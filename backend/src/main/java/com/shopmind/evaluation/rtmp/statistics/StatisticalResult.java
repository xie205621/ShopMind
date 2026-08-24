package com.shopmind.evaluation.rtmp.statistics;

/**
 * 统计结果 DTO — Phase 5-B1。
 * <p>
 * 承载 McNemar 或 Wilcoxon 的完整统计结果。{@code statisticalTest / statistic / pValue /
 * decision} 会写回 {@code RtmpComparison.ComparisonEntry}；其余详情字段（discordant / W+ / W- /
 * nonZeroN / zeroDifferenceN / excludedCount）用于报告溯源，避免信息丢失（§8 / §14）。
 * <p>
 * 未用于当前检验的详情字段取默认值 0。
 *
 * @param statisticalTest  canonical test name（"McnemarExact" / "WilcoxonSignedRankAsymptotic"）
 * @param statistic        McNemar：signed discordance {@code b-c}；Wilcoxon：{@code W = min(W+, W-)}
 * @param pValue           two-sided p-value（配对不足时为 null）
 * @param decision         SIGNIFICANT / NOT_SIGNIFICANT / INSUFFICIENT_PAIRS
 * @param pairedN          实际进入统计的 paired unit 数
 * @param excludedCount    因无效/缺失而被排除的 paired unit 数
 * @param discordantAOnly  McNemar 的 b（A=true, B=false）
 * @param discordantBOnly  McNemar 的 c（A=false, B=true）
 * @param discordantN      McNemar 的 b+c
 * @param wPlus            Wilcoxon 的 W+
 * @param wMinus           Wilcoxon 的 W-
 * @param nonZeroN         Wilcoxon 的 non-zero difference 数（effective N）
 * @param zeroDifferenceN  Wilcoxon 的 zero difference 数（丢弃）
 */
public record StatisticalResult(
        String statisticalTest,
        double statistic,
        Double pValue,
        StatisticalDecision decision,
        int pairedN,
        int excludedCount,
        int discordantAOnly,
        int discordantBOnly,
        int discordantN,
        double wPlus,
        double wMinus,
        int nonZeroN,
        int zeroDifferenceN
) {
}
