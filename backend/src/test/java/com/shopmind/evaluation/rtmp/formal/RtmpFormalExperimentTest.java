package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.rtmp.ContextRisk;
import com.shopmind.evaluation.rtmp.ExpectedToolAction;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTaskCategory;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.ToolRiskProfile;
import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentPersistence;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.RunIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5-E1：execution-layer closure 测试（§十五 A–G）。
 * <p>
 * 覆盖 formal matrix / condition order / identity / retry / model config / output
 * collision / conditionOrderIndex provenance。不调用 Real LLM。
 */
class RtmpFormalExperimentTest {

    @TempDir
    Path tempDir;

    private static final RtmpEvaluationDataset DATASET = RtmpDatasetLoader.load();

    // ============================================================
    //  A. Formal matrix
    // ============================================================

    @Test
    @DisplayName("A. Formal matrix: 42×3×3 = 378 canonical units")
    void formalMatrix_is378CanonicalUnits() {
        RtmpFormalExperimentPlan.Plan plan =
                RtmpFormalExperimentPlan.build("RTMP-EXP01", DATASET);
        RtmpFormalExperimentPlan.ValidationResult v = RtmpFormalExperimentPlan.validate(plan);

        assertTrue(v.valid(), () -> "errors: " + v.errors());
        assertEquals(RtmpFormalExperimentPlan.EXPECTED_UNITS, v.plannedUnits());
        assertEquals(378, v.uniqueRunIds());
        assertEquals(378, v.uniqueMemoryIds());
        assertEquals(42, v.caseCoverage());
        assertEquals(3, v.conditionCoverage());
        assertEquals(3, v.repetitionCoverage());
    }

    // ============================================================
    //  B. Condition order
    // ============================================================

    @Test
    @DisplayName("B. Condition order: deterministic balanced rotation")
    void conditionOrder_isFrozenBalancedRotation() {
        assertEquals(List.of(
                        ExperimentCondition.BASELINE_A,
                        ExperimentCondition.BASELINE_B,
                        ExperimentCondition.METHOD_C),
                RtmpConditionOrder.orderFor(1));
        assertEquals(List.of(
                        ExperimentCondition.BASELINE_B,
                        ExperimentCondition.METHOD_C,
                        ExperimentCondition.BASELINE_A),
                RtmpConditionOrder.orderFor(2));
        assertEquals(List.of(
                        ExperimentCondition.METHOD_C,
                        ExperimentCondition.BASELINE_A,
                        ExperimentCondition.BASELINE_B),
                RtmpConditionOrder.orderFor(3));
    }

    @Test
    @DisplayName("B. conditionOrderIndex mapping per repetition")
    void conditionOrderIndex_matchesFrozenRotation() {
        assertEquals(0, RtmpConditionOrder.conditionOrderIndex(1, ExperimentCondition.BASELINE_A));
        assertEquals(1, RtmpConditionOrder.conditionOrderIndex(1, ExperimentCondition.BASELINE_B));
        assertEquals(2, RtmpConditionOrder.conditionOrderIndex(1, ExperimentCondition.METHOD_C));

        assertEquals(0, RtmpConditionOrder.conditionOrderIndex(2, ExperimentCondition.BASELINE_B));
        assertEquals(1, RtmpConditionOrder.conditionOrderIndex(2, ExperimentCondition.METHOD_C));
        assertEquals(2, RtmpConditionOrder.conditionOrderIndex(2, ExperimentCondition.BASELINE_A));

        assertEquals(0, RtmpConditionOrder.conditionOrderIndex(3, ExperimentCondition.METHOD_C));
        assertEquals(1, RtmpConditionOrder.conditionOrderIndex(3, ExperimentCondition.BASELINE_A));
        assertEquals(2, RtmpConditionOrder.conditionOrderIndex(3, ExperimentCondition.BASELINE_B));
    }

    // ============================================================
    //  C. Identity
    // ============================================================

    @Test
    @DisplayName("C. Identity: runId format + memoryId == runId + uniqueness")
    void identity_runIdFormatAndMemoryIdEquality() {
        RtmpFormalExperimentPlan.Plan plan =
                RtmpFormalExperimentPlan.build("RTMP-EXP01", DATASET);
        // 抽样验证一个 unit 的 identity 契约
        RtmpFormalExperimentPlan.Unit first = plan.units().get(0);
        assertEquals("RTMP-EXP01_" + first.condition() + "_" + first.caseId() + "_"
                + first.repetition(), first.runId());
        assertEquals(first.runId(), first.memoryId());
    }

    // ============================================================
    //  D. Retry
    // ============================================================

    private static RtmpFormalExperimentRunner.OutcomeSource countingSource(
            AtomicInteger calls, RunStatus first, RunStatus second) {
        AtomicInteger idx = new AtomicInteger();
        return (tc, cfg, c, rep) -> {
            calls.incrementAndGet();
            RunStatus status = idx.getAndIncrement() == 0 ? first : second;
            return new RtmpRunOutcome(null, status);
        };
    }

