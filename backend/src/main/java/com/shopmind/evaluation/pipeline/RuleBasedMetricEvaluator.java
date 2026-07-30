package com.shopmind.evaluation.pipeline;

import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.evaluation.port.MetricEvaluator;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于规则的指标评估器 — 实现 {@link MetricEvaluator} 接口。
 * <p>
 * <b>判卷逻辑：</b>
 * <ol>
 *   <li><b>意图匹配：</b>从 Trace Span "ANSWER_OUTPUT" 中提取 Agent 回答文本，
 *       检查回答中是否包含意图相关的关键词</li>
 *   <li><b>工具匹配：</b>检查回答中是否提及预期工具名称；
 *       若 TestCase 不需要工具调用则默认通过</li>
 *   <li><b>知识召回：</b>检查回答中是否包含 TestCase.expectedKnowledge 中的关键词；
 *       若 TestCase 不需要知识则默认通过</li>
 *   <li><b>性能指标：</b>从 ExecutionTrace.getMetrics() 提取 TTFT、Token 等数据</li>
 * </ol>
 * <p>
 * <b>异步约束（v2.1）：</b>返回 {@link Mono}{@code <TestCaseResult>}，即使当前实现
 * 是纯 CPU 计算，也使用 {@code Mono.just()} 包裹以保持接口一致性。
 * <p>
 * <b>线程安全：</b>无状态 {@code @Component} 单例，所有数据通过方法参数传入。
 */
