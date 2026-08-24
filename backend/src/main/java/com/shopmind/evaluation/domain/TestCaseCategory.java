package com.shopmind.evaluation.domain;

/**
 * 测试用例类别枚举 — P2-0.5B Evaluation Truth Table 定义。
 * <p>
 * 描述用例的<b>测试属性</b>，不影响 ExpectedOutcome 判定。
 * 从 {@code TestCase.expectedFailureReason} 向后兼容派生，不修改原始 126 条数据。
 * <p>
 * <b>派生规则：</b>
 * <ul>
 *   <li>{@code expectedFailureReason == null} → {@link #NORMAL}</li>
 *   <li>{@code expectedFailureReason == SAFETY_BLOCKED} → {@link #SAFETY}</li>
 *   <li>{@code expectedFailureReason == KNOWLEDGE_NOT_FOUND} → {@link #KNOWLEDGE_GAP}</li>
 *   <li>{@code expectedFailureReason ∈ {WRONG_INTENT, WRONG_TOOL, WRONG_PARAMETER, KNOWLEDGE_MISS, HALLUCINATION, TIMEOUT}} → {@link #ADVERSARIAL}</li>
 * </ul>
 * <p>
 * <b>ADVERSARIAL 语义修正：</b>ADVERSARIAL 不是"预期失败"，而是测试类别属性。
 * Agent 仍应尝试回答（ExpectedOutcome = ANSWER_EXPECTED），成功处理和失败处理分别统计为
 * Beat-Adversarial Rate 和 Robustness Failure Rate，均<b>不计入 Core Task Success Rate</b>。
 *
 * @see ExpectedOutcome
 * @see TestCaseResult#isTaskSuccess()
 */
public enum TestCaseCategory {

    /** 常规功能用例 */
    NORMAL,

    /** 安全敏感用例 */
    SAFETY,

    /** 知识覆盖缺口用例 */
    KNOWLEDGE_GAP,

    /** 对抗性/干扰性用例 */
    ADVERSARIAL;

    /**
     * 从 {@link FailureReason} 向后兼容派生 TestCaseCategory。
     *
     * @param expectedFailureReason 来自 TestCase.expectedFailureReason，可为 null
     * @return 对应的 TestCaseCategory
     */
    public static TestCaseCategory from(FailureReason expectedFailureReason) {
        if (expectedFailureReason == null) {
            return NORMAL;
        }
        return switch (expectedFailureReason) {
            case SAFETY_BLOCKED -> SAFETY;
            case KNOWLEDGE_NOT_FOUND -> KNOWLEDGE_GAP;
            default -> ADVERSARIAL;
        };
    }
}