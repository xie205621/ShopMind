package com.shopmind.evaluation.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A/B 实验对比结果 — 支持两个 ExperimentReport 的逐维度对比。
 * <p>
 * <b>核心价值：</b>将"评测"升级为"对比"，量化每个维度上的改进幅度（Δ%）。
 * 这是论文中最有说服力的数据——不是 Recall=0.83，而是
 * "Compared with v2.0, Recall improved +15.3%"。
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * ExperimentReport baseline = load("experiments/benchmark_v2.0.json");
 * ExperimentReport current  = load("experiments/benchmark_v2.1.json");
 * ExperimentComparison cmp = ExperimentComparison.compare(baseline, current, "v2.0", "v2.1");
 * System.out.println(cmp.toMarkdownTable());
 * }</pre>
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param baselineId           基线实验 ID
 * @param currentId            对比实验 ID
 * @param intentDelta          意图准确率变化（百分点）
 * @param recallDelta          召回率变化（百分点）
 * @param hallucinationDelta   幻觉率变化（百分点，负值为改善）
 * @param toolAccuracyDelta    工具准确率变化（百分点）
 * @param taskSuccessDelta     任务成功率变化（百分点）
 * @param ttftDelta            平均 TTFT 变化（毫秒）
 * @param p95LatencyDelta      P95 延迟变化（毫秒）
 * @param costDelta            成本变化（美元）
 * @param dimensionDetails     各维度详细对比数据
 */
public record ExperimentComparison(
        String baselineId,
        String currentId,
        double intentDelta,
        double recallDelta,
        double hallucinationDelta,
        double toolAccuracyDelta,
        double taskSuccessDelta,
        double ttftDelta,
        double p95LatencyDelta,
        double costDelta,
        Map<String, DimensionDelta> dimensionDetails
) {

    /**
     * 单个维度的对比详情。
     */
    public record DimensionDelta(
            String dimension,
            double baselineValue,
            double currentValue,
            double delta,
            double deltaPercent,
            String unit
    ) {
        public String improvementLabel() {
            if (delta > 0) return String.format("+%.1f%%", deltaPercent);
            if (delta < 0) return String.format("%.1f%%", deltaPercent);
            return "0.0%";
        }

        public String direction() {
            if (delta > 0) return "↑";
            if (delta < 0) return "↓";
            return "→";
        }
    }

    /**
     * 对比两个实验报告，生成逐维度对比数据。
     *
     * @param baseline   基线实验（旧版本）
     * @param current    对比实验（新版本）
     * @param baselineLabel 基线标签（如 "v2.0"）
     * @param currentLabel  对比标签（如 "v2.1"）
     * @return 完整的对比结果
     */
    public static ExperimentComparison compare(
            ExperimentReport baseline, ExperimentReport current,
            String baselineLabel, String currentLabel) {

        MetricSummary bm = baseline.getMetrics();
        MetricSummary cm = current.getMetrics();
        CostSummary bc = baseline.getCost();
        CostSummary cc = current.getCost();

        Map<String, DimensionDelta> details = new LinkedHashMap<>();

        // 意图准确率
        details.put("intent", delta("Intent Accuracy", bm.intentAccuracy(), cm.intentAccuracy(), "rate"));
        // 召回率
        details.put("recall", delta("Avg Recall@K", bm.avgRecallAtK(), cm.avgRecallAtK(), "score"));
        // 幻觉率（越低越好，delta 为负表示改善）
        details.put("hallucination", delta("Hallucination Rate", bm.hallucinationRate(), cm.hallucinationRate(), "rate"));
        // 工具准确率
        details.put("tool", delta("Tool Accuracy", bm.toolAccuracy(), cm.toolAccuracy(), "rate"));
        // 任务成功率
        details.put("task_success", delta("Task Success Rate", bm.taskSuccessRate(), cm.taskSuccessRate(), "rate"));
        // TTFT
        details.put("ttft", delta("Avg TTFT", bm.avgTtftMs(), cm.avgTtftMs(), "ms"));
        // P95
        details.put("p95", delta("P95 Latency", bm.p95LatencyMs(), cm.p95LatencyMs(), "ms"));
        // 成本
        details.put("cost", delta("Cost", bc.estimatedCostUsd(), cc.estimatedCostUsd(), "$"));

        return new ExperimentComparison(
                baselineLabel,
                currentLabel,
                cm.intentAccuracy() - bm.intentAccuracy(),
                cm.avgRecallAtK() - bm.avgRecallAtK(),
                cm.hallucinationRate() - bm.hallucinationRate(),
                cm.toolAccuracy() - bm.toolAccuracy(),
                cm.taskSuccessRate() - bm.taskSuccessRate(),
                cm.avgTtftMs() - bm.avgTtftMs(),
                cm.p95LatencyMs() - bm.p95LatencyMs(),
                cc.estimatedCostUsd() - bc.estimatedCostUsd(),
                details
        );
    }

    private static DimensionDelta delta(String dim, double baseline, double current, String unit) {
        double d = current - baseline;
        double dp = baseline != 0 ? (d / Math.abs(baseline)) * 100.0 : 0.0;
        return new DimensionDelta(dim, baseline, current, d, dp, unit);
    }

    /**
     * 生成 Markdown 对比表格（适合放入 README Research）。
     */
    public String toMarkdownTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("## A/B Experiment Comparison\n\n");
        sb.append(String.format("**%s** (baseline) vs **%s** (current)\n\n", baselineId, currentId));
        sb.append("| Dimension | ").append(baselineId).append(" | ").append(currentId).append(" | Δ | Δ% | Direction |\n");
        sb.append("|-----------|------|------|---|---|-----------|---|\n");

        for (DimensionDelta d : dimensionDetails.values()) {
            String fmtBaseline = formatValue(d.baselineValue(), d.unit());
            String fmtCurrent = formatValue(d.currentValue(), d.unit());
            String fmtDelta = formatValue(d.delta(), d.unit());
            sb.append(String.format("| %s | %s | %s | %s | %s | %s |\n",
                    d.dimension(),
                    fmtBaseline, fmtCurrent,
                    fmtDelta, d.improvementLabel(), d.direction()));
        }
        return sb.toString();
    }

    private String formatValue(double v, String unit) {
        if ("ms".equals(unit)) return String.format("%.0fms", v);
        if ("$".equals(unit)) return String.format("$%.4f", v);
        if ("rate".equals(unit)) return String.format("%.1f%%", v * 100);
        return String.format("%.3f", v);
    }

    /**
     * 生成一句话总结（适合放入论文 Abstract）。
     */
    public String oneLineSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s→%s: ", baselineId, currentId));
        for (DimensionDelta d : dimensionDetails.values()) {
            if (Math.abs(d.deltaPercent()) > 1.0) {
                sb.append(String.format("%s %s, ", d.dimension(), d.improvementLabel()));
            }
        }
        // 去掉末尾逗号
        if (sb.charAt(sb.length() - 2) == ',') {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }
}
