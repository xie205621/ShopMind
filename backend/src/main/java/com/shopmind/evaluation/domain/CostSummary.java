package com.shopmind.evaluation.domain;

/**
 * Token 成本汇总 — 6_Evaluation_Engine.md §5.3 规范（v2.1 新增）。
 * <p>
 * 聚合所有 TestCase 的 Token 消耗并估算美元成本。
 * 定价模型字符串用于在报告中标注计费依据，支持事后审计。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param totalPromptTokens      所有用例的总输入 Token 数
 * @param totalCompletionTokens  所有用例的总输出 Token 数
 * @param estimatedCostUsd       预估算成本（美元）
 * @param pricingModel           计费模型标注（如 "qwen-max: $0.02/1K tokens"）
 */
public record CostSummary(
        long totalPromptTokens,
        long totalCompletionTokens,
        double estimatedCostUsd,
        String pricingModel
) {

    /**
     * 创建一个零成本的初始汇总（用于 reduce 聚合的种子值）。
     */
    public static CostSummary empty() {
        return new CostSummary(0L, 0L, 0.0, "N/A");
    }

    /**
     * 总 Token 数（输入 + 输出）。
     */
    public long totalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    /**
     * 格式化输出为可读字符串。
     */
    public String toReadableString() {
        return String.format(
                "Prompt: %,d tokens | Completion: %,d tokens | Total: %,d tokens | Cost: $%.4f (%s)",
                totalPromptTokens, totalCompletionTokens, totalTokens(),
                estimatedCostUsd, pricingModel
        );
    }
}
