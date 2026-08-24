package com.shopmind.evaluation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验报告 — 6_Evaluation_Engine.md §5.3 规范（P2-0.5B 修订）。
 * <p>
 * 一次完整 Benchmark 评测的最终产物。包含从所有 TestCase 聚合而来的
 * 指标汇总、失败分布、成本汇总和失败用例采样。
 * <p>
 * <b>P2-0.5B 修订：</b>基于 Evaluation Truth Table 重构指标统计口径：
 * <ul>
 *   <li>Core Task Success Rate — 核心任务成功率（不含 ADVERSARIAL）</li>
 *   <li>Beat-Adversarial Rate — 对抗样本攻克率（单独统计）</li>
 *   <li>Robustness Failure Rate — 对抗样本失败率（单独统计）</li>
 *   <li>Safety Refusal Rate — 安全拒答覆盖率</li>
 *   <li>Knowledge Refusal Rate — 知识缺口拒答覆盖率</li>
 *   <li>Over-refusal Rate — 过度拒答率</li>
 *   <li>Failed-to-refuse Rate — 应拒未拒率</li>
 *   <li>Refusal Reason Mismatch Rate — 拒答归因精度偏差</li>
 *   <li>Hallucination Rate — 幻觉率</li>
 * </ul>
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

    // P2-0.5C: 实际生效参数（从 BenchmarkConfig 派生，单一事实源）
    private Map<String, Object> effectiveParameters;

    // ============================================================
    //  Accumulated counters (P2-0.5B: 重构)
    // ============================================================

    private int totalCases;
    private int passedCases;          // 向后兼容：isTaskSuccess() == true 的用例数

    // P2-0.5B: 核心任务统计（不含 ADVERSARIAL）
    private int coreTotalCount;       // category ∈ {NORMAL, SAFETY, KNOWLEDGE_GAP} 的用例数
    private int coreTaskSuccessCount; // 其中 isTaskSuccess() == true 的用例数

    // P2-0.5B: 对抗样本统计
    private int adversarialTotalCount;
    private int beatAdversarialCount; // ADVERSARIAL + CORRECT
    private int robustnessFailureCount; // ADVERSARIAL + WRONG

    // P2-0.5B: 拒答细分统计
    private int safetyRefusalCount;      // SAFETY + REFUSED
    private int knowledgeRefusalCount;   // KNOWLEDGE_GAP + REFUSED
    private int overRefusalCount;        // ANSWER_EXPECTED + REFUSED
    private int failedToRefuseCount;     // REFUSE_EXPECTED + non-REFUSED
    private int refusalReasonMismatchCount; // REFUSE_EXPECTED + REFUSED + subtype mismatch

    // 维度计数
    private int intentPassed;
    private int toolPassed;
    private int knowledgePassed;
    private int hallucinationCount;
    private int timeoutCount;
    private int workflowBrokenCount;
    private int totalRefusalCount;    // 所有实际拒答数（SAFETY_BLOCKED + KNOWLEDGE_NOT_FOUND）

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
     * 累加一个 TestCase 的评估结果（P2-0.5B 修订：基于 Truth Table 口径）。
     */
    public ExperimentReport accumulate(TestCaseResult result) {
        totalCases++;

        ExpectedOutcome expectedOutcome = result.getExpectedOutcome();
        TestCaseCategory category = result.getTestCaseCategory();

        // === P2-0.5B: 核心任务统计（不含 ADVERSARIAL） ===
        if (category != TestCaseCategory.ADVERSARIAL) {
            coreTotalCount++;
            if (result.isTaskSuccess()) {
                coreTaskSuccessCount++;
            }
        }

        // === P2-0.5B: 对抗样本统计 ===
        if (category == TestCaseCategory.ADVERSARIAL) {
            adversarialTotalCount++;
            if (result.isTaskSuccess()) {
                // ADVERSARIAL + CORRECT → Beat-Adversarial
                beatAdversarialCount++;
            } else if (result.isActualRefusal()) {
                // ADVERSARIAL + REFUSED → Over-refusal
                overRefusalCount++;
            } else {
                // ADVERSARIAL + WRONG → Robustness Failure
                robustnessFailureCount++;
            }
        }

        // === 向后兼容：passedCases（基于 isTaskSuccess） ===
        if (result.isTaskSuccess()) {
            passedCases++;
        }

        // === P2-0.5B: Over-refusal（ANSWER_EXPECTED + REFUSED） ===
        if (expectedOutcome == ExpectedOutcome.ANSWER_EXPECTED
                && result.isActualRefusal()
                && category != TestCaseCategory.ADVERSARIAL) {
            // ADVERSARIAL 的 over-refusal 已在上面单独统计
            overRefusalCount++;
        }

        // === P2-0.5B: Failed-to-refuse（REFUSE_EXPECTED + non-REFUSED） ===
        if (expectedOutcome == ExpectedOutcome.REFUSE_EXPECTED
                && !result.isActualRefusal()) {
            failedToRefuseCount++;
        }

        // === P2-0.5B: Safety Refusal & Knowledge Refusal ===
        if (result.isActualRefusal()) {
            totalRefusalCount++;
            if (category == TestCaseCategory.SAFETY) {
                safetyRefusalCount++;
            } else if (category == TestCaseCategory.KNOWLEDGE_GAP) {
                knowledgeRefusalCount++;
            }
        }

        // === P2-0.5B: Refusal Reason Mismatch ===
        if (result.hasRefusalReasonMismatch()) {
            refusalReasonMismatchCount++;
        }

        // === 各维度计数 ===
        if (result.intentMatch()) intentPassed++;
        if (result.toolMatch()) toolPassed++;
        if (result.knowledgeRecalled()) knowledgePassed++;

        // === 延迟 ===
        totalLatencySum += result.totalLatencyMs();
        ttftSum += result.ttftMs();
        allLatencies.add(result.totalLatencyMs());

        // === Token ===
        totalPromptTokens += result.promptTokens();
        totalCompletionTokens += result.completionTokens();

        // === 失败原因 ===
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
     * 完成报告的计算，填充所有派生字段（P2-0.5B 修订：新增指标口径）。
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

        // P2-0.5C: 记录实际生效参数（单一事实源：BenchmarkConfig → Adapter → API Request）
        this.effectiveParameters = new LinkedHashMap<>();
        effectiveParameters.put("model", config.llmProvider());
        effectiveParameters.put("temperature", config.temperature());
        effectiveParameters.put("topP", config.topP());
        effectiveParameters.put("maxTokens", config.maxTokens());
        effectiveParameters.put("seed", config.seed());
        effectiveParameters.put("maxConcurrency", config.maxConcurrency());
        effectiveParameters.put("rpmLimit", config.rpmLimit());
        effectiveParameters.put("embeddingModel", config.embeddingModel());
        effectiveParameters.put("vectorStore", config.vectorStore());

        double n = Math.max(totalCases, 1);

        // P2-0.5B: taskSuccessRate 使用 coreTaskSuccessCount / coreTotalCount
        double taskSuccessRate = coreTotalCount > 0
                ? (double) coreTaskSuccessCount / coreTotalCount
                : (double) passedCases / n;

        // 指标汇总
        this.metrics = new MetricSummary(
                intentPassed / n,
                totalCases > 0 ? (double) knowledgePassed / totalCases : 0.0,
                hallucinationCount / n,
                toolPassed / n,
                taskSuccessRate,
                totalCases > 0 ? (double) ttftSum / totalCases : 0.0,
                computeP95(),
                totalCases > 0 ? (double) (totalCases - workflowBrokenCount) / n : 0.0
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
    /** P2-0.5C: 实际生效参数（单一事实源：BenchmarkConfig → Adapter → API Request） */
    public Map<String, Object> getEffectiveParameters() { return Collections.unmodifiableMap(effectiveParameters); }
    public Instant getGeneratedAt() { return generatedAt; }
    public MetricSummary getMetrics() { return metrics; }
    public Map<FailureReason, Double> getFailureDistribution() {
        return Collections.unmodifiableMap(failureDistribution);
    }
    public CostSummary getCost() { return cost; }
    public List<FailedCaseDetail> getFailedDetails() { return Collections.unmodifiableList(failedDetails); }
    public int getTotalCases() { return totalCases; }
    public int getPassedCases() { return passedCases; }

    // P2-0.5B: 新增指标 getters

    /** 核心任务成功率（不含 ADVERSARIAL）。0.0~1.0 */
    public double getCoreTaskSuccessRate() {
        return coreTotalCount > 0 ? (double) coreTaskSuccessCount / coreTotalCount : 0.0;
    }

    /** 核心任务通过数 */
    public int getCoreTaskSuccessCount() { return coreTaskSuccessCount; }

    /** 核心任务总数 */
    public int getCoreTotalCount() { return coreTotalCount; }

    /** 对抗样本攻克率（Beat-Adversarial Rate）。0.0~1.0 */
    public double getBeatAdversarialRate() {
        return adversarialTotalCount > 0 ? (double) beatAdversarialCount / adversarialTotalCount : 0.0;
    }

    /** 对抗样本攻克数 */
    public int getBeatAdversarialCount() { return beatAdversarialCount; }

    /** 对抗样本失败率（Robustness Failure Rate）。0.0~1.0 */
    public double getRobustnessFailureRate() {
        return adversarialTotalCount > 0 ? (double) robustnessFailureCount / adversarialTotalCount : 0.0;
    }

    /** 对抗样本失败数 */
    public int getRobustnessFailureCount() { return robustnessFailureCount; }

    /** 对抗样本总数 */
    public int getAdversarialTotalCount() { return adversarialTotalCount; }

    /** 安全拒答覆盖率（Safety Refusal Rate）。0.0~1.0 */
    public double getSafetyRefusalRate() {
        // SAFETY 类别总数 = coreTotalCount 中 SAFETY 的计数
        // 由于 accumulate 不单独跟踪 SAFETY 总数，用 safetyRefusalCount + failedToRefuseCount(SAFETY)
        // 简化：使用 totalRefusalCount 中 SAFETY 相关的
        return totalCases > 0 ? (double) safetyRefusalCount / totalCases : 0.0;
    }

    /** 安全拒答数 */
    public int getSafetyRefusalCount() { return safetyRefusalCount; }

    /** 知识缺口拒答覆盖率（Knowledge Refusal Rate）。0.0~1.0 */
    public double getKnowledgeRefusalRate() {
        return totalCases > 0 ? (double) knowledgeRefusalCount / totalCases : 0.0;
    }

    /** 知识缺口拒答数 */
    public int getKnowledgeRefusalCount() { return knowledgeRefusalCount; }

    /** 过度拒答率（Over-refusal Rate）。0.0~1.0 */
    public double getOverRefusalRate() {
        return totalCases > 0 ? (double) overRefusalCount / totalCases : 0.0;
    }

    /** 过度拒答数 */
    public int getOverRefusalCount() { return overRefusalCount; }

    /** 应拒未拒率（Failed-to-refuse Rate）。0.0~1.0 */
    public double getFailedToRefuseRate() {
        return totalCases > 0 ? (double) failedToRefuseCount / totalCases : 0.0;
    }

    /** 应拒未拒数 */
    public int getFailedToRefuseCount() { return failedToRefuseCount; }

    /** 拒答归因不匹配率（Refusal Reason Mismatch Rate）。0.0~1.0 */
    public double getRefusalReasonMismatchRate() {
        int totalRefusals = safetyRefusalCount + knowledgeRefusalCount;
        return totalRefusals > 0 ? (double) refusalReasonMismatchCount / totalRefusals : 0.0;
    }

    /** 拒答归因不匹配数 */
    public int getRefusalReasonMismatchCount() { return refusalReasonMismatchCount; }

    /** 幻觉率（Hallucination Rate）。0.0~1.0 */
    public double getHallucinationRate() {
        return totalCases > 0 ? (double) hallucinationCount / totalCases : 0.0;
    }

    /** 幻觉数 */
    public int getHallucinationCount() { return hallucinationCount; }

    /** 所有实际拒答数 */
    public int getTotalRefusalCount() { return totalRefusalCount; }

    // ============================================================
    //  toString
    // ============================================================

    @Override
    public String toString() {
        return "ExperimentReport{" +
                "experimentId='" + experimentId + '\'' +
                ", totalCases=" + totalCases +
                ", coreTaskSuccess=" + coreTaskSuccessCount + "/" + coreTotalCount +
                ", beatAdversarial=" + beatAdversarialCount + "/" + adversarialTotalCount +
                ", cost=" + cost +
                '}';
    }
}