    @Test
    @DisplayName("D. Retry: VALID -> 0 retry")
    void retry_valid_doesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.VALID, null), null, null,
                ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(RunStatus.VALID, out.outcome().status());
        assertEquals(1, out.attempts());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("D. Retry: INVALID_RUN -> 0 retry")
    void retry_invalid_doesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.INVALID_RUN, null), null, null,
                ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(RunStatus.INVALID_RUN, out.outcome().status());
        assertEquals(1, out.attempts());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("D. Retry: RETRYABLE -> exactly 1 retry, success -> VALID")
    void retry_retryableThenValid_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.RETRYABLE_FAILURE, RunStatus.VALID), null, null,
                ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(RunStatus.VALID, out.outcome().status());
        assertEquals(2, out.attempts());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("D. Retry: retry transient again -> RETRYABLE_FAILURE")
    void retry_retryableThenRetryable_staysRetryable() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.RETRYABLE_FAILURE, RunStatus.RETRYABLE_FAILURE),
                null, null, ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(RunStatus.RETRYABLE_FAILURE, out.outcome().status());
        assertEquals(2, out.attempts());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("D. Retry: retry nonrecoverable -> INVALID_RUN")
    void retry_retryableThenInvalid_invalid() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.RetryOutcome out = RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.RETRYABLE_FAILURE, RunStatus.INVALID_RUN),
                null, null, ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(RunStatus.INVALID_RUN, out.outcome().status());
        assertEquals(2, out.attempts());
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("D. Retry: never 3 attempts")
    void retry_neverThreeAttempts() {
        AtomicInteger calls = new AtomicInteger();
        RtmpFormalExperimentRunner.runWithRetry(
                countingSource(calls, RunStatus.RETRYABLE_FAILURE, RunStatus.RETRYABLE_FAILURE),
                null, null, ExperimentCondition.BASELINE_A, 1, null, null);
        assertEquals(2, calls.get(), "run-level retry must never issue a 3rd attempt");
    }

    // ============================================================
    //  E. Model config
    // ============================================================

    @Test
    @DisplayName("E. Model config: qwen-max / seed=null / maxTokens=null / topP=0.9 / temperature=0.1")
    void modelConfig_isFrozen() {
        BenchmarkConfig config = RtmpFormalExperimentConfig.build("RTMP-EXP01");
        assertEquals("qwen-max", config.llmProvider());
        assertNull(config.seed());
        assertNull(config.maxTokens());
        assertEquals(0.1, config.temperature(), 1e-9);
        assertEquals(0.9, config.topP(), 1e-9);
        assertEquals("v2.3", config.workflowVersion());
        assertEquals("rtmp_v1.0", config.datasetVersion());
    }

    // ============================================================
    //  F. Output collision
    // ============================================================

    @Test
    @DisplayName("F. Collision: none exist -> pass")
    void collision_noneExist_passes() {
        RtmpExperimentPersistence.assertOutputsDoNotExist("RTMP-EXP01", tempDir);
    }

    @Test
    @DisplayName("F. Collision: raw exists -> reject")
    void collision_rawExists_rejects() throws Exception {
        Files.writeString(tempDir.resolve("RTMP-EXP01_raw.json"), "{}");
        assertThrows(IllegalStateException.class,
                () -> RtmpExperimentPersistence.assertOutputsDoNotExist("RTMP-EXP01", tempDir));
    }

    @Test
    @DisplayName("F. Collision: summary exists -> reject")
    void collision_summaryExists_rejects() throws Exception {
        Files.writeString(tempDir.resolve("RTMP-EXP01_summary.json"), "{}");
        assertThrows(IllegalStateException.class,
                () -> RtmpExperimentPersistence.assertOutputsDoNotExist("RTMP-EXP01", tempDir));
    }

    @Test
    @DisplayName("F. Collision: comparison exists -> reject")
    void collision_comparisonExists_rejects() throws Exception {
        Files.writeString(tempDir.resolve("comparison.json"), "{}");
        assertThrows(IllegalStateException.class,
                () -> RtmpExperimentPersistence.assertOutputsDoNotExist("RTMP-EXP01", tempDir));
    }

    // ============================================================
    //  G. conditionOrderIndex provenance（B4：不改 runId/caseId/condition/repetition）
    // ============================================================

    @Test
    @DisplayName("G. RtmpRawRecord records conditionOrderIndex without altering identity fields")
    void rawRecord_recordsConditionOrderIndex() {
        RtmpTestCase tc = minimalTestCase("RTMP-001");
        RunIdentity identity = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 1);
        ExecutionTrace trace = new ExecutionTrace("trace-1", identity.memoryId(), "v2.3", identity);

        RtmpRawRecord record = RtmpRawRecord.of(
                trace, null, RunStatus.INVALID_RUN, tc, "invalid-run", 2);

        assertEquals(2, record.conditionOrderIndex());
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-001_1", record.runId());
        assertEquals("BASELINE_A", record.condition());
        assertEquals("RTMP-001", record.caseId());
        assertEquals(1, record.repetition());
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static RtmpTestCase minimalTestCase(String id) {
        return new RtmpTestCase(
                id, "query-" + id, RtmpTaskCategory.SAFE_LOW_RISK, "ANSWER_EXPECTED",
                null, ExpectedToolAction.NOT_CALL,
                List.of("queryOrder", "refund", "queryPoints", "queryCoupons"),
                new ToolRiskProfile("NONE", "NONE", "N_A", "MEDIUM", "OWN_DATA"),
                new ContextRisk("HIGH", "AUTHORIZED", "OWN_RESOURCE", "NORMAL"),
                "USER", "NONE", false, null, "mock", List.of());
    }
}
