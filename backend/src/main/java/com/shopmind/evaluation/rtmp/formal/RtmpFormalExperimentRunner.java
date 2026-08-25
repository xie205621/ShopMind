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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical formal experiment runner — Phase 5-E1（B1）+ Phase 5-R6（checkpoint/recovery）。
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
 * <b>R6 执行完整性：</b>每个 canonical unit 完成（形成最终 canonical status）后立即
 * checkpoint（JSONL append + fsync）。JVM/进程/机器中断后，从 checkpoint 恢复：
 * 跳过已 completed unit，按 frozen condition order 只执行 remaining unit；最终 raw 从
 * checkpoint 重建并重新通过 {@link RtmpExperimentValidator} 校验，仍然恰好 378 条。
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

    /** run-level retry 的最终结果：最终 outcome + 实际 attempt 次数（1 或 2）。 */
    public record RetryOutcome(RtmpRunOutcome outcome, int attempts) {
    }

    /** preflight 结果（不执行 Agent / LLM）。 */
    public record PreflightResult(boolean valid, List<String> errors,
                                  RtmpFormalExperimentPlan.Plan plan, BenchmarkConfig config) {
    }

    /** checkpoint 校验结果。 */
    public record CheckpointValidation(boolean valid, List<String> errors) {
    }

    /** R6 recovery 状态（§17 recovery preflight 输出）。 */
    public record RecoveryState(
            boolean checkpointFound,
            int completedUnits,
            int remainingUnits,
            boolean resumeRequired,
            int resumeCount,
            List<RtmpExecutionCheckpoint> completedCheckpoints,
            Set<String> completedRunIds
    ) {
    }

    /** 一次正式实验的落盘结果。 */
    public record RtmpFormalExperimentResult(String experimentId, int recordsWritten,
                                             boolean validated, Path rawFile) {
    }

    /** 一个 canonical unit 的执行产物（Raw record + 实际 attempts）。 */
    record ExecutedUnit(RtmpRawRecord record, int attempts) {
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
     * R6 recovery preflight：检测 checkpoint、校验 schema/experimentId/runId membership/去重，
     * 并确定已完成的 canonical units（§9 / §17）。
     * <p>
     * checkpoint 存在但最终 raw 不存在 → resume candidate；checkpoint 非法 → 抛异常
     * （调用方不得启动任何 Real LLM）。
     */
    public static RecoveryState detectRecoveryState(RtmpFormalExperimentPlan.Plan plan,
                                                    String experimentId, Path outputDir) {
        Path checkpointFile = RtmpCheckpointStore.checkpointFile(outputDir, experimentId);
        if (!java.nio.file.Files.exists(checkpointFile)) {
            return new RecoveryState(false, 0, plan.units().size(), false, 0, List.of(), Set.of());
        }
        List<RtmpExecutionCheckpoint> checkpoints = RtmpCheckpointStore.load(checkpointFile);
        CheckpointValidation validation = validateCheckpoints(checkpoints, plan, experimentId);
        if (!validation.valid()) {
            throw new IllegalStateException("Checkpoint validation failed: " + validation.errors());
        }
        Set<String> completedRunIds = checkpoints.stream()
                .map(RtmpExecutionCheckpoint::runId)
                .collect(Collectors.toSet());
        int completed = completedRunIds.size();
        int maxResume = checkpoints.stream()
                .mapToInt(RtmpExecutionCheckpoint::resumeCount).max().orElse(0);
        return new RecoveryState(true, completed, plan.units().size() - completed, true,
                maxResume + 1, List.copyOf(checkpoints), Set.copyOf(completedRunIds));
    }

    /**
     * 校验 checkpoint 集合：schema 已在 load 时校验；此处校验 experimentId、runId ∈ plan、
     * 无重复 completed runId、completed==true、attempts∈{1,2}、status 合法。
     */
    public static CheckpointValidation validateCheckpoints(
            List<RtmpExecutionCheckpoint> checkpoints,
            RtmpFormalExperimentPlan.Plan plan, String experimentId) {
        List<String> errors = new ArrayList<>();
        Map<String, RtmpFormalExperimentPlan.Unit> plannedByRunId = plan.units().stream()
                .collect(Collectors.toMap(
                        RtmpFormalExperimentPlan.Unit::runId, u -> u, (a, b) -> a));
        Set<String> seenRunIds = new HashSet<>();
        for (RtmpExecutionCheckpoint cp : checkpoints) {
            if (!experimentId.equals(cp.experimentId())) {
                errors.add("checkpoint experimentId mismatch: " + cp.experimentId());
            }
            RtmpFormalExperimentPlan.Unit unit = cp.runId() == null
                    ? null : plannedByRunId.get(cp.runId());
            if (unit == null) {
                errors.add("checkpoint runId not in plan: " + cp.runId());
            } else if (!seenRunIds.add(cp.runId())) {
                errors.add("duplicate completed checkpoint runId: " + cp.runId());
            } else {
                // §6/§15：identity 字段必须与 canonical plan 一致
                if (!unit.caseId().equals(cp.caseId())
                        || !unit.condition().equals(cp.condition())
                        || unit.repetition() != cp.repetition()
                        || unit.conditionOrderIndex() != cp.conditionOrderIndex()) {
                    errors.add("checkpoint identity fields mismatch plan: " + cp.runId()
                            + " (caseId=" + cp.caseId() + ", condition=" + cp.condition()
                            + ", repetition=" + cp.repetition()
                            + ", orderIndex=" + cp.conditionOrderIndex() + ")");
                }
            }
            if (!cp.completed()) {
                errors.add("checkpoint not completed: " + cp.runId());
            }
            if (cp.attempts() < 1 || cp.attempts() > 2) {
                errors.add("checkpoint attempts out of range: " + cp.attempts() + " for " + cp.runId());
            }
            if (cp.status() == null || !isTerminalStatus(cp.status())) {
                errors.add("checkpoint status not terminal: " + cp.status() + " for " + cp.runId());
            }
            // §15 validate identity：checkpoint 携带的 canonical Raw record 必须与 runId 一致
            if (cp.record() == null) {
                errors.add("checkpoint record missing: " + cp.runId());
            } else if (!cp.runId().equals(cp.record().runId())) {
                errors.add("checkpoint record runId mismatch: " + cp.record().runId()
                        + " vs " + cp.runId());
            }
        }
        return new CheckpointValidation(errors.isEmpty(), List.copyOf(errors));
    }

    private static boolean isTerminalStatus(String status) {
        return "VALID".equals(status) || "INVALID_RUN".equals(status)
                || "RETRYABLE_FAILURE".equals(status);
    }

    /**
     * 执行完整正式实验（blocking orchestration；供 opt-in entry point 调用）。
     */
    public RtmpFormalExperimentResult run(String experimentId, Path outputDir) {
        RtmpEvaluationDataset dataset = RtmpDatasetLoader.load();
        PreflightResult preflight = preflight(experimentId, outputDir, dataset, memoryStore);
        if (!preflight.valid()) {
            throw new IllegalStateException("Formal experiment preflight failed: " + preflight.errors());
        }
        BenchmarkConfig config = preflight.config();
        RtmpFormalExperimentPlan.Plan plan = preflight.plan();
        logEffectiveConfig(config, plan);

        RecoveryState recovery = detectRecoveryState(plan, experimentId, outputDir);
        logRecoveryState(recovery);

        Path checkpointFile = RtmpCheckpointStore.checkpointFile(outputDir, experimentId);
        Path ledgerFile = RtmpAttemptLedgerStore.ledgerFile(outputDir, experimentId);
        Set<String> completedRunIds = new HashSet<>(recovery.completedRunIds());
        Map<String, RtmpRawRecord> checkpointRecords = new HashMap<>();
        for (RtmpExecutionCheckpoint cp : recovery.completedCheckpoints()) {
            checkpointRecords.put(cp.runId(), cp.record());
        }
        int resumeCount = recovery.resumeCount();

        List<RtmpAttemptLedgerEvent> ledgerEvents = RtmpAttemptLedgerStore.load(ledgerFile);
        Map<String, Integer> nextAttemptByRunId =
                buildAttemptRecoveryMap(plan, completedRunIds, ledgerEvents);

        List<RtmpRawRecord> allRecords = new ArrayList<>();
        for (RtmpFormalExperimentPlan.Unit unit : plan.units()) {
            if (completedRunIds.contains(unit.runId())) {
                allRecords.add(checkpointRecords.get(unit.runId()));
            } else {
                RtmpTestCase testCase = dataset.findById(unit.caseId());
                int nextAttempt = nextAttemptByRunId.getOrDefault(unit.runId(), 1);
                ExecutedUnit executed = executeUnitDetailedRecorded(
                        unit, testCase, config, defaultSource(), ledgerFile, nextAttempt);
                RtmpExecutionCheckpoint cp = RtmpExecutionCheckpoint.of(
                        executed.record(), executed.attempts(), resumeCount);
                checkpointAndAppend(checkpointFile, cp, completedRunIds);
                allRecords.add(executed.record());
            }
        }

        if (allRecords.size() != RtmpFormalExperimentPlan.EXPECTED_UNITS) {
            throw new IllegalStateException("Final raw reconstruction has " + allRecords.size()
                    + " records (expected " + RtmpFormalExperimentPlan.EXPECTED_UNITS + ")");
        }

        RtmpExperimentValidator.Result validation = RtmpExperimentValidator.validate(allRecords);
        if (!validation.valid()) {
            throw new IllegalStateException("Raw records failed validation: " + validation.errors());
        }

        Path rawFile = RtmpExperimentPersistence.writeRaw(allRecords, outputDir);
        String sourceRawPattern = rawFile.getFileName().toString();
        String generatedAt = Instant.now().toString();

        RtmpSummary summary = RtmpSummaryBuilder.build(
                allRecords, experimentId, sourceRawPattern, generatedAt);
        RtmpExperimentPersistence.writeSummary(summary, outputDir);

        RtmpComparison comparison = RtmpComparisonBuilder.build(
                allRecords, experimentId, sourceRawPattern, generatedAt);
        RtmpComparison analyzed = RtmpStatisticalAnalyzer.analyze(allRecords, comparison);
        RtmpExperimentPersistence.writeComparison(analyzed, outputDir);

        return new RtmpFormalExperimentResult(experimentId, allRecords.size(), true, rawFile);
    }

    /**
     * 生产默认 outcome source：通过 {@code benchmarkRunner.runRtmpCaseOutcome(...).block()}
     * 真实驱动单次 run（供 {@link #run} 与测试复用同一路径）。
     */
    OutcomeSource defaultSource() {
        return (tc, cfg, c, rep) -> benchmarkRunner.runRtmpCaseOutcome(tc, cfg, c, rep).block();
    }

    /**
     * 按 canonical plan 顺序执行全部 unit（供执行层测试复用；不 checkpoint）。
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
        return executeUnitDetailed(unit, testCase, config, source).record();
    }

    /**
     * 对一个 canonical unit 执行，返回 Raw record + 实际 attempts（供 checkpoint 记录）。
     * <p>
     * 纯逻辑路径（不写 attempt ledger），供执行层测试复用。
     */
    ExecutedUnit executeUnitDetailed(RtmpFormalExperimentPlan.Unit unit, RtmpTestCase testCase,
                                     BenchmarkConfig config, OutcomeSource source) {
        ExperimentCondition condition = ExperimentCondition.valueOf(unit.condition());
        RetryOutcome retry = runWithRetry(source, testCase, config, condition,
                unit.repetition(), memoryStore, unit.memoryId());
        return new ExecutedUnit(buildRecord(retry.outcome(), testCase, unit), retry.attempts());
    }

    /**
     * 对一个 canonical unit 执行（带 crash-safe attempt ledger），返回 Raw record + 实际 attempts。
     * <p>
     * 从 {@code nextAttempt}（恢复时为已消耗 attempt 数 + 1）继续执行，保证任意
     * crash/recovery 路径下 canonical run 最多 2 次真实 invocation。
     */
    ExecutedUnit executeUnitDetailedRecorded(RtmpFormalExperimentPlan.Unit unit, RtmpTestCase testCase,
                                             BenchmarkConfig config, OutcomeSource source,
                                             Path ledgerFile, int nextAttempt) {
        ExperimentCondition condition = ExperimentCondition.valueOf(unit.condition());
        RetryOutcome retry = runWithRetryRecorded(source, testCase, config, condition,
                unit.repetition(), memoryStore, unit.memoryId(), config.experimentId(), unit,
                ledgerFile, nextAttempt);
        return new ExecutedUnit(buildRecord(retry.outcome(), testCase, unit), retry.attempts());
    }

    /** 从 final outcome 构建 canonical Raw record（VALID 才做 case evaluation）。 */
    private RtmpRawRecord buildRecord(RtmpRunOutcome outcome, RtmpTestCase testCase,
                                      RtmpFormalExperimentPlan.Unit unit) {
        RunStatus status = outcome.status();
        RtmpCaseEvaluation evaluation = status == RunStatus.VALID
                ? evaluator.evaluate(testCase, outcome.trace()) : null;
        String invalidReason = status == RunStatus.VALID ? null
                : (status == RunStatus.INVALID_RUN ? "invalid-run" : "retryable-failure");
        return RtmpRawRecord.of(outcome.trace(), evaluation, status, testCase,
                invalidReason, unit.conditionOrderIndex());
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

    /**
     * Run-level retry 编排（带 crash-safe attempt ledger）— Phase 5-R6.1。
     * <p>
     * 与 {@link #runWithRetry} 的差异：每次真实 invocation 前写 {@code STARTED}、完成后写
     * {@code COMPLETED + status}，使 process interruption 后可从 ledger 精确得知“哪些 attempt
     * 已被消耗”，从而只执行 remaining attempt（{@code nextAttempt}）。
     * <ul>
     *   <li>{@code nextAttempt == 1}：fresh，attempt1 决定是否 retry（与 {@link #runWithRetry} 一致）；</li>
     *   <li>{@code nextAttempt == 2}：attempt1 已在中断前消耗（crash mid-attempt1 或
     *       attempt1 RETRYABLE），直接执行 attempt2，绝不回头执行 attempt1，也绝不 attempt3。</li>
     * </ul>
     * 返回的 {@code attempts} 即最后一个被执行 attempt 的序号（1 或 2）。
     */
    static RetryOutcome runWithRetryRecorded(OutcomeSource source, RtmpTestCase testCase,
                                             BenchmarkConfig config, ExperimentCondition condition,
                                             int repetition, ChatMemoryStore memoryStore,
                                             String memoryId, String experimentId,
                                             RtmpFormalExperimentPlan.Unit unit,
                                             Path ledgerFile, int nextAttempt) {
        RtmpRunOutcome outcome;
        int attempt = nextAttempt;
        if (attempt == 1) {
            outcome = executeAttemptRecorded(source, testCase, config, condition, repetition,
                    experimentId, unit, ledgerFile, 1);
            if (RtmpRunRetryPolicy.shouldRetry(outcome.status())) {
                if (memoryStore != null && memoryId != null) {
                    memoryStore.deleteMessages(memoryId);
                }
                attempt = 2;
                outcome = executeAttemptRecorded(source, testCase, config, condition, repetition,
                        experimentId, unit, ledgerFile, 2);
            }
        } else {
            // attempt1 已消耗：执行唯一的 remaining retry attempt。
            if (memoryStore != null && memoryId != null) {
                memoryStore.deleteMessages(memoryId);
            }
            outcome = executeAttemptRecorded(source, testCase, config, condition, repetition,
                    experimentId, unit, ledgerFile, 2);
        }
        return new RetryOutcome(outcome, attempt);
    }

    /** 单次真实 invocation，前后分别持久化 {@code STARTED} / {@code COMPLETED + status}。 */
    private static RtmpRunOutcome executeAttemptRecorded(OutcomeSource source, RtmpTestCase testCase,
                                                         BenchmarkConfig config,
                                                         ExperimentCondition condition, int repetition,
                                                         String experimentId,
                                                         RtmpFormalExperimentPlan.Unit unit,
                                                         Path ledgerFile, int attempt) {
        RtmpAttemptLedgerStore.append(ledgerFile, RtmpAttemptLedgerEvent.started(
                experimentId, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), attempt));
        RtmpRunOutcome outcome = source.execute(testCase, config, condition, repetition);
        RtmpAttemptLedgerStore.append(ledgerFile, RtmpAttemptLedgerEvent.completed(
                experimentId, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), attempt, outcome.status()));
        return outcome;
    }

    /**
     * 从 attempt ledger 重建每个未完成 canonical unit 的下一个 attempt 序号（1 或 2）。
     * <p>
     * 恢复语义：
     * <ul>
     *   <li>已 checkpoint 的 unit → skip（不进入返回值）；</li>
     *   <li>未消耗任何 attempt（无 STARTED）→ nextAttempt=1（fresh）；</li>
     *   <li>已消耗 attempt1（STARTED 无 COMPLETED，或 COMPLETED=RETRYABLE）→ nextAttempt=2；</li>
     *   <li>已消耗 attempt1 且 COMPLETED 为 terminal，或已消耗 attempt2 → 抛异常
     *       （record 已丢失或预算耗尽，拒绝在无 Real LLM 前继续，避免 fabrication）。</li>
     * </ul>
     */
    static Map<String, Integer> buildAttemptRecoveryMap(
            RtmpFormalExperimentPlan.Plan plan, Set<String> completedRunIds,
            List<RtmpAttemptLedgerEvent> ledgerEvents) {
        Map<String, Integer> consumed = RtmpAttemptLedgerStore.consumedAttempts(ledgerEvents);
        Map<String, String> lastCompleted = RtmpAttemptLedgerStore.lastCompletedStatus(ledgerEvents);
        Map<String, Integer> next = new HashMap<>();
        for (RtmpFormalExperimentPlan.Unit unit : plan.units()) {
            if (completedRunIds.contains(unit.runId())) {
                continue;
            }
            int c = consumed.getOrDefault(unit.runId(), 0);
            String status = lastCompleted.get(unit.runId());
            if (c == 0) {
                next.put(unit.runId(), 1);
            } else if (c == 1) {
                if (status == null || "RETRYABLE_FAILURE".equals(status)) {
                    next.put(unit.runId(), 2);
                } else {
                    throw new IllegalStateException("Attempt ledger inconsistency for runId="
                            + unit.runId() + ": attempt1 completed terminal (" + status
                            + ") but no canonical checkpoint exists.");
                }
            } else {
                throw new IllegalStateException("Attempt ledger inconsistency for runId="
                        + unit.runId() + ": " + c + " attempts already started (max 2).");
            }
        }
        return next;
    }

    /**
     * 写 checkpoint 前拒绝 same runId 重复；append 失败（IO）时向上抛异常终止 formal execution。
     */
    private void checkpointAndAppend(Path checkpointFile, RtmpExecutionCheckpoint checkpoint,
                                     Set<String> completedRunIds) {
        if (completedRunIds.contains(checkpoint.runId())) {
            throw new IllegalStateException("Duplicate canonical checkpoint: " + checkpoint.runId());
        }
        RtmpCheckpointStore.append(checkpointFile, checkpoint);
        completedRunIds.add(checkpoint.runId());
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

    private void logRecoveryState(RecoveryState recovery) {
        log.info("[RtmpFormalExperiment] Recovery preflight: checkpointFound={}, completedUnits={}, "
                        + "remainingUnits={}, resumeRequired={}, resumeCount={}",
                recovery.checkpointFound(), recovery.completedUnits(), recovery.remainingUnits(),
                recovery.resumeRequired(), recovery.resumeCount());
    }
}
