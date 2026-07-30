package com.shopmind.evaluation.domain;

import java.util.Collections;
import java.util.Map;

/**
 * 单用例评估结果 — 6_Evaluation_Engine.md §5.2 规范（v2.1 新增）。
 * <p>
 * 由 {@link com.shopmind.evaluation.port.MetricEvaluator} 对单个 TestCase
 * 进行指标计算后生成。包含所有评估维度的布尔判定、数值指标和失败原因。
 * 同时作为 {@link com.shopmind.evaluation.port.FailureAnalyzer} 的输入。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param testCaseId         用例 ID
 * @param query              用户原始输入查询文本
 * @param intentMatch        意图识别是否匹配预期
 * @param toolMatch          工具选择是否匹配预期
 * @param knowledgeRecalled  知识是否正确召回
 * @param recallAtK          知识召回率（命中数 / K）
 * @param ttftMs             首字延迟（毫秒）
 * @param totalLatencyMs     端到端延迟（毫秒）
 * @param promptTokens       输入 Token 数
 * @param completionTokens   输出 Token 数
 * @param failureReason      失败原因（null 表示成功）
 * @param answerSnippet      实际回复片段（截断，用于人工审核）
 * @param rawMetrics         扩展指标 Map（如 safety_score, confidence_score）
 */
public record TestCaseResult(
        String testCaseId,
        String query,
        boolean intentMatch,
        boolean toolMatch,
        boolean knowledgeRecalled,
        double recallAtK,
        long ttftMs,
        long totalLatencyMs,
        int promptTokens,
        int completionTokens,
        FailureReason failureReason,
        String answerSnippet,
        Map<String, Object> rawMetrics
) {

    /**
     * 紧凑构造器：确保 rawMetrics 不为 null。
     */
    public TestCaseResult {
        rawMetrics = rawMetrics != null ? Collections.unmodifiableMap(rawMetrics) : Collections.emptyMap();
    }

    /**
     * 判断该用例在所有关键维度上是否全部通过。
     * 全部通过 = 意图正确 + 工具正确 + 知识已召回 + 无失败原因。
     */
    public boolean isAllPassed() {
        return intentMatch && toolMatch && knowledgeRecalled && failureReason == null;
    }

    /**
     * 判断是否有任何维度未通过。
     */
    public boolean hasAnyFailure() {
        return !isAllPassed();
    }

    /**
     * 获取失败原因的标签文本（用于报告）。
     */
    public String failureLabel() {
        return failureReason != null ? failureReason.getLabel() : "通过";
    }

    /**
     * 创建一个全部通过的用例结果（用于纯闲聊等无工具/知识的场景）。
     */
    public static TestCaseResult allPassed(String testCaseId, String query, long ttftMs, long totalLatencyMs,
                                            int promptTokens, int completionTokens, String answerSnippet) {
        return new TestCaseResult(
                testCaseId, query, true, true, true, 1.0,
                ttftMs, totalLatencyMs, promptTokens, completionTokens,
                null, answerSnippet, Collections.emptyMap()
        );
    }

    /**
     * 创建一个带失败原因的用例结果。
     */
    public static TestCaseResult failed(String testCaseId, String query, FailureReason reason,
                                         long ttftMs, long totalLatencyMs,
                                         int promptTokens, int completionTokens,
                                         String answerSnippet) {
        return new TestCaseResult(
                testCaseId, query, false, false, false, 0.0,
                ttftMs, totalLatencyMs, promptTokens, completionTokens,
                reason, answerSnippet, Collections.emptyMap()
        );
    }
}
