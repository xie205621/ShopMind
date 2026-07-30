package com.shopmind.evaluation.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.evaluation.port.MetricEvaluator;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.SystemMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM-as-Judge 指标评估器 — 用 LLM 替代关键词匹配进行语义评估。
 * <p>
 * <b>核心理念：</b>用第二路 LLM（Judge）对 Agent 回答进行多维度语义评判，
 * 取代 {@link RuleBasedMetricEvaluator} 的精确字符串匹配。
 * <p>
 * <b>评估维度：</b>
 * <ol>
 *   <li><b>Intent Match (意图匹配)：</b>Agent 回答是否覆盖了用户意图？(0-100)</li>
 *   <li><b>Tool Selection (工具选择)：</b>是否选择了正确的工具？(0-100)</li>
 *   <li><b>Task Success (任务完成)：</b>用户需求是否被完全满足？(0-100)</li>
 *   <li><b>Hallucination (幻觉检测)：</b>回答中虚假信息程度？(0-100，0=无幻觉)</li>
 *   <li><b>Knowledge Recall (知识召回)：</b>关键知识点覆盖率？(0-100)</li>
 * </ol>
 * <p>
 * <b>激活条件：</b>{@code @Profile("deepseek")}，需搭配 {@link com.shopmind.orchestrator.adapter.DeepSeekChatAdapter}。
 * <p>
 * <b>阈值设定：</b>每维 >= 60 分视为通过，hallucination <= 30 视为无幻觉。
 * <p>
 * <b>注意：</b>LLM-as-Judge 会使 API 调用量翻倍（每个用例 1 次 Agent + 1 次 Judge），
 * 但评估质量从"关键词匹配"跃升为"语义理解"。
 */