@Component
@Profile("!deepseek")
public class RuleBasedMetricEvaluator implements MetricEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedMetricEvaluator.class);

    /** TraceSpan 名称常量：BenchmarkRunnerImpl 将回答文本存入此 Span */
    private static final String ANSWER_SPAN_NAME = "ANSWER_OUTPUT";

    @Override
    public Mono<TestCaseResult> evaluate(TestCase expected, ExecutionTrace actual) {
        // 1. 从 Trace Span 中提取 Agent 回答文本
        String answer = extractAnswer(actual);

        // 2. 从 Trace 提取性能指标
        long ttftMs = actual.getMetrics().getTtftMs();
        long totalLatencyMs = actual.getTotalLatencyMs();
        int promptTokens = actual.getMetrics().getPromptTokens();
        int completionTokens = actual.getMetrics().getCompletionTokens();

        // 3. 意图匹配（回答中是否包含预期意图的关键词）
        boolean intentMatch = evaluateIntent(expected, answer, actual);

        // 4. 工具匹配
        boolean toolMatch = evaluateTool(expected, answer);

        // 5. 知识召回
        boolean knowledgeRecalled = evaluateKnowledge(expected, answer);
        double recallAtK = computeRecallAtK(expected, answer);

        log.debug("[RuleBasedMetric] Case {}: intent={}, tool={}, knowledge={}, recall@K={}",
                expected.testCaseId(), intentMatch, toolMatch, knowledgeRecalled, recallAtK);

        return Mono.just(new TestCaseResult(
                expected.testCaseId(),
                expected.query(),
                intentMatch,
                toolMatch,
                knowledgeRecalled,
                recallAtK,
                ttftMs,
                totalLatencyMs,
                promptTokens,
                completionTokens,
                null,  // failureReason 由 FailureAnalyzer 后续设置
                truncateAnswer(answer),
                Collections.emptyMap()
        ));
    }

    // ============================================================
    //  判卷子方法
    // ============================================================

    /**
     * 意图判断：检查回答文本中是否包含与预期意图相关的关键词。
     * <p>
     * 简化策略：将 expectedIntent 转为小写后检查是否为回答的子串，
     * 并对常见意图进行模糊匹配。
     */
    private boolean evaluateIntent(TestCase expected, String answer, ExecutionTrace trace) {
        if (expected.expectedIntent() == null || expected.expectedIntent().isBlank()) {
            return true; // 未指定意图 → 默认通过
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }

        String lowerAnswer = answer.toLowerCase();
        String expectedIntent = expected.expectedIntent().toLowerCase();

        // 直接匹配
        if (lowerAnswer.contains(expectedIntent)) {
            return true;
        }

        // 意图关键词映射（常见意图的别名）
        return switch (expectedIntent) {
            case "return_policy"   -> lowerAnswer.contains("退货") || lowerAnswer.contains("退款") || lowerAnswer.contains("退换") || lowerAnswer.contains("换货");
            case "order_query"     -> lowerAnswer.contains("订单") || lowerAnswer.contains("order") || lowerAnswer.contains("物流") || lowerAnswer.contains("发货") || lowerAnswer.contains("地址") || lowerAnswer.contains("快递") || lowerAnswer.contains("优惠券");
            case "product_info"    -> lowerAnswer.contains("商品") || lowerAnswer.contains("产品") || lowerAnswer.contains("手机") || lowerAnswer.contains("这款") || lowerAnswer.contains("材质") || lowerAnswer.contains("推荐") || lowerAnswer.contains("面料");
            case "payment"         -> lowerAnswer.contains("支付") || lowerAnswer.contains("付款") || lowerAnswer.contains("退款") || lowerAnswer.contains("发票") || lowerAnswer.contains("花呗") || lowerAnswer.contains("支付宝");
            case "greeting"        -> lowerAnswer.contains("你好") || lowerAnswer.contains("欢迎") || lowerAnswer.contains("您好") || lowerAnswer.contains("请问") || lowerAnswer.contains("客服");
            default                -> false;
        };
    }

    /**
     * 工具匹配：检查回答中是否提及了预期工具名称。
     * 若 TestCase 不需要工具调用（expectedTool 为 null），则默认通过。
     */
    private boolean evaluateTool(TestCase expected, String answer) {
        if (!expected.requiresTool()) {
            return true; // 本用例不涉及工具调用
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return answer.toLowerCase().contains(expected.expectedTool().toLowerCase());
    }

    /**
     * 知识召回评估：检查回答中是否包含预期知识的关键词。
     * 若 TestCase 不需要知识召回，则默认通过。
     */
    private boolean evaluateKnowledge(TestCase expected, String answer) {
        if (!expected.requiresKnowledge()) {
            return true; // 本用例不涉及知识召回
        }
        if (answer == null || answer.isBlank()) {
            return false;
        }

        String lowerAnswer = answer.toLowerCase();
        long hitCount = expected.expectedKnowledge().stream()
                .filter(kw -> lowerAnswer.contains(kw.toLowerCase()))
                .count();

        // 至少命中一半的关键词才算通过
        return hitCount > 0 && hitCount >= expected.expectedKnowledge().size() / 2.0;
    }

    /**
     * 计算 Recall@K：命中关键词数 / 预期关键词总数。
     */
    private double computeRecallAtK(TestCase expected, String answer) {
        if (!expected.requiresKnowledge() || answer == null) {
            return 1.0;
        }

        String lowerAnswer = answer.toLowerCase();
        long hitCount = expected.expectedKnowledge().stream()
                .filter(kw -> lowerAnswer.contains(kw.toLowerCase()))
                .count();

        return (double) hitCount / Math.max(expected.expectedKnowledge().size(), 1);
    }

    // ============================================================
    //  从 ExecutionTrace 提取数据
    // ============================================================

    /**
     * 从 ExecutionTrace 的 Span 列表中提取 "ANSWER_OUTPUT" span 中的回答文本。
     */
    private String extractAnswer(ExecutionTrace trace) {
        List<TraceSpan> spans = trace.getSpans();
        if (spans == null) return "";

        for (TraceSpan span : spans) {
            if (ANSWER_SPAN_NAME.equals(span.getStepName())) {
                Map<String, Object> output = span.getOutput();
                if (output != null) {
                    Object answerObj = output.get("answer");
                    if (answerObj != null) {
                        return answerObj.toString();
                    }
                }
            }
        }
        return "";
    }

    private static String truncateAnswer(String answer) {
        if (answer == null) return "";
        if (answer.length() <= 500) return answer;
        return answer.substring(0, 500) + "... (truncated)";
    }
}
