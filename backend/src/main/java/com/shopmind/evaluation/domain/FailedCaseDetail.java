package com.shopmind.evaluation.domain;

/**
 * 失败用例详情 — 6_Evaluation_Engine.md §5.3 规范（v2.1 新增）。
 * <p>
 * 采样失败的 TestCase，记录其原始输入、失败原因、实际回复和诊断信息，
 * 供人工审核和算法改进。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param testCaseId      失败的用例 ID
 * @param query           用户原始输入
 * @param reason          失败归因分类
 * @param actualResponse  Agent 的实际回复片段（截断）
 * @param diagnostics     诊断信息（如 "Expected tool 'queryOrder' but got 'refund'"）
 */
public record FailedCaseDetail(
        String testCaseId,
        String query,
        FailureReason reason,
        String actualResponse,
        String diagnostics
) {

    /**
     * 截断回复文本，防止报告中单个 case 的输出过长。
     */
    private static final int MAX_RESPONSE_LENGTH = 200;

    /**
     * 紧凑构造器：自动截断过长的回复文本。
     */
    public FailedCaseDetail {
        if (actualResponse != null && actualResponse.length() > MAX_RESPONSE_LENGTH) {
            actualResponse = actualResponse.substring(0, MAX_RESPONSE_LENGTH) + "...";
        }
    }

    /**
     * 格式化输出为单行摘要。
     */
    public String toSummary() {
        return String.format("[%s] %s: %s → %s",
                reason.getLabel(), testCaseId, query, diagnostics);
    }
}
