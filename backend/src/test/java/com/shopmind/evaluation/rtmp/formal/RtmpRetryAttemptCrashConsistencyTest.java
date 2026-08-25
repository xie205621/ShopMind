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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5-R6.1：Retry Attempt Crash Consistency Closure（测试 A–F）。
 * <p>
 * 证明：在任意 process interruption / recovery 路径下，一个 canonical run 最多 2 次真实
 * invocation，且 attempt-level retry accounting 不会因中断而重置。不调用 Real LLM / Pilot /
 * 378 runs（378 仅作为 plan 规模出现在 full-run 重建断言中）。
 */
class RtmpRetryAttemptCrashConsistencyTest {

    @TempDir
    Path tempDir;

    private static final RtmpEvaluationDataset DATASET = RtmpDatasetLoader.load();
    private static final String EXP = "RTMP-EXP01";

    // ============================================================
    //  A. attempt1 RETRYABLE → crash → resume executes attempt2 only
    // ============================================================

    @Test
    @DisplayName("A. attempt1 RETRYABLE -> crash -> resume 只执行 attempt2（不重跑 attempt1）")
    void A_attempt1Retryable_crash_resume_executesAttempt2Only() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());

        writeLedger(started(unit, 1), completed(unit, 1, RunStatus.RETRYABLE_FAILURE));
        assertEquals(2, nextAttempt(unit), "attempt1 RETRYABLE 后 resume 应执行 attempt2");

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = runWithRetryRecorded(
                unit, tc, countingSource(calls, RunStatus.VALID), 2);

        assertEquals(1, calls.get(), "resume 只执行 attempt2，绝不重跑 attempt1");
        assertEquals(2, out.attempts());
        assertEquals(RunStatus.VALID, out.outcome().status());
    }

    // ============================================================
    //  B. attempt1 STARTED → crash → resume → attempt2 VALID
    // ============================================================

    @Test
    @DisplayName("B. attempt1 STARTED -> crash -> resume -> attempt2 VALID => final VALID, attempts=2")
    void B_attempt1Started_crash_attempt2Valid() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());

        writeLedger(started(unit, 1));
        assertEquals(2, nextAttempt(unit), "STARTED 无 COMPLETED 的 attempt 已消耗");

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = runWithRetryRecorded(
                unit, tc, countingSource(calls, RunStatus.VALID), 2);

        assertEquals(1, calls.get());
        assertEquals(2, out.attempts());
        assertEquals(RunStatus.VALID, out.outcome().status());
    }

    // ============================================================
    //  C. attempt1 STARTED → crash → resume → attempt2 RETRYABLE
    // ============================================================

    @Test
    @DisplayName("C. attempt2 RETRYABLE => final RETRYABLE_FAILURE，无 attempt3")
    void C_attempt1Started_crash_attempt2Retryable() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());

        writeLedger(started(unit, 1));

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = runWithRetryRecorded(
                unit, tc, countingSource(calls, RunStatus.RETRYABLE_FAILURE), 2);

        assertEquals(1, calls.get(), "attempt2 RETRYABLE 后不得出现 attempt3");
        assertEquals(2, out.attempts());
        assertEquals(RunStatus.RETRYABLE_FAILURE, out.outcome().status());
    }

    // ============================================================
    //  D. attempt1 STARTED → crash → resume → attempt2 INVALID_RUN
    // ============================================================

    @Test
    @DisplayName("D. attempt2 INVALID_RUN => final INVALID_RUN，无 attempt3")
    void D_attempt1Started_crash_attempt2InvalidRun() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());

        writeLedger(started(unit, 1));

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = runWithRetryRecorded(
                unit, tc, countingSource(calls, RunStatus.INVALID_RUN), 2);

        assertEquals(1, calls.get(), "attempt2 INVALID_RUN 后不得出现 attempt3");
        assertEquals(2, out.attempts());
        assertEquals(RunStatus.INVALID_RUN, out.outcome().status());
    }

    // ============================================================
    //  E. same runId / same memoryId throughout
    // ============================================================

    @Test
    @DisplayName("E. 全程保持同一 runId / memoryId（retry 不产生新 identity）")
    void E_sameRunIdMemoryIdThroughout() {
        RtmpFormalExperimentPlan.Unit unit = buildPlan().units().get(0);
        RtmpTestCase tc = DATASET.findById(unit.caseId());
        assertEquals(unit.runId(), unit.memoryId(), "canonical invariant: memoryId == runId");

        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.OutcomeSource source = (t, cfg, c, rep) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE);
            }
            return new RtmpRunOutcome(validTrace(unit), RunStatus.VALID);
        };

        RtmpFormalExperimentRunner.ExecutedUnit executed =
                runner().executeUnitDetailedRecorded(unit, tc, config(), source, ledgerFile(), 1);

        assertEquals(RunStatus.VALID, executed.record().status());
        assertEquals(2, executed.attempts());
        assertEquals(unit.runId(), executed.record().runId(), "final record runId 必须保持 canonical");
        assertEquals(unit.memoryId(), executed.record().runId(), "memoryId == runId 不变");

        List<RtmpAttemptLedgerEvent> events = RtmpAttemptLedgerStore.load(ledgerFile());
        assertEquals(4, events.size(), "STARTED(1)+COMPLETED(1)+STARTED(2)+COMPLETED(2)");
        for (RtmpAttemptLedgerEvent e : events) {
            assertEquals(unit.runId(), e.runId(), "ledger 事件 runId 全程不变");
            assertEquals(unit.memoryId(), e.runId());
        }
    }

    // ============================================================
    //  F. canonical record count remains exactly one per unit
    // ============================================================

    @Test
    @DisplayName("F. crash+resume 后 canonical record 每个 unit 恰好一条")
    void F_canonicalRecordCount_exactlyOnePerUnit() {
        RtmpFormalExperimentPlan.Plan plan = buildPlan();
        RtmpFormalExperimentPlan.Unit unit0 = plan.units().get(0);

        // 模拟 crash：attempt1 已 STARTED + COMPLETED(RETRYABLE)，但无 canonical checkpoint
        writeLedger(started(unit0, 1), completed(unit0, 1, RunStatus.RETRYABLE_FAILURE));

        CountingRunner fake = new CountingRunner(unit0.caseId(), unit0.condition(), unit0.repetition());
        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new MemoryStore());
        RtmpFormalExperimentRunner.RtmpFormalExperimentResult result = runner.run(EXP, tempDir);

        assertEquals(378, result.recordsWritten(), "final raw 仍恰好 378 条");
        assertTrue(result.validated());

        // resume 阶段 unit0 只被真实调用一次（attempt2，非 attempt1 重跑）
        String key = unit0.caseId() + ":" + unit0.condition() + ":" + unit0.repetition();
        assertEquals(1, fake.invocations.getOrDefault(key, 0),
                "resume 只执行 attempt2（1 次真实 invocation）");

        // attempt ledger 证明 attempt1 只 STARTED 一次、attempt2 STARTED 一次（无重复 attempt1）
        List<RtmpAttemptLedgerEvent> events = RtmpAttemptLedgerStore.load(ledgerFile());
        Set<Integer> startedAttempts = events.stream()
                .filter(e -> e.runId().equals(unit0.runId()))
                .filter(e -> RtmpAttemptLedgerEvent.STARTED.equals(e.eventType()))
                .map(RtmpAttemptLedgerEvent::attempt)
                .collect(Collectors.toSet());
        assertEquals(Set.of(1, 2), startedAttempts,
                "attempt1 不得被重新 STARTED（否则会出现重复 attempt1）");

        // final raw 中 unit0 的 canonical runId 恰好出现一次
        RtmpExperimentPersistence.RawFile raw = RtmpExperimentPersistence.readRawFile(result.rawFile());
        assertEquals(378, raw.records().size());
        long unit0Count = raw.records().stream()
                .filter(r -> r.runId().equals(unit0.runId()))
                .count();
        assertEquals(1, unit0Count, "canonical record 每个 unit 恰好一条");
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static RtmpFormalExperimentPlan.Plan buildPlan() {
        return RtmpFormalExperimentPlan.build(EXP, DATASET);
    }

    private static BenchmarkConfig config() {
        return RtmpFormalExperimentConfig.build(EXP);
    }

    private Path ledgerFile() {
        return RtmpAttemptLedgerStore.ledgerFile(tempDir, EXP);
    }

    private RtmpFormalExperimentRunner runner() {
        return new RtmpFormalExperimentRunner(null, new MemoryStore());
    }

    private void writeLedger(RtmpAttemptLedgerEvent... events) {
        Path file = ledgerFile();
        for (RtmpAttemptLedgerEvent e : events) {
            RtmpAttemptLedgerStore.append(file, e);
        }
    }

    private int nextAttempt(RtmpFormalExperimentPlan.Unit unit) {
        List<RtmpAttemptLedgerEvent> loaded = RtmpAttemptLedgerStore.load(ledgerFile());
        Map<String, Integer> next = RtmpFormalExperimentRunner.buildAttemptRecoveryMap(
                buildPlan(), Set.of(), loaded);
        return next.get(unit.runId());
    }

    private static RtmpAttemptLedgerEvent started(RtmpFormalExperimentPlan.Unit unit, int attempt) {
        return RtmpAttemptLedgerEvent.started(EXP, unit.runId(), unit.caseId(),
                unit.condition(), unit.repetition(), attempt);
    }

    private static RtmpAttemptLedgerEvent completed(RtmpFormalExperimentPlan.Unit unit,
                                                    int attempt, RunStatus status) {
        return RtmpAttemptLedgerEvent.completed(EXP, unit.runId(), unit.caseId(),
                unit.condition(), unit.repetition(), attempt, status);
    }

    private static RtmpFormalExperimentRunner.OutcomeSource countingSource(AtomicInteger calls,
                                                                           RunStatus status) {
        return (tc, cfg, c, rep) -> {
            calls.incrementAndGet();
            return new RtmpRunOutcome(null, status);
        };
    }

    private RtmpFormalExperimentRunner.RetryOutcome runWithRetryRecorded(
            RtmpFormalExperimentPlan.Unit unit, RtmpTestCase tc,
            RtmpFormalExperimentRunner.OutcomeSource source, int nextAttempt) {
        return RtmpFormalExperimentRunner.runWithRetryRecorded(
                source, tc, config(), ExperimentCondition.valueOf(unit.condition()),
                unit.repetition(), new MemoryStore(), unit.memoryId(), EXP, unit,
                ledgerFile(), nextAttempt);
    }

    private static ExecutionTrace validTrace(RtmpFormalExperimentPlan.Unit unit) {
        RunIdentity identity = new RunIdentity(EXP, unit.condition(), unit.caseId(), unit.repetition());
        return new ExecutionTrace("trace-" + unit.runId(), identity.memoryId(), "v2.3", identity);
    }

    /** 记录每 (case:condition:repetition) 的真实 invocation 次数，并返回 INVALID_RUN canonical trace。 */
    static final class CountingRunner implements BenchmarkRunner {
        final Map<String, Integer> invocations = new HashMap<>();
        private final String caseId;
        private final String condition;
        private final int repetition;

        CountingRunner(String caseId, String condition, int repetition) {
            this.caseId = caseId;
            this.condition = condition;
            this.repetition = repetition;
        }

        @Override
        public Mono<RtmpRunOutcome> runRtmpCaseOutcome(RtmpTestCase testCase, BenchmarkConfig config,
                                                       ExperimentCondition condition, int repetition) {
            invocations.merge(testCase.id() + ":" + condition.name() + ":" + repetition, 1, Integer::sum);
            RunIdentity identity = new RunIdentity(config.experimentId(), condition.name(),
                    testCase.id(), repetition);
            ExecutionTrace trace = new ExecutionTrace("trace-" + identity.runId(),
                    identity.memoryId(), config.workflowVersion(), identity);
            return Mono.just(new RtmpRunOutcome(trace, RunStatus.INVALID_RUN));
        }

        @Override
        public Mono<ExperimentReport> run(EvaluationDataset dataset, BenchmarkConfig config,
                                          String isolationPrefix) {
            throw new UnsupportedOperationException("not used in R6.1 tests");
        }

        @Override
        public Mono<ExecutionTrace> runRtmpCase(RtmpTestCase testCase, BenchmarkConfig config,
                                                ExperimentCondition condition, int repetition) {
            throw new UnsupportedOperationException("not used in R6.1 tests");
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
