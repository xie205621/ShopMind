package com.shopmind.evaluation.rtmp.persistence;

import java.util.List;

/**
 * RTMP Comparison — 描述性比较 + 统计占位字段（Phase 5-B4）。
 * <p>
 * 固定 condition pairs：{@code A_vs_B} / {@code B_vs_C} / {@code A_vs_C}。本阶段只产生
 * descriptive difference（{@code valueA} / {@code valueB} / {@code difference} /
 * {@code relativeDifference}）与配对结构（{@code pairedN} / {@code pairedUnitIds}）。
 * <p>
 * <b>统计占位字段在 B4 阶段恒为 null</b>（{@code statisticalTest / statistic / pValue / decision}），
 * 由 Phase 5-B1 的 {@code RtmpStatisticalAnalyzer} 负责写回。本 record 在 B4 只承载
 * 描述性比较与配对结构；禁止在 B4 偷渡统计模块。
 *
 * @param schemaVersion       comparison schema 版本（rtmp-b4-comparison-v1）
 * @param sourceExperimentId  来源实验标识
 * @param sourceRawPattern    来源 Raw 文件 pattern
 * @param generatedAt         生成时间（ISO-8601）
 * @param pairs               三个 condition pair 的比较结果
 */
public record RtmpComparison(
        String schemaVersion,
        String sourceExperimentId,
        String sourceRawPattern,
        String generatedAt,
        List<PairComparison> pairs
) {

    /**
     * 一个 condition pair 的比较结果集合。
     */
    public record PairComparison(
            String pairId,
            String conditionA,
            String conditionB,
            List<ComparisonEntry> entries
    ) {
    }

    /**
     * 单个 metric 的比较条目。
     * <p>
     * {@code hypothesis} 为 §19 的 metric → hypothesis 元数据映射（如 L2→H1），
     * 但绝不写 Supported / Rejected / Confirmed 等 inferential conclusion。
     * <p>
     * {@code alpha} / {@code twoSided} 为统计协议元数据（B1 写回后固定为 0.05 / true），
     * descriptive 阶段（B4）为 null。
     */
    public record ComparisonEntry(
            String metric,
            String hypothesis,
            String conditionA,
            String conditionB,
            int pairedN,
            List<String> pairedUnitIds,
            Double valueA,
            Double valueB,
            Double difference,
            Double relativeDifference,
            String statisticalTest,
            Double statistic,
            Double pValue,
            Double adjustedPValue,
            String decision,
            Double alpha,
            Boolean twoSided
    ) {
    }
}
