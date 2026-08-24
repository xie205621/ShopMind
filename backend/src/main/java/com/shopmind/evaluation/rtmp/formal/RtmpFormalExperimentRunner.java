package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpCaseEvaluation;
import com.shopmind.evaluation.rtmp.RtmpCaseEvaluator;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpComparison;
import com.shopmind.evaluation.rtmp.persistence.RtmpComparisonBuilder;
import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentPersistence;
import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentValidator;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.evaluation.rtmp.persistence.RtmpSummary;
import com.shopmind.evaluation.rtmp.persistence.RtmpSummaryBuilder;
import com.shopmind.evaluation.rtmp.statistics.RtmpStatisticalAnalyzer;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.memory.store.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical formal experiment runner — Phase 5-E1（B1）。
 * <p>
 * 独立于通用 {@link BenchmarkRunner#run} 的正式实验编排类，驱动
 * 42 × 3 × 3 = 378 canonical experimental units，并严格遵循：
 * <pre>
 *   Raw → Summary
 *   Raw → Comparison
 *   Raw → Statistics
 * </pre>
 * Raw 始终是唯一事实源；Summary / Comparison / Statistics 均从 Raw 重建。
 * <p>
 * 执行流程：加载 dataset → 构造 plan → preflight（含 output collision 与 ChatMemoryStore
 * gate）→ 按 frozen condition order 执行 A/B/C → run-level retry（max=1）→ evaluator →
 * Raw record → validator → raw / summary / comparison / statistics 落盘。
 */
public final class RtmpFormalExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(RtmpFormalExperimentRunner.class);

    private final BenchmarkRunner benchmarkRunner;
    private final RtmpCaseEvaluator evaluator = new RtmpCaseEvaluator();
    private final ChatMemoryStore memoryStore;

    public RtmpFormalExperimentRunner(BenchmarkRunner benchmarkRunner, ChatMemoryStore memoryStore) {
        this.benchmarkRunner = benchmarkRunner;
        this.memoryStore = memoryStore;
    }

    /**
     * 一次 run 执行的 outcome 来源（生产实现为 {@code runRtmpCaseOutcome(...).block()}；
     * 测试注入 synthetic outcome）。
     */
    @FunctionalInterface
    public interface OutcomeSource {
        RtmpRunOutcome execute(RtmpTestCase testCase, BenchmarkConfig config,
                               ExperimentCondition condition, int repetition);
    }

    /**
     * run-level retry 的最终结果：最终 outcome + 实际 attempt 次数（1 或 2）。
     */
    public record RetryOutcome(RtmpRunOutcome outcome, int attempts) {
    }

    /** preflight 结果（不执行 Agent / LLM）。 */
    public record PreflightResult(boolean valid, List<String> errors,
                                  RtmpFormalExperimentPlan.Plan plan, BenchmarkConfig config) {
    }

    /** 一次正式实验的落盘结果。 */
    public record RtmpFormalExperimentResult(String experimentId, int recordsWritten,
                                             boolean validated, Path rawFile) {
    }

    /**
     * Preflight（不调用 Real LLM）：构造并校验 plan、执行 output collision preflight、
     * 校验 ChatMemoryStore 可用性、构造正式 config。任一失败即返回 {@code valid=false}。
     */
    public static PreflightResult preflight(String experimentId, Path outputDir,
                                            RtmpEvaluationDataset dataset,
                                            ChatMemoryStore memoryStore) {
        List<String> errors = new ArrayList<>();
        RtmpFormalExperimentPlan.Plan plan = RtmpFormalExperimentPlan.build(experimentId, dataset);
        RtmpFormalExperimentPlan.ValidationResult validation = RtmpFormalExperimentPlan.validate(plan);
        errors.addAll(validation.errors());
        try {
            RtmpExperimentPersistence.assertOutputsDoNotExist(experimentId, outputDir);
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
        }
        if (memoryStore == null) {
            errors.add("ChatMemoryStore bean not available; formal experiment requires memory "
                    + "cleanup for run-level retry (memoryId == runId). Aborting.");
        }
        BenchmarkConfig config = RtmpFormalExperimentConfig.build(experimentId);
        return new PreflightResult(errors.isEmpty(), List.copyOf(errors), plan, config);
    }

    /**
     * 执行完整正式实验（blocking orchestration；供 opt-in entry point 调用）。
     */
    public RtmpFormalExperimentResult run(String experimentId, Path outputDir) {
        RtmpEvaluationDataset dataset = RtmpDatasetLoader.load();
        PreflightResult preflight = preflight(experimentId, outputDir, dataset, memoryStore);
        if (!preflight.valid()) {
            throw new IllegalStateException(
                    "Formal experiment preflight failed: " + preflight.errors());
        }
        BenchmarkConfig config = preflight.config();
        RtmpFormalExperimentPlan.Plan plan = preflight.plan();
        logEffectiveConfig(config, plan);

        List<RtmpRawRecord> records = executePlan(plan, dataset, config, defaultSource());

        RtmpExperimentValidator.Result validation = RtmpExperimentValidator.validate(records);
        if (!validation.valid()) {
            throw new IllegalStateException("Raw records failed validation: " + validation.errors());
        }

        Path rawFile = RtmpExperimentPersistence.writeRaw(records, outputDir);
        String sourceRawPattern = rawFile.getFileName().toString();
        String generatedAt = Instant.now().toString();

        RtmpSummary summary = RtmpSummaryBuilder.build(
                records, experimentId, sourceRawPattern, generatedAt);
        RtmpExperimentPersistence.writeSummary(summary, outputDir);

        RtmpComparison comparison = RtmpComparisonBuilder.build(
                records, experimentId, sourceRawPattern, generatedAt);
        RtmpComparison analyzed = RtmpStatisticalAnalyzer.analyze(records, comparison);
        RtmpExperimentPersistence.writeComparison(analyzed, outputDir);

        return new RtmpFormalExperimentResult(experimentId, records.size(), true, rawFile);
    }

    /**
     * 生产默认 outcome source：通过 {@code benchmarkRunner.runRtmpCaseOutcome(...).block()}
     * 真实驱动单次 run（供 {@link #run} 与测试复用同一路径）。
     */
    OutcomeSource defaultSource() {
        return (tc, cfg, c, rep) -> benchmarkRunner.runRtmpCaseOutcome(tc, cfg, c, rep).block();
    }

    /**
     * 按 canonical plan 顺序执行全部 unit（供正式 run 与执行层测试复用）。
     */
    List<RtmpRawRecord> executePlan(RtmpFormalExperimentPlan.Plan plan, RtmpEvaluationDataset dataset,
                                    BenchmarkConfig config, OutcomeSource source) {
        List<RtmpRawRecord> records = new ArrayList<>();
        for (RtmpFormalExperimentPlan.Unit unit : plan.units()) {
            RtmpTestCase testCase = dataset.findById(unit.caseId());
            records.add(executeUnit(unit, testCase, config, source));
        }
        return records;
    }

    /**
     * 对一个 canonical unit 执行（含 run-level retry），产出唯一 Raw record。
     */
    RtmpRawRecord executeUnit(RtmpFormalExperimentPlan.Unit unit, RtmpTestCase testCase,
                              BenchmarkConfig config, OutcomeSource source) {
        ExperimentCondition condition = ExperimentCondition.valueOf(unit.condition());
        RetryOutcome retry = runWithRetry(source, testCase, config, condition,
                unit.repetition(), memoryStore, unit.memoryId());
        RtmpRunOutcome outcome = retry.outcome();
        RunStatus status = outcome.status();
        RtmpCaseEvaluation evaluation = status == RunStatus.VALID
                ? evaluator.evaluate(testCase, outcome.trace()) : null;
        String invalidReason = status == RunStatus.VALID ? null
                : (status == RunStatus.INVALID_RUN ? "invalid-run" : "retryable-failure");
        return RtmpRawRecord.of(outcome.trace(), evaluation, status, testCase, invalidReason,
                unit.conditionOrderIndex());
    }

    /**
     * Run-level retry 编排（纯逻辑、可测试）：
     * <ul>
     *   <li>第一次 VALID / INVALID_RUN → 不重跑（attempts=1）；</li>
     *   <li>第一次 RETRYABLE_FAILURE → 清理同一 memoryId 后重跑一次（attempts=2），
     *       第二次结果即最终 canonical status；</li>
     *   <li>绝不第三次执行。</li>
     * </ul>
     */
    public static RetryOutcome runWithRetry(OutcomeSource source, RtmpTestCase testCase,
                                            BenchmarkConfig config, ExperimentCondition condition,
                                            int repetition, ChatMemoryStore memoryStore,
                                            String memoryId) {
        RtmpRunOutcome first = source.execute(testCase, config, condition, repetition);
        if (!RtmpRunRetryPolicy.shouldRetry(first.status())) {
            return new RetryOutcome(first, 1);
        }
        if (memoryStore != null && memoryId != null) {
            memoryStore.deleteMessages(memoryId);
        }
        RtmpRunOutcome second = source.execute(testCase, config, condition, repetition);
        return new RetryOutcome(second, 2);
    }

    private void logEffectiveConfig(BenchmarkConfig config, RtmpFormalExperimentPlan.Plan plan) {
        log.info("[RtmpFormalExperiment] Effective config: experimentId={}, model={}, "
                        + "temperature={}, topP={}, seed={}, maxTokens={}, workflowVersion={}, "
                        + "maxConcurrency={}, rpmLimit={}, datasetVersion={}, plannedUnits={}",
                config.experimentId(), config.llmProvider(), config.temperature(), config.topP(),
                config.seed(), config.maxTokens(), config.workflowVersion(),
                config.maxConcurrency(), config.rpmLimit(), config.datasetVersion(),
                plan.units().size());
    }
}
