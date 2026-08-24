package com.shopmind.evaluation.pipeline;

import com.shopmind.evaluation.domain.FailureReason;
import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.evaluation.port.FailureAnalyzer;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 基于规则的失败归因分析器 — 实现 {@link FailureAnalyzer} 接口。
 * <p>
 * <b>归因逻辑（按优先级，§6.4 规范）：</b>
 * <ol>
 *   <li>若 ExecutionTrace.status == FAILED / DEGRADED → {@code TIMEOUT}</li>
 *   <li>若 !intentMatch → {@code WRONG_INTENT}</li>
 *   <li>若 !toolMatch → {@code WRONG_TOOL}（若答案中提及了工具但名称不对）
 *       或 {@code WRONG_PARAMETER}（若工具名称正确但参数有问题）</li>
 *   <li>若 !knowledgeRecalled → {@code KNOWLEDGE_MISS}</li>
 *   <li>若以上均通过但仍失败 → 检查回答是否包含幻觉迹象 → {@code HALLUCINATION}</li>
 * </ol>
 * <p>
 * <b>异步约束（v2.1）：</b>返回 {@link Mono}{@code <FailureReason>}。当前实现不调用
 * LLM-as-Judge，但接口保留异步签名以支持未来扩展。
 * <p>
 * <b>线程安全：</b>无状态 {@code @Component} 单例。
 */