@Component
@Profile("deepseek")
public class LlmJudgeMetricEvaluator implements MetricEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeMetricEvaluator.class);

    private static final String ANSWER_SPAN_NAME = "ANSWER_OUTPUT";
    private static final Duration JUDGE_TIMEOUT = Duration.ofSeconds(30);

    /** 各维度通过阈值（0-100 分数制） */
    private static final int PASS_THRESHOLD = 60;
    /** 幻觉分数 <= 此值视为无幻觉 */
    private static final int HALLUCINATION_THRESHOLD = 30;

    private final ChatModelPort judgeLlm;
    private final ObjectMapper objectMapper;

    public LlmJudgeMetricEvaluator(ChatModelPort judgeLlm, ObjectMapper objectMapper) {
        this.judgeLlm = judgeLlm;
        this.objectMapper = objectMapper;
        log.info("[LlmJudge] Initialized with judge LLM: {}", judgeLlm.getClass().getSimpleName());
    }

    @Override
    public Mono<TestCaseResult> evaluate(TestCase expected, ExecutionTrace actual) {
        String answer = extractAnswer(actual);

        // 性能指标从 Trace 提取
        long ttftMs = actual.getMetrics().getTtftMs();
        long totalLatencyMs = actual.getTotalLatencyMs();
        int promptTokens = actual.getMetrics().getPromptTokens();
        int completionTokens = actual.getMetrics().getCompletionTokens();

        // 构建 Judge Prompt
        String judgePrompt = buildJudgePrompt(expected, answer);

        // 调用 Judge LLM
        ChatMessage systemMsg = new SystemMessage(JUDGE_SYSTEM_PROMPT);
        ChatMessage userMsg = new UserMessage(judgePrompt);

        return judgeLlm.stream(List.of(systemMsg, userMsg), List.of())
                .collect(Collectors.joining())
                .timeout(JUDGE_TIMEOUT)
                .map(judgeResponse -> parseJudgment(expected, judgeResponse, answer,
                        ttftMs, totalLatencyMs, promptTokens, completionTokens))
                .doOnNext(result -> log.debug("[LlmJudge] Case {}: intent={}, tool={}, task={}, "
                                + "hallucination={}, knowledge={}",
                        expected.testCaseId(),
                        result.intentMatch(), result.toolMatch(), result.isAllPassed(),
                        result.failureReason(), result.knowledgeRecalled()))
                .onErrorResume(e -> {
                    log.warn("[LlmJudge] Judge failed for case {}, falling back: {}",
                            expected.testCaseId(), e.getMessage());
                    return Mono.just(fallbackResult(expected, answer,
                            ttftMs, totalLatencyMs, promptTokens, completionTokens));
                });
    }

    // ============================================================
    //  Judge Prompt
    // ============================================================

    /**
     * Judge 系统提示词。要求输出严格的 JSON 格式。
     */
    private static final String JUDGE_SYSTEM_PROMPT =
            "You are an impartial evaluation judge for AI agent responses. "
            + "Your task is to rate an agent's answer against expected criteria. "
            + "Output ONLY valid JSON (no markdown, no extra text) in exactly this format:\n"
            + "{\"intent_match\":N,\"tool_selection\":N,\"task_success\":N,\"hallucination\":N,\"knowledge_recall\":N,\"safety_refusal\":N,\"explanation\":\"...\"}\n"
            + "Each N is an integer 0-100. hallucination: 0=perfectly factual, 100=completely fabricated.\n"
            + "safety_refusal: 100=agent correctly refused to answer when knowledge was unavailable, 0=agent should have refused but fabricated an answer.\n"
            + "Be objective and consistent. Base your judgment on semantic meaning, not exact wording.";

    /**
     * 构建每个用例的 Judge 评估提示词。
     */
    private String buildJudgePrompt(TestCase tc, String answer) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== EVALUATION TASK ===\n\n");
        sb.append("USER QUERY: ").append(tc.query()).append("\n\n");

        if (tc.expectedIntent() != null && !tc.expectedIntent().isBlank()) {
            sb.append("EXPECTED INTENT: ").append(tc.expectedIntent()).append("\n");
        }
        if (tc.expectedTool() != null && !tc.expectedTool().isBlank()) {
            sb.append("EXPECTED TOOL: ").append(tc.expectedTool()).append("\n");
        }
        if (tc.expectedKnowledge() != null && !tc.expectedKnowledge().isEmpty()) {
            sb.append("REQUIRED KNOWLEDGE POINTS: ").append(String.join(" | ", tc.expectedKnowledge())).append("\n");
        }
        if (tc.expectedAnswer() != null && !tc.expectedAnswer().isBlank()) {
            sb.append("REFERENCE ANSWER (for context, not exact match): ").append(tc.expectedAnswer()).append("\n");
        }
        if (tc.expectedFailureReason() != null) {
            sb.append("NOTE: This is expected to be a FAILURE case (")
                    .append(tc.expectedFailureReason().getLabel()).append(").\n");
        }

        sb.append("\nAGENT'S ACTUAL RESPONSE:\n\"\"\"\n")
                .append(answer != null ? answer : "[EMPTY]")
                .append("\n\"\"\"\n\n");

        sb.append("=== RATING GUIDELINES ===\n");
        sb.append("- intent_match (0-100): How well does the response address the intended topic/domain?\n");
        sb.append("- tool_selection (0-100): Was the correct tool selected?\n");
        sb.append("- task_success (0-100): Did the agent fully satisfy the user's request?\n");
        sb.append("- hallucination (0-100): Fabricated info level? (0=all factual, 100=pure fabrication)\n");
        sb.append("- knowledge_recall (0-100): Were key knowledge points covered?\n");
        sb.append("- safety_refusal (0-100): Did agent correctly refuse when knowledge unavailable? (100=correct refusal, 0=fabricated answer)\n");
        sb.append("\nRespond with ONLY the JSON object (no markdown fences):");

        return sb.toString();
    }

    // ============================================================
    //  Judgment 解析
    // ============================================================

    /**
     * 解析 Judge LLM 返回的 JSON，转换为 TestCaseResult。
     */
    private TestCaseResult parseJudgment(TestCase tc, String judgeResponse, String answer,
                                          long ttftMs, long totalLatencyMs,
                                          int promptTokens, int completionTokens) {
        try {
            String cleanJson = extractJson(judgeResponse);

            JsonNode root = objectMapper.readTree(cleanJson);

            int intentScore = getInt(root, "intent_match", 0);
            int toolScore = getInt(root, "tool_selection", 0);
            int taskScore = getInt(root, "task_success", 0);
            int hallucinationScore = getInt(root, "hallucination", 0);
            int knowledgeScore = getInt(root, "knowledge_recall", 0);
            int safetyRefusalScore = getInt(root, "safety_refusal", 0);

            boolean intentMatch = intentScore >= PASS_THRESHOLD;
            boolean toolMatch = toolScore >= PASS_THRESHOLD;
            boolean knowledgeRecalled = knowledgeScore >= PASS_THRESHOLD;

            // 幻觉检测：分数高 = 幻觉多
            boolean hasHallucination = hallucinationScore > HALLUCINATION_THRESHOLD;

            // 归一化 Recall@K
            double recallAtK = knowledgeScore / 100.0;

            // 将各维度分数存入 rawMetrics 供报告使用
            Map<String, Object> rawMetrics = Map.of(
                    "judge_intent_score", intentScore,
                    "judge_tool_score", toolScore,
                    "judge_task_score", taskScore,
                    "judge_hallucination_score", hallucinationScore,
                    "judge_knowledge_score", knowledgeScore,
                    "judge_safety_refusal_score", safetyRefusalScore,
                    "judge_method", "llm-as-judge"
            );

            log.debug("[LlmJudge] Case {} scores: intent={}, tool={}, task={}, hallu={}, know={}",
                    tc.testCaseId(), intentScore, toolScore, taskScore, hallucinationScore, knowledgeScore);

            return new TestCaseResult(
                    tc.testCaseId(),
                    tc.query(),
                    intentMatch,
                    toolMatch,
                    knowledgeRecalled,
                    recallAtK,
                    ttftMs,
                    totalLatencyMs,
                    promptTokens,
                    completionTokens,
                    null, // failureReason 由 FailureAnalyzer 设置
                    truncateAnswer(answer),
                    rawMetrics
            );
        } catch (JsonProcessingException e) {
            log.warn("[LlmJudge] Failed to parse judge response for case {}: {}",
                    tc.testCaseId(), judgeResponse.substring(0, Math.min(100, judgeResponse.length())));
            return fallbackResult(tc, answer, ttftMs, totalLatencyMs, promptTokens, completionTokens);
        }
    }

    /**
     * 从 LLM 响应中提取 JSON — 处理 markdown fence 和 LLM 额外文字。
     * <ul>
     *   <li>直接 JSON: {@code {"key": "val"}} → 原样返回</li>
     *   <li>Markdown fence: {@code ```json\n{...}\n```} → 提取内容</li>
     *   <li>混排文字: {@code Here is:\n```json\n{...}\n```} → 提取内容</li>
     *   <li>兜底: 找第一个 { 和最后一个 } → 截取</li>
     * </ul>
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }
        String trimmed = response.trim();

        // Case 1: 直接就是 JSON — starts with {
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // Case 2: Markdown fenced code block (```json ... ```)
        Pattern fence = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
        Matcher m = fence.matcher(trimmed);
        if (m.find()) {
            String content = m.group(1).trim();
            if (content.startsWith("{")) {
                return content;
            }
        }

        // Case 3: 混排 — 找第一个 { 和最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1).trim();
        }

        // 无法提取 JSON
        return trimmed;
    }

    private int getInt(JsonNode root, String field, int defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return defaultValue;
        if (node.isInt()) return node.asInt();
        // 处理浮点数（如 85.5）和字符串（如 "85"）
        try {
            return (int) Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ============================================================
    //  降级与工具方法
    // ============================================================

    /**
     * Judge 调用失败时的降级结果。
     * 降级时所有维度打分为 0（不通过），避免错误地标记为 PASS。
     */
    private TestCaseResult fallbackResult(TestCase tc, String answer,
                                           long ttftMs, long totalLatencyMs,
                                           int promptTokens, int completionTokens) {
        return new TestCaseResult(
                tc.testCaseId(),
                tc.query(),
                false, // 降级：默认不通过，避免误判为 PASS
                false,
                false,
                0.0,
                ttftMs,
                totalLatencyMs,
                promptTokens,
                completionTokens,
                null,
                truncateAnswer(answer),
                Map.of("judge_method", "llm-as-judge-fallback",
                       "judge_error", "Evaluation failed, all dimensions default to fail")
        );
    }

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
