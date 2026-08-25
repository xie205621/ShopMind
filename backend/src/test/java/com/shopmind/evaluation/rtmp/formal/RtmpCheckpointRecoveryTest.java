package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.evaluation.domain.ExperimentReport;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentPersistence;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.RunIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5-R6：Process Interruption Recovery &amp; Checkpoint Integrity（§18 A–J）。
 * <p>
 * 覆盖 unit checkpoint、防重复、恢复（数量/identity/顺序）、crash-before-checkpoint、
 * 损坏校验、final raw 重建、final output 碰撞、retry × interruption。不调用 Real LLM。
 */
class RtmpCheckpointRecoveryTest {

    @TempDir
    Path tempDir;

    private static final RtmpEvaluationDataset DATASET = RtmpDatasetLoader.load();
    private static final String EXP = "RTMP-EXP01";

    // ============================================================
    //  A. Unit checkpoint
    // ============================================================

    @Test
    @DisplayName("A. 完成 1 unit -> checkpoint 含 1 条 canonical record")
    void checkpoint_singleUnit_roundTrips() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);

        Path file = RtmpCheckpointStore.checkpointFile(tempDir, EXP);
        RtmpCheckpointStore.append(file, completedCheckpoint(unit));

        List<RtmpExecutionCheckpoint> loaded = RtmpCheckpointStore.load(file);
        assertEquals(1, loaded.size());
        RtmpExecutionCheckpoint got = loaded.get(0);
        assertEquals(RtmpCheckpointStore.CHECKPOINT_SCHEMA_VERSION, got.schemaVersion());
        assertEquals(EXP, got.experimentId());
        assertEquals(unit.runId(), got.runId());
        assertEquals(unit.caseId(), got.caseId());
        assertEquals(unit.condition(), got.condition());
        assertEquals(unit.repetition(), got.repetition());
        assertEquals(unit.conditionOrderIndex(), got.conditionOrderIndex());
        assertEquals("INVALID_RUN", got.status());
        assertEquals(1, got.attempts());
        assertTrue(got.completed());
        assertEquals(unit.runId(), got.record().runId());
    }

    // ============================================================
    //  B. No duplicate checkpoint
    // ============================================================

    @Test
    @DisplayName("B. same runId checkpoint 两次 -> reject")
    void checkpoint_duplicateRunId_rejected() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        RtmpFormalExperimentPlan.Unit unit = plan.units().get(0);

        RtmpFormalExperimentRunner.CheckpointValidation v =
                RtmpFormalExperimentRunner.validateCheckpoints(
                        List.of(completedCheckpoint(unit), completedCheckpoint(unit)), plan, EXP);

        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("duplicate")),
                () -> "应报告 duplicate checkpoint: " + v.errors());
    }

    // ============================================================
    //  C. Recovery（10 completed -> remaining 368）
    // ============================================================

    @Test
    @DisplayName("C. checkpoint=10 completed units, plan=378 -> remaining=368")
    void recovery_tenCompleted_remaining368() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        List<RtmpExecutionCheckpoint> cps = plan.units().subList(0, 10).stream()
                .map(RtmpCheckpointRecoveryTest::completedCheckpoint)
                .toList();
        writeCheckpoints(cps);

        RtmpFormalExperimentRunner.RecoveryState state =
                RtmpFormalExperimentRunner.detectRecoveryState(plan, EXP, tempDir);

        assertTrue(state.checkpointFound());
        assertEquals(10, state.completedUnits());
        assertEquals(378 - 10, state.remainingUnits());
        assertTrue(state.resumeRequired());
        assertEquals(10, state.completedRunIds().size());
    }

    // ============================================================
    //  D. Recovery identity
    // ============================================================

    @Test
    @DisplayName("D. 恢复后的 runId 仍是 canonical runId（不生成新 identity）")
    void recovery_identity_sameCanonicalRunId() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        List<RtmpFormalExperimentPlan.Unit> done = plan.units().subList(0, 5);
        writeCheckpoints(done.stream().map(RtmpCheckpointRecoveryTest::completedCheckpoint).toList());

        RtmpFormalExperimentRunner.RecoveryState state =
                RtmpFormalExperimentRunner.detectRecoveryState(plan, EXP, tempDir);

        Set<String> expected = done.stream()
                .map(RtmpFormalExperimentPlan.Unit::runId)
                .collect(Collectors.toSet());
        assertEquals(expected, state.completedRunIds());

        for (String runId : state.completedRunIds()) {
            assertTrue(runId.startsWith(EXP + "_"), "runId 必须保持 canonical 前缀");
            assertTrue(plan.units().stream().anyMatch(u -> u.runId().equals(runId)),
                    "completed runId 必须属于 frozen plan");
        }
    }

    // ============================================================
    //  E. Recovery order（frozen condition order 保持）
    // ============================================================

    @Test
    @DisplayName("E. rep1 A/B completed, C unfinished -> 恢复执行 C（不改变 order）")
    void recovery_order_preservesFrozenConditionOrder() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        String firstCase = DATASET.cases().get(0).id();
        // plan 前 3 个 unit 是 firstCase rep1 的 A/B/C（frozen rotation）
        RtmpFormalExperimentPlan.Unit a = plan.units().get(0);
        RtmpFormalExperimentPlan.Unit b = plan.units().get(1);
        assertEquals(firstCase, a.caseId());
        assertEquals("BASELINE_A", a.condition());
        assertEquals("BASELINE_B", b.condition());
        assertEquals(1, a.repetition());
        assertEquals(1, b.repetition());

        writeCheckpoints(List.of(completedCheckpoint(a), completedCheckpoint(b)));

        FakeRunner fake = new FakeRunner();
        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new MemoryStore());
        RtmpFormalExperimentRunner.RtmpFormalExperimentResult result = runner.run(EXP, tempDir);

        assertEquals(378, result.recordsWritten());
        assertFalse(fake.invocations.contains(firstCase + ":BASELINE_A:1"), "completed A 必须跳过");
        assertFalse(fake.invocations.contains(firstCase + ":BASELINE_B:1"), "completed B 必须跳过");
        assertEquals(firstCase + ":METHOD_C:1", fake.invocations.get(0),
                "恢复后第一个执行的 unit 必须是 rep1 的 C（跳过 A/B，保持 frozen order）");
    }

    // ============================================================
    //  F. Crash before checkpoint -> abort
    // ============================================================

    @Test
    @DisplayName("F. checkpoint append IO 失败 -> 抛异常终止（不静默继续）")
    void checkpoint_appendFailure_aborts() throws IOException {
        // 使 checkpoint 父路径不可写：父路径是普通文件
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "x");
        Path file = blocker.resolve(EXP + "_checkpoint.jsonl");

        RtmpExecutionCheckpoint cp = completedCheckpoint(buildPlan().units().get(0));
        assertThrows(UncheckedIOException.class, () -> RtmpCheckpointStore.append(file, cp));
    }

    // ============================================================
    //  G. Checkpoint corruption
    // ============================================================

    @Test
    @DisplayName("G1. 非法 JSON -> preflight 失败（无 Real LLM）")
    void corruption_invalidJson_preflightFails() throws IOException {
        Path file = RtmpCheckpointStore.checkpointFile(tempDir, EXP);
        Files.writeString(file, "{ this is not valid json\n");
        assertThrows(UncheckedIOException.class,
                () -> RtmpFormalExperimentRunner.detectRecoveryState(buildPlan(), EXP, tempDir));
    }

    @Test
    @DisplayName("G2. wrong schema -> preflight 失败")
    void corruption_wrongSchema_preflightFails() {
        RtmpFormalExperimentPlan.Unit u = buildPlan().units().get(0);
        RtmpExecutionCheckpoint cp = rawCheckpoint("wrong-schema-v9", EXP,
                u.runId(), u.caseId(), u.condition(), u.repetition(), u.conditionOrderIndex());
        RtmpCheckpointStore.append(RtmpCheckpointStore.checkpointFile(tempDir, EXP), cp);

        assertThrows(IllegalStateException.class,
                () -> RtmpFormalExperimentRunner.detectRecoveryState(buildPlan(), EXP, tempDir));
    }

    @Test
    @DisplayName("G3. wrong experimentId -> preflight 失败")
    void corruption_wrongExperimentId_preflightFails() {
        RtmpFormalExperimentPlan.Unit u = buildPlan().units().get(0);
        RtmpExecutionCheckpoint cp = rawCheckpoint(RtmpCheckpointStore.CHECKPOINT_SCHEMA_VERSION,
                "RTMP-OTHER", u.runId(), u.caseId(), u.condition(), u.repetition(),
                u.conditionOrderIndex());
        RtmpCheckpointStore.append(RtmpCheckpointStore.checkpointFile(tempDir, EXP), cp);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RtmpFormalExperimentRunner.detectRecoveryState(buildPlan(), EXP, tempDir));
        assertTrue(ex.getMessage().contains("experimentId mismatch"), ex.getMessage());
    }

    @Test
    @DisplayName("G4. unknown runId -> preflight 失败")
    void corruption_unknownRunId_preflightFails() {
        String bogusRunId = EXP + "_BASELINE_A_UNKNOWN-999_1";
        RtmpExecutionCheckpoint cp = rawCheckpoint(RtmpCheckpointStore.CHECKPOINT_SCHEMA_VERSION,
                EXP, bogusRunId, DATASET.cases().get(0).id(), "BASELINE_A", 1, 0);
        RtmpCheckpointStore.append(RtmpCheckpointStore.checkpointFile(tempDir, EXP), cp);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RtmpFormalExperimentRunner.detectRecoveryState(buildPlan(), EXP, tempDir));
        assertTrue(ex.getMessage().contains("runId not in plan"), ex.getMessage());
    }

    // ============================================================
    //  H. Final reconstruction
    // ============================================================

    @Test
    @DisplayName("H. 完整执行 -> 378 records / 378 unique runIds")
    void finalReconstruction_378Records_uniqueRunIds() {
        FakeRunner fake = new FakeRunner();
        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new MemoryStore());
        RtmpFormalExperimentRunner.RtmpFormalExperimentResult result = runner.run(EXP, tempDir);

        assertEquals(378, result.recordsWritten());
        assertTrue(result.validated());

        RtmpExperimentPersistence.RawFile raw =
                RtmpExperimentPersistence.readRawFile(result.rawFile());
        assertEquals(378, raw.records().size());
        Set<String> runIds = raw.records().stream()
                .map(RtmpRawRecord::runId)
                .collect(Collectors.toSet());
        assertEquals(378, runIds.size());
    }

    // ============================================================
    //  I. Final output already exists -> reject resume
    // ============================================================

    @Test
    @DisplayName("I. checkpoint 存在 + final raw 已存在 -> 拒绝 resume")
    void finalOutputExists_rejectsResume() throws IOException {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        writeCheckpoints(List.of(completedCheckpoint(plan.units().get(0))));
        Files.writeString(tempDir.resolve(EXP + "_raw.json"), "{}");

        RtmpFormalExperimentRunner.PreflightResult pf =
                RtmpFormalExperimentRunner.preflight(EXP, tempDir, DATASET, new MemoryStore());

        assertFalse(pf.valid());
        assertTrue(pf.errors().stream().anyMatch(e -> e.contains("Output collision")),
                () -> pf.errors().toString());
    }

    // ============================================================
    //  J. Retry interaction
    // ============================================================

    @Test
    @DisplayName("J. RETRYABLE -> 中断后不产生重复 statistical unit（identity 不变、max retry=1）")
    void retry_interruption_noDuplicateStatisticalUnit() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        RtmpFormalExperimentPlan.Unit unit = plan.units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());

        RunIdentity identity = new RunIdentity(EXP, unit.condition(), unit.caseId(), unit.repetition());
        ExecutionTrace trace = new ExecutionTrace("trace-" + unit.runId(), identity.memoryId(),
                "v2.3", identity);

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.OutcomeSource source = (t, cfg, c, rep) -> {
            calls.incrementAndGet();
            // attempt1 RETRYABLE（crash 前未 checkpoint）→ 恢复后重跑，attempt2 仍 RETRYABLE → terminal
            return new RtmpRunOutcome(trace, RunStatus.RETRYABLE_FAILURE);
        };

        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(new FakeRunner(), new MemoryStore());
        RtmpFormalExperimentRunner.ExecutedUnit executed = runner.executeUnitDetailed(
                unit, tc, RtmpFormalExperimentConfig.build(EXP), source);

        assertEquals(2, executed.attempts(), "run-level retry 不得超过 2 次");
        assertEquals(2, calls.get(), "绝不第 3 次 invocation");
        assertEquals(RunStatus.RETRYABLE_FAILURE, executed.record().status());
        // identity 不变：没有 runId_retry / memoryId_retry
        assertEquals(unit.runId(), executed.record().runId());
        assertEquals(unit.memoryId(), executed.record().runId());

        // checkpoint 也使用同一 canonical runId（不产生新的 statistical unit）
        RtmpExecutionCheckpoint cp = RtmpExecutionCheckpoint.of(executed.record(), executed.attempts(), 0);
        assertEquals(unit.runId(), cp.runId());
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static RtmpFormalExperimentPlan.Plan buildPlan() {
        return RtmpFormalExperimentPlan.build(EXP, DATASET);
    }

    /** 从 canonical unit 构建 completed checkpoint（status=INVALID_RUN，attempts=1）。 */
    private static RtmpExecutionCheckpoint completedCheckpoint(RtmpFormalExperimentPlan.Unit unit) {
        RtmpTestCase tc = DATASET.findById(unit.caseId());
        RunIdentity identity = new RunIdentity(EXP, unit.condition(), unit.caseId(), unit.repetition());
        ExecutionTrace trace = new ExecutionTrace("trace-" + unit.runId(), identity.memoryId(),
                "v2.3", identity);
        RtmpRawRecord record = RtmpRawRecord.of(trace, null, RunStatus.INVALID_RUN, tc,
                "invalid-run", unit.conditionOrderIndex());
        return RtmpExecutionCheckpoint.of(record, 1, 0);
    }

    /** 直接构造任意字段的 checkpoint（供 corruption 测试）。 */
    private static RtmpExecutionCheckpoint rawCheckpoint(String schema, String experimentId,
            String runId, String caseId, String condition, int repetition, int orderIndex) {
        RtmpTestCase tc = DATASET.findById(caseId);
        RunIdentity identity = new RunIdentity(experimentId, condition, caseId, repetition);
        ExecutionTrace trace = new ExecutionTrace("trace-" + runId, identity.memoryId(), "v2.3", identity);
        RtmpRawRecord record = RtmpRawRecord.of(trace, null, RunStatus.INVALID_RUN, tc,
                "invalid-run", orderIndex);
        return new RtmpExecutionCheckpoint(schema, experimentId, runId, caseId, condition,
                repetition, orderIndex, "INVALID_RUN", 1, true, 0, record, Instant.now().toString());
    }

    private void writeCheckpoints(List<RtmpExecutionCheckpoint> cps) {
        Path file = RtmpCheckpointStore.checkpointFile(tempDir, EXP);
        for (RtmpExecutionCheckpoint cp : cps) {
            RtmpCheckpointStore.append(file, cp);
        }
    }

    /** Stub BenchmarkRunner：为每个 unit 生成 canonical identity trace，并记录 invocation。 */
    static final class FakeRunner implements BenchmarkRunner {
        final List<String> invocations = new ArrayList<>();

        @Override
        public Mono<RtmpRunOutcome> runRtmpCaseOutcome(RtmpTestCase testCase, BenchmarkConfig config,
                                                       ExperimentCondition condition, int repetition) {
            invocations.add(testCase.id() + ":" + condition.name() + ":" + repetition);
            RunIdentity identity = new RunIdentity(config.experimentId(), condition.name(),
                    testCase.id(), repetition);
            ExecutionTrace trace = new ExecutionTrace("trace-" + identity.runId(),
                    identity.memoryId(), config.workflowVersion(), identity);
            return Mono.just(new RtmpRunOutcome(trace, RunStatus.INVALID_RUN));
        }

        @Override
        public Mono<ExperimentReport> run(EvaluationDataset dataset, BenchmarkConfig config,
                                          String isolationPrefix) {
            throw new UnsupportedOperationException("not used in R6 tests");
        }

        @Override
        public Mono<ExecutionTrace> runRtmpCase(RtmpTestCase testCase, BenchmarkConfig config,
                                                ExperimentCondition condition, int repetition) {
            throw new UnsupportedOperationException("not used in R6 tests");
        }
    }

    /** Fake ChatMemoryStore：记录 deleteMessages 调用。 */
    static final class MemoryStore implements ChatMemoryStore {
        final List<Object> deleted = new ArrayList<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return List.of();
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        }

        @Override
        public void deleteMessages(Object memoryId) {
            deleted.add(memoryId);
        }
    }
}