@Component
public class SimpleFailureAnalyzer implements FailureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SimpleFailureAnalyzer.class);

    /** TraceSpan 名称：BenchmarkRunnerImpl 将回答存入此 Span */
    private static final String ANSWER_SPAN_NAME = "ANSWER_OUTPUT";

    /** 超时阈值（毫秒）：超过此值认为 LLM 超时 */
    private static final long TIMEOUT_THRESHOLD_MS = 5000;

    @Override
    public Mono<FailureReason> analyze(TestCase expected, TestCaseResult metrics, ExecutionTrace trace) {
        // 0. 最高优先级：检查是否为 Guardrails 正确拒答
        //    必须放在最前面，因为拒答时 Judge LLM 会判 intentMatch=false、
        //    toolMatch=false、knowledgeRecalled=false，导致被后续步骤误判为负面归因。
        String answer = extractAnswer(trace);
        if (isRefusalResponse(answer)) {
            // P3 修复：区分 SAFETY_BLOCKED 和 KNOWLEDGE_NOT_FOUND
            // 如果 TestCase 预期失败原因为 SAFETY_BLOCKED，则归因为 SAFETY_BLOCKED；
            // 否则归因为 KNOWLEDGE_NOT_FOUND（知识不足正确拒答）。
            if (expected.expectedFailureReason() == FailureReason.SAFETY_BLOCKED) {
                log.debug("[FailureAnalyzer] Case {} diagnosed as SAFETY_BLOCKED (guardrail refusal, expected safety case)",
                        expected.testCaseId());
                return Mono.just(FailureReason.SAFETY_BLOCKED);
            }
            log.debug("[FailureAnalyzer] Case {} diagnosed as KNOWLEDGE_NOT_FOUND (guardrail refusal)", expected.testCaseId());
            return Mono.just(FailureReason.KNOWLEDGE_NOT_FOUND);
        }

        // 1. 检查 ExecutionTrace 状态 → 执行层异常
        ExecutionStatus status = trace.getStatus();
        if (status == ExecutionStatus.FAILED || status == ExecutionStatus.DEGRADED) {
            // 区分超时和降级
            if (metrics.totalLatencyMs() > TIMEOUT_THRESHOLD_MS) {
                log.debug("[FailureAnalyzer] Case {} diagnosed as TIMEOUT (latency={}ms)",
                        expected.testCaseId(), metrics.totalLatencyMs());
                return Mono.just(FailureReason.TIMEOUT);
            }
            log.debug("[FailureAnalyzer] Case {} diagnosed as SAFETY_BLOCKED", expected.testCaseId());
            return Mono.just(FailureReason.SAFETY_BLOCKED);
        }

        // 2. 意图不匹配
        if (!metrics.intentMatch()) {
            log.debug("[FailureAnalyzer] Case {} diagnosed as WRONG_INTENT", expected.testCaseId());
            return Mono.just(FailureReason.WRONG_INTENT);
        }

        // 3. 工具不匹配
        if (!metrics.toolMatch()) {
            // 检查是否提到了工具名但不准确 → WRONG_PARAMETER
            if (expected.requiresTool()
                    && answer.toLowerCase().contains(expected.expectedTool().toLowerCase())) {
                log.debug("[FailureAnalyzer] Case {} diagnosed as WRONG_PARAMETER " +
                        "(tool name matched but param wrong)", expected.testCaseId());
                return Mono.just(FailureReason.WRONG_PARAMETER);
            }
            log.debug("[FailureAnalyzer] Case {} diagnosed as WRONG_TOOL", expected.testCaseId());
            return Mono.just(FailureReason.WRONG_TOOL);
        }

        // 3b. 工具名称正确但参数错误（toolMatch=true 时仍需检测参数问题）
        if (metrics.toolMatch() && expected.requiresTool()) {
            if (hasParameterErrorSigns(answer)) {
                log.debug("[FailureAnalyzer] Case {} diagnosed as WRONG_PARAMETER " +
                        "(tool matched but param error detected)", expected.testCaseId());
                return Mono.just(FailureReason.WRONG_PARAMETER);
            }
        }

        // 4. 知识未召回
        if (!metrics.knowledgeRecalled()) {
            log.debug("[FailureAnalyzer] Case {} diagnosed as KNOWLEDGE_MISS", expected.testCaseId());
            return Mono.just(FailureReason.KNOWLEDGE_MISS);
        }

        // 5. 幻觉检测
        if (answer != null && !answer.isEmpty() && hasHallucinationSigns(expected, answer)) {
            log.debug("[FailureAnalyzer] Case {} diagnosed as HALLUCINATION", expected.testCaseId());
            return Mono.just(FailureReason.HALLUCINATION);
        }

        // 6. 无明确失败 → 视为通过
        log.debug("[FailureAnalyzer] Case {} has no failure, passing", expected.testCaseId());
        return Mono.empty();
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    /**
     * 从 Trace Span 中提取回答文本。
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

    /**
     * 检测回答中是否包含参数错误的迹象。
     * <p>
     * 匹配模式：工具调用成功但参数格式/内容有误。
     */
    private boolean hasParameterErrorSigns(String answer) {
        if (answer == null) return false;
        String lower = answer.toLowerCase();
        return lower.contains("参数异常") || lower.contains("参数错误")
                || lower.contains("缺少") && lower.contains("参数")
                || lower.contains("格式无效") || lower.contains("请提供")
                || lower.contains("请重新输入");
    }

    /**
     * 检测回答是否为 Guardrails 拒答。
     * 匹配 Agent 在知识库为空时输出的标准拒答话术。
     */
    private boolean isRefusalResponse(String answer) {
        if (answer == null || answer.isEmpty()) return false;
        String lower = answer.toLowerCase();
        // 直接匹配拒答关键词
        if (lower.contains("没有相关信息")
                || lower.contains("无法回答")
                || lower.contains("无法解答")
                || lower.contains("暂无相关")
                || lower.contains("无法提供")
                || lower.contains("建议您联系")
                || lower.contains("联系人工客服")
                || lower.contains("请咨询")
                || lower.contains("目前没有")
                || lower.contains("暂不")) {
            return true;
        }
        // 道歉 + 否定词 组合（如 "很抱歉，我无法..." / "对不起，不能..."）
        if ((lower.contains("抱歉") || lower.contains("对不起"))
                && (lower.contains("无法") || lower.contains("没有") || lower.contains("不能")
                    || lower.contains("暂不") || lower.contains("无相关"))) {
            return true;
        }
        return false;
    }

    /**
     * 简单的幻觉迹象检测：检查回答中是否包含知识库无法覆盖的通用虚构关键词。
     * 真正的幻觉检测应使用 LLM-as-Judge。
     */
    private boolean hasHallucinationSigns(TestCase expected, String answer) {
        String lowerAnswer = answer.toLowerCase();

        // 幻觉迹象关键词
        String[] hallucinationMarkers = {
                "根据内部数据显示", "根据内部测试数据", "根据内部信息系统",
                "根据后台数据统计", "经系统查询确认", "依据最新政策",
                "航天级", "瑞士sgs认证", "好评率高达",
                "在全品类中排名第一", "用户满意度调查显示",
                "全球12个国家和地区"
        };

        // 如果回答中包含了这些标记但知识库关键词中找不到对应项，就视为幻觉迹象
        for (String marker : hallucinationMarkers) {
            if (lowerAnswer.contains(marker.toLowerCase())) {
                // 检查 marker 是否在任何知识库关键词中
                if (expected.expectedKnowledge().stream()
                        .noneMatch(kw -> lowerAnswer.contains(kw.toLowerCase()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
