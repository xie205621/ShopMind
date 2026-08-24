package com.shopmind.evaluation.rtmp.statistics;

/**
 * 统计判定结果 — Phase 5-B1。
 * <p>
 * 只允许三种值（§18 冻结）：
 * <ul>
 *   <li>{@link #SIGNIFICANT} — p &lt; 0.05</li>
 *   <li>{@link #NOT_SIGNIFICANT} — p &gt;= 0.05</li>
 *   <li>{@link #INSUFFICIENT_PAIRS} — p == null（配对不足，无法检验）</li>
 * </ul>
 * 不得写入 H1_SUPPORTED / H2_SUPPORTED 等研究结论。
 */
public enum StatisticalDecision {
    SIGNIFICANT,
    NOT_SIGNIFICANT,
    INSUFFICIENT_PAIRS;

    public static final double ALPHA = 0.05;

    /**
     * 统一判定：p &lt; 0.05 → SIGNIFICANT；p &gt;= 0.05 → NOT_SIGNIFICANT；p == null → INSUFFICIENT_PAIRS。
     */
    public static StatisticalDecision fromPValue(Double pValue) {
        if (pValue == null) {
            return INSUFFICIENT_PAIRS;
        }
        return pValue < ALPHA ? SIGNIFICANT : NOT_SIGNIFICANT;
    }
}
