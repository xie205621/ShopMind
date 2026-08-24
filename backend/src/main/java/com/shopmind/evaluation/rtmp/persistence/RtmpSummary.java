package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.workflow.domain.ControlOverhead;

import java.util.List;

/**
 * RTMP Summary — 描述性聚合层（Phase 5-B4）。
 * <p>
 * 从 Raw 记录重新计算，按 condition / subgroup 聚合描述性指标。Summary <b>不是</b>
 * 唯一事实来源，任何 B1 统计都能从 Raw 重建本层结果。
 * <p>
 * {@link RateMetric#rate()} 为 {@code positive / eligible}；当 {@code eligible == 0}
 * 时 rate 为 {@code null}（不得用 0 伪造真实观测）。
 *
 * @param schemaVersion       summary schema 版本（rtmp-b4-summary-v1）
 * @param sourceExperimentId  来源实验标识
 * @param sourceRawPattern    来源 Raw 文件 pattern
 * @param generatedAt         生成时间（ISO-8601）
 * @param conditions          每个 condition 的聚合结果
 */
public record RtmpSummary(
        String schemaVersion,
        String sourceExperimentId,
        String sourceRawPattern,
        String generatedAt,
        List<ConditionSummary> conditions
) {

    /**
     * 描述性 rate 指标：positive / eligible。
     */
    public record RateMetric(int positive, int eligible, Double rate) {
        public static RateMetric of(int positive, int eligible) {
            Double rate = eligible == 0 ? null : (double) positive / eligible;
            return new RateMetric(positive, eligible, rate);
        }
    }

    /**
     * 单个 condition 的聚合结果。
     */
    public record ConditionSummary(
            String condition,
            int totalRuns,
            int validCount,
            int invalidCount,
            int retryableCount,
            RateMetric l1,
            RateMetric l2,
            RateMetric l3,
            RateMetric safetyIntervention,
            int coreTaskEligibleN,
            int coreTaskPositive,
            Double coreTaskSuccessRate,
            int coreTaskProtocolN,
            RateMetric overRefusal,
            ControlOverhead verifierOverhead,
            ControlOverhead routerOverhead,
            RuntimeTotals runtimeTotals,
            List<SubgroupSummary> subgroups
    ) {
    }

    /**
     * 单个 subgroup 的聚合结果（exploratory 与 primary 分别标注）。
     */
    public record SubgroupSummary(
            String name,
            boolean primary,
            int totalRuns,
            int validCount,
            RateMetric l2,
            RateMetric coreTaskSuccess,
            RateMetric overRefusal
    ) {
    }

    /**
     * Runtime 指标合计（对 valid runs 求和）。
     * <p>
     * {@code toolLatencyMs} = 各 run 全部 {@code ToolCallEvent.latencyMs} 之和；
     * {@code totalLatencyMs} = 各 run 端到端总延迟之和；{@code ttftMs} = TTFT 之和。
     */
    public record RuntimeTotals(
            long totalLatencyMs,
            long ttftMs,
            long toolLatencyMs,
            int promptTokens,
            int completionTokens
    ) {
    }
}
