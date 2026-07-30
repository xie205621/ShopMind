package com.shopmind.evaluation.domain;

import java.util.Collections;
import java.util.List;

/**
 * 单个评测用例 — 6_Evaluation_Engine.md §5.1 规范。
 * <p>
 * 每个 TestCase 定义了一个完整的"输入-期望输出"对，作为 Ground Truth
 * 用于与 Agent 实际输出进行对比评测。
 * <p>
 * 使用 Java record 确保不可变语义——Ground Truth 不应在评测过程中被修改。
 *
 * @param testCaseId             用例唯一标识（Phase D: 格式为 {SCENARIO}-{NNN}，如 NORMAL-001）
 * @param query                  用户输入的自然语言查询
 * @param expectedIntent         预期意图分类（如 "RETURN_POLICY", "ORDER_QUERY"）
 * @param expectedTool           预期调用的工具名（如 "queryOrder"）。如果本用例不涉及工具调用，可为 null
 * @param expectedKnowledge      预期命中的知识关键词列表（用于计算 Recall@K）
 * @param expectedAnswer         预期回答（用于成功用例的答案相似度评测，可为 null）
 * @param expectedFailureReason  预期失败原因（用于失败用例，成功用例为 null）
 */
public record TestCase(
        String testCaseId,
        String query,
        String expectedIntent,
        String expectedTool,
        List<String> expectedKnowledge,
        String expectedAnswer,
        FailureReason expectedFailureReason
) {

    /**
     * 紧凑构造器：防御性拷贝，确保 expectedKnowledge 不为 null。
     */
    public TestCase {
        expectedKnowledge = expectedKnowledge != null
                ? Collections.unmodifiableList(expectedKnowledge)
                : Collections.emptyList();
    }

    /**
     * 构造成功用例（无失败原因）。
     */
    public TestCase(String testCaseId, String query, String expectedIntent,
                    String expectedTool, List<String> expectedKnowledge, String expectedAnswer) {
        this(testCaseId, query, expectedIntent, expectedTool, expectedKnowledge, expectedAnswer, null);
    }

    /**
     * 构造标准用例（无预期回答、无失败原因 — 向后兼容 Phase A/B）。
     */
    public TestCase(String testCaseId, String query, String expectedIntent,
                    String expectedTool, List<String> expectedKnowledge) {
        this(testCaseId, query, expectedIntent, expectedTool, expectedKnowledge, null, null);
    }

    /**
     * 本用例是否需要工具调用。
     */
    public boolean requiresTool() {
        return expectedTool != null && !expectedTool.isBlank();
    }

    /**
     * 本用例是否需要知识库召回。
     */
    public boolean requiresKnowledge() {
        return !expectedKnowledge.isEmpty();
    }

    /**
     * 本用例是否为失败用例（预期会触发某种 FailureReason）。
     */
    public boolean isFailureCase() {
        return expectedFailureReason != null;
    }

    /**
     * 判断 Agent 的意图分类是否匹配预期。
     *
     * @param actualIntent Agent 实际识别的意图分类
     */
    public boolean isIntentMatched(String actualIntent) {
        return expectedIntent != null && expectedIntent.equalsIgnoreCase(actualIntent);
    }
}
