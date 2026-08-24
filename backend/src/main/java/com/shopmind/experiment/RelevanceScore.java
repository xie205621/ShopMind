package com.shopmind.experiment;

/**
 * Relevance 特征映射结果 — Phase 4 (P4-2)。
 * <p>
 * 三个相互独立的 relevance 特征，最终 relevance 取三者最大值：
 * <pre>
 * RelevanceScore(tool, context) = max(intentScore, lexicalScore, descriptionCompatibilityScore)
 * </pre>
 * 范围 0.0 ~ 1.0。
 *
 * @param intentScore                  来自 runtime {@code IntentAnalyzer.IntentResult}（强兼容 1.0 / 不兼容 0.0）
 * @param lexicalScore                 来自工具语义词典（强 1.0 / 弱 0.6 / 无 0.0）
 * @param descriptionCompatibilityScore 来自工具 description/parameters 的确定性 token 兼容（0.3 / 0.0）
 */
public record RelevanceScore(
        double intentScore,
        double lexicalScore,
        double descriptionCompatibilityScore
) {

    /** 综合 relevance 值：三者最大值。 */
    public double value() {
        return Math.max(intentScore, Math.max(lexicalScore, descriptionCompatibilityScore));
    }
}
