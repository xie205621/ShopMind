package com.shopmind.evaluation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 实验报告 — 6_Evaluation_Engine.md §5.3 规范。
 * <p>
 * 一次完整 Benchmark 评测的最终产物。包含从所有 TestCase 聚合而来的
 * 指标汇总、失败分布、成本汇总和失败用例采样。
 * <p>
 * <b>构建方式：</b>通过 {@link com.shopmind.evaluation.pipeline.BenchmarkRunnerImpl}
 * 使用 {@code Flux.reduce()} 逐用例累加构建，保证并发安全。
 * 最终调用 {@link #finalize(BenchmarkConfig)} 完成百分比计算。
 * <p>
 * <b>线程安全：</b>本类仅在单次 reduce 操作中使用，实例不跨线程共享。
 * 所有 mutation 方法在 reduce accumulator 中串行调用，天然线程安全。
 */
public class ExperimentReport {

    // ============================================================
    //  Metadata
    // ============================================================

    private String experimentId;
    private String workflowVersion;
    private BenchmarkConfig metadata;
    private Instant generatedAt;

    // ============================================================
    //  Accumulated counters
    // ============================================================

    private int totalCases;
    private int passedCases;
    private int intentPassed;
    private int toolPassed;
    private int knowledgePassed;
    private int hallucinationCount;
    private int timeoutCount;
    private int workflowBrokenCount;
    private int safetyRefusalCount;

    // ============================================================
    //  Latency accumulators (for average / P95)
    // ============================================================

    private long totalLatencySum;
    private long ttftSum;
    private final List<Long> allLatencies;  // for P95 calculation

    // ============================================================
    //  Cost accumulators
    // ============================================================

    private long totalPromptTokens;
    private long totalCompletionTokens;

    // ============================================================
    //  Failure distribution
    // ============================================================

    private final Map<FailureReason, Integer> failureCounts;

    // ============================================================
    //  Failed case samples (up to MAX_SAMPLES)
    // ============================================================

    private static final int MAX_FAILED_SAMPLES = 50;
    private final List<FailedCaseDetail> failedDetails;

    // ============================================================
    //  Metrics (computed in finalize)
    // ============================================================

    private MetricSummary metrics;
    private Map<FailureReason, Double> failureDistribution;
    private CostSummary cost;

    // ============================================================
    //  Constructor
    // ============================================================

    public ExperimentReport() {
        this.failureCounts = new EnumMap<>(FailureReason.class);
        this.failedDetails = new ArrayList<>();
        this.allLatencies = new ArrayList<>();
        this.generatedAt = Instant.now();
    }

    // ============================================================
    //  Accumulation methods (called by BenchmarkRunnerImpl.reduce)
    // ============================================================

    /**
     * 累加一个 TestCase 的评估结果。
     */
    public ExperimentReport accumulate(TestCaseResult result) {
        totalCases++;

        // 成功率
        if (result.isAllPassed()) {
            passedCases++;
        }

        // 各维度计数
        if (result.intentMatch()) intentPassed++;
        if (result.toolMatch()) toolPassed++;
        if (result.knowledgeRecalled()) knowledgePassed++;

        // 延迟
        totalLatencySum += result.totalLatencyMs();
        ttftSum += result.ttftMs();
        allLatencies.add(result.totalLatencyMs());

        // Token
        totalPromptTokens += result.promptTokens();
        totalCompletionTokens += result.completionTokens();

        // 失败原因
        if (result.failureReason() != null) {
            failureCounts.merge(result.failureReason(), 1, Integer::sum);

            if (result.failureReason() == FailureReason.HALLUCINATION) {
                hallucinationCount++;
            }
            if (result.failureReason() == FailureReason.TIMEOUT) {
                timeoutCount++;
            }
            if (result.failureReason() == FailureReason.SAFETY_BLOCKED) {
                workflowBrokenCount++;
            }
            if (result.failureReason() == FailureReason.KNOWLEDGE_NOT_FOUND) {
                safetyRefusalCount++;
            }

            // 采样失败用例详情（最多 MAX_SAMPLES 条）
            if (failedDetails.size() < MAX_FAILED_SAMPLES) {
                failedDetails.add(new FailedCaseDetail(
                        result.testCaseId(),
                        result.query() != null ? result.query() : "N/A",
                        result.failureReason(),
                        result.answerSnippet(),
                        result.failureLabel()
                ));
            }
        }

        return this;
    }

    /**
     * 累加一个失败的 TestCase 及其原始 query。
     * 当 BenchmarkRunner 已知 query 时使用此方法。
     */
    public ExperimentReport accumulateWithQuery(TestCaseResult result, String query) {
        accumulate(result);
        // 替换最后添加的 failedDetail 的 query
        if (!failedDetails.isEmpty() && result.failureReason() != null) {
            int lastIdx = failedDetails.size() - 1;
            FailedCaseDetail old = failedDetails.get(lastIdx);
            failedDetails.set(lastIdx, new FailedCaseDetail(
                    old.testCaseId(), query, old.reason(), old.actualResponse(), old.diagnostics()
            ));
        }
        return this;
    }

    // ============================================================
    //  Finalization (called once after reduce)
    // ============================================================

    /**
     * 完成报告的计算，填充所有派生字段。
     * 调用此方法后，报告变为只读状态。
     *
     * @param config 实验配置（注入报告 metadata）
     * @return this，便于链式调用
     */
    public ExperimentReport finalize(BenchmarkConfig config) {
        this.experimentId = config.experimentId();
        this.workflowVersion = config.workflowVersion();
        this.metadata = config;
        this.generatedAt = Instant.now();

        double n = Math.max(totalCases, 1);

        // 指标汇总
        this.metrics = new MetricSummary(
                intentPassed / n,
                totalCases > 0 ? (double) knowledgePassed / totalCases : 0.0,
                hallucinationCount / n,
                toolPassed / n,
                passedCases / n,
                totalCases > 0 ? (double) ttftSum / totalCases : 0.0,
                computeP95(),
                (double) (totalCases - workflowBrokenCount) / n
        );

        // 失败分布百分比
        this.failureDistribution = new EnumMap<>(FailureReason.class);
        for (Map.Entry<FailureReason, Integer> entry : failureCounts.entrySet()) {
            failureDistribution.put(entry.getKey(), entry.getValue() / n);
        }

        // 成本汇总
        this.cost = new CostSummary(
                totalPromptTokens,
                totalCompletionTokens,
                estimateCost(totalPromptTokens, totalCompletionTokens, config),
                config.llmProvider()
        );

        // Freeze lists
        Collections.sort(allLatencies);
        return this;
    }

    // ============================================================
    //  Private helpers
    // ============================================================

    private long computeP95() {
        if (allLatencies.isEmpty()) return 0;
        Collections.sort(allLatencies);
        int idx = (int) Math.ceil(0.95 * allLatencies.size()) - 1;
        return allLatencies.get(Math.max(idx, 0));
    }

    /**
     * 基于 Token 和厂商估算成本。
     * 当前使用简化的统一价格模型，实际应查表。
     */
    private double estimateCost(long promptTokens, long completionTokens, BenchmarkConfig config) {
        // 简化模型: $0.002 / 1K tokens (约 Qwen-Max 定价)
        double promptCost = promptTokens / 1000.0 * 0.002;
        double completionCost = completionTokens / 1000.0 * 0.002;
        return Math.round((promptCost + completionCost) * 10000.0) / 10000.0;
    }

    // ============================================================
    //  Factory: 从已有数据重建（用于加载历史 JSON 做 A/B 对比）
    // ============================================================

    /**
     * 从已有的 MetricSummary 和 CostSummary 重建 ExperimentReport。
     * 用于加载历史实验 JSON 进行 A/B 对比，不经过 accumulate/finalize 流程。
     *
     * @param experimentId     实验 ID
     * @param workflowVersion  工作流版本号
     * @param metrics          指标汇总
     * @param cost             成本汇总
     * @param totalCases       总用例数
     * @param passedCases      通过用例数
     * @param failureDistribution 失败分布
     */
    public static ExperimentReport fromComparisonData(
            String experimentId, String workflowVersion,
            MetricSummary metrics, CostSummary cost,
            int totalCases, int passedCases,
            Map<FailureReason, Double> failureDistribution) {
        ExperimentReport report = new ExperimentReport();
        report.experimentId = experimentId;
        report.workflowVersion = workflowVersion;
        report.metrics = metrics;
        report.cost = cost;
        report.totalCases = totalCases;
        report.passedCases = passedCases;
        report.failureDistribution = new EnumMap<>(failureDistribution);
        return report;
    }

    // ============================================================
    //  Getters
    // ============================================================

    public String getExperimentId() { return experimentId; }
    public String getWorkflowVersion() { return workflowVersion; }
    public BenchmarkConfig getMetadata() { return metadata; }
    public Instant getGeneratedAt() { return generatedAt; }
    public MetricSummary getMetrics() { return metrics; }
    public Map<FailureReason, Double> getFailureDistribution() {
        return Collections.unmodifiableMap(failureDistribution);
    }
    public CostSummary getCost() { return cost; }
    public List<FailedCaseDetail> getFailedDetails() { return Collections.unmodifiableList(failedDetails); }
    public int getTotalCases() { return totalCases; }
    public int getPassedCases() { return passedCases; }

    /** 获取安全拒答率（Guardrails 生效的用例占比，0.0~1.0） */
    public double getSafetyRefusalRate() {
        return totalCases > 0 ? (double) safetyRefusalCount / totalCases : 0.0;
    }

    public int getSafetyRefusalCount() { return safetyRefusalCount; }

    // ============================================================
    //  toString
    // ============================================================

    @Override
    public String toString() {
        return "ExperimentReport{" +
                "experimentId='" + experimentId + '\'' +
                ", totalCases=" + totalCases +
                ", passedCases=" + passedCases +
                ", passRate=" + String.format("%.1f%%", 100.0 * passedCases / Math.max(totalCases, 1)) +
                ", cost=" + cost +
                '}';
    }
}
