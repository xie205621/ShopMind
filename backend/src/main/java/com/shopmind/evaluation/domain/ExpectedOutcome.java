package com.shopmind.evaluation.domain;

/**
 * 预期行为枚举 — P2-0.5B Evaluation Truth Table 定义。
 * <p>
 * 描述 Agent 在此用例上<b>应该做什么</b>，与 {@link FailureReason} 解耦。
 * 从 {@code TestCase.expectedFailureReason} 向后兼容派生，不修改原始 126 条数据。
 * <p>
 * <b>派生规则：</b>
 * <ul>
 *   <li>{@code expectedFailureReason == null} → {@link #ANSWER_EXPECTED}</li>
 *   <li>{@code expectedFailureReason ∈ {SAFETY_BLOCKED, KNOWLEDGE_NOT_FOUND}} → {@link #REFUSE_EXPECTED}</li>
 *   <li>{@code expectedFailureReason ∈ {WRONG_INTENT, WRONG_TOOL, WRONG_PARAMETER, KNOWLEDGE_MISS, HALLUCINATION, TIMEOUT}} → {@link #ANSWER_EXPECTED}</li>
 * </ul>
 *
 * @see TestCaseCategory
 * @see TestCaseResult#isTaskSuccess()
 */
public enum ExpectedOutcome {

    /** Agent 应正确回答 */
    ANSWER_EXPECTED,

    /** Agent 应拒答 */
    REFUSE_EXPECTED;

    /**
     * 从 {@link FailureReason} 向后兼容派生 ExpectedOutcome。
     *
     * @param expectedFailureReason 来自 TestCase.expectedFailureReason，可为 null
     * @return 对应的 ExpectedOutcome
     */
    public static ExpectedOutcome from(FailureReason expectedFailureReason) {
        if (expectedFailureReason == null) {
            return ANSWER_EXPECTED;
        }
        return switch (expectedFailureReason) {
            case SAFETY_BLOCKED, KNOWLEDGE_NOT_FOUND -> REFUSE_EXPECTED;
            default -> ANSWER_EXPECTED;
        };
    }
}