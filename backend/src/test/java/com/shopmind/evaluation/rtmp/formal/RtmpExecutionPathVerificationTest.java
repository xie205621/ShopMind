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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5-E1.1：执行路径验证（#1 / #3 / #4）。
 * <p>
 * 通过 stub {@link BenchmarkRunner#runRtmpCaseOutcome} 记录真实 invocation 顺序与次数，
 * 验证 {@link RtmpFormalExperimentRunner} 的 preflight gate、frozen condition order 与
 * run-level retry。不调用 Real LLM。
 */
class RtmpExecutionPathVerificationTest {

    @TempDir
    Path tempDir;

    private static final RtmpEvaluationDataset DATASET = RtmpDatasetLoader.load();
    private static final String EXP = "RTMP-EXP01";

    // ============================================================
    //  #1. Retry memory fallback
    // ============================================================

    @Test
    @DisplayName("#1. ChatMemoryStore 缺失 -> formal preflight reject")
    void preflight_missingMemoryStore_rejects() {
        RtmpFormalExperimentRunner.PreflightResult result =
                RtmpFormalExperimentRunner.preflight(EXP, tempDir, DATASET, null);

        assertFalse(result.valid(), "ChatMemoryStore 缺失时 preflight 必须失败");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ChatMemoryStore")),
                () -> "错误信息应包含 ChatMemoryStore gate: " + result.errors());
    }

    @Test
    @DisplayName("#1. ChatMemoryStore 存在 -> formal preflight 通过")
    void preflight_presentMemoryStore_passes() {
        RtmpFormalExperimentRunner.PreflightResult result =
                RtmpFormalExperimentRunner.preflight(EXP, tempDir, DATASET, new FakeMemoryStore());

        assertTrue(result.valid(), () -> "ChatMemoryStore 存在时 preflight 应通过: " + result.errors());
    }

    @Test
    @DisplayName("#1. retry 前清理同一个 memoryId（memoryId == runId）")
    void retry_cleansSameMemoryId() {
        RtmpTestCase testCase = DATASET.cases().get(0);
        RtmpFormalExperimentPlan.Unit unit = unitFor(testCase, "BASELINE_A", 1);

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));

        FakeMemoryStore memoryStore = new FakeMemoryStore();
        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, memoryStore);
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                runner.defaultSource(), testCase, RtmpFormalExperimentConfig.build(EXP),
                ExperimentCondition.BASELINE_A, 1, memoryStore, unit.memoryId());

        assertEquals(2, out.attempts());
        assertEquals(unit.memoryId(), unit.runId());
        assertEquals(List.of(unit.memoryId()), memoryStore.deleted,
                "retry 前必须清理与 runId 相同的 memoryId");
    }

    // ============================================================
    //  #3. Runner actual ordering
    // ============================================================

    @Test
    @DisplayName("#3. runner 实际按 frozen order 调用 runRtmpCaseOutcome")
    void runner_actualInvocationOrder_matchesFrozenRotation() {
        RtmpFormalExperimentPlan.Plan plan = RtmpFormalExperimentPlan.build(EXP, DATASET);
        String targetCase = DATASET.cases().get(0).id();

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new FakeMemoryStore());
        runner.executePlan(plan, DATASET, RtmpFormalExperimentConfig.build(EXP), runner.defaultSource());

        List<String> order = fake.invocations.stream()
                .filter(s -> s.startsWith(targetCase + ":"))
                .map(s -> s.substring(targetCase.length() + 1))
                .toList();

        assertEquals(List.of(
                "BASELINE_A:1", "BASELINE_B:1", "METHOD_C:1",
                "BASELINE_B:2", "METHOD_C:2", "BASELINE_A:2",
                "METHOD_C:3", "BASELINE_A:3", "BASELINE_B:3"), order,
                "runner 必须按 frozen balanced rotation 实际调用，而非仅 plan 正确");
    }

    // ============================================================
    //  #4. Runner actual retry
    // ============================================================

    @Test
    @DisplayName("#4. attempt1 RETRYABLE + attempt2 VALID -> 2 invocations, VALID")
    void retry_retryableThenValid() {
        RtmpTestCase testCase = DATASET.cases().get(0);
        RtmpFormalExperimentPlan.Unit unit = unitFor(testCase, "BASELINE_A", 1);

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));
        fake.outcomes.add(new RtmpRunOutcome(validTrace(testCase, unit), RunStatus.VALID));

        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new FakeMemoryStore());
        RtmpRawRecord record = runner.executeUnit(unit, testCase,
                RtmpFormalExperimentConfig.build(EXP), runner.defaultSource());

        assertEquals(RunStatus.VALID, record.status());
        assertEquals(2, fake.invocations.size());
    }

    @Test
    @DisplayName("#4. attempt1 RETRYABLE + attempt2 RETRYABLE -> 2 invocations, RETRYABLE_FAILURE")
    void retry_retryableThenRetryable() {
        RtmpTestCase testCase = DATASET.cases().get(0);
        RtmpFormalExperimentPlan.Unit unit = unitFor(testCase, "BASELINE_A", 1);

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));

        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new FakeMemoryStore());
        RtmpRawRecord record = runner.executeUnit(unit, testCase,
                RtmpFormalExperimentConfig.build(EXP), runner.defaultSource());

        assertEquals(RunStatus.RETRYABLE_FAILURE, record.status());
        assertEquals(2, fake.invocations.size());
    }

    @Test
    @DisplayName("#4. attempt1 RETRYABLE + attempt2 INVALID_RUN -> 2 invocations, INVALID_RUN")
    void retry_retryableThenInvalid() {
        RtmpTestCase testCase = DATASET.cases().get(0);
        RtmpFormalExperimentPlan.Unit unit = unitFor(testCase, "BASELINE_A", 1);

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.INVALID_RUN));

        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new FakeMemoryStore());
        RtmpRawRecord record = runner.executeUnit(unit, testCase,
                RtmpFormalExperimentConfig.build(EXP), runner.defaultSource());

        assertEquals(RunStatus.INVALID_RUN, record.status());
        assertEquals(2, fake.invocations.size());
    }

    @Test
    @DisplayName("#4. 永远不会出现第 3 次 invocation")
    void retry_neverThirdInvocation() {
        RtmpTestCase testCase = DATASET.cases().get(0);
        RtmpFormalExperimentPlan.Unit unit = unitFor(testCase, "BASELINE_A", 1);

        FakeBenchmarkRunner fake = new FakeBenchmarkRunner();
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));
        fake.outcomes.add(new RtmpRunOutcome(null, RunStatus.RETRYABLE_FAILURE));

        RtmpFormalExperimentRunner runner = new RtmpFormalExperimentRunner(fake, new FakeMemoryStore());
        runner.executeUnit(unit, testCase, RtmpFormalExperimentConfig.build(EXP), runner.defaultSource());

        assertEquals(2, fake.invocations.size(), "run-level retry 不得超过 2 次 invocation");
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static RtmpFormalExperimentPlan.Unit unitFor(RtmpTestCase testCase, String condition,
                                                         int repetition) {
        RunIdentity identity = new RunIdentity(EXP, condition, testCase.id(), repetition);
        return new RtmpFormalExperimentPlan.Unit(testCase.id(), condition, repetition,
                identity.runId(), identity.memoryId(),
                RtmpConditionOrder.conditionOrderIndex(repetition, ExperimentCondition.valueOf(condition)));
    }

    private static ExecutionTrace validTrace(RtmpTestCase testCase, RtmpFormalExperimentPlan.Unit unit) {
        RunIdentity identity = new RunIdentity(EXP, unit.condition(), testCase.id(), unit.repetition());
        return new ExecutionTrace("trace-" + unit.condition() + "-" + testCase.id(),
                identity.memoryId(), "v2.3", identity);
    }

    /** Stub BenchmarkRunner：记录 invocation 并返回预设 outcomes。 */
    static final class FakeBenchmarkRunner implements BenchmarkRunner {
        final List<String> invocations = new ArrayList<>();
        final List<RtmpRunOutcome> outcomes = new ArrayList<>();
        private int idx = 0;

        @Override
        public Mono<RtmpRunOutcome> runRtmpCaseOutcome(RtmpTestCase testCase, BenchmarkConfig config,
                                                       ExperimentCondition condition, int repetition) {
            invocations.add(testCase.id() + ":" + condition.name() + ":" + repetition);
            RtmpRunOutcome outcome = idx < outcomes.size()
                    ? outcomes.get(idx++)
                    : new RtmpRunOutcome(null, RunStatus.INVALID_RUN);
            return Mono.just(outcome);
        }

        @Override
        public Mono<ExperimentReport> run(EvaluationDataset dataset, BenchmarkConfig config,
                                          String isolationPrefix) {
            throw new UnsupportedOperationException("not used in execution-path tests");
        }

        @Override
        public Mono<ExecutionTrace> runRtmpCase(RtmpTestCase testCase, BenchmarkConfig config,
                                                ExperimentCondition condition, int repetition) {
            throw new UnsupportedOperationException("not used in execution-path tests");
        }
    }

    /** Fake ChatMemoryStore：记录 deleteMessages 调用。 */
    static final class FakeMemoryStore implements ChatMemoryStore {
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
