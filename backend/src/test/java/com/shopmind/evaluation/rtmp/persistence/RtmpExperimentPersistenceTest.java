package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.evaluation.rtmp.ExpectedToolAction;
import com.shopmind.evaluation.rtmp.RtmpTaskCategory;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.experiment.ControlType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-B4：Raw 持久化 + 完整性校验 + null/无效状态保留测试。
 */
class RtmpExperimentPersistenceTest {

    @TempDir
    Path tempDir;

    // ============================================================
    //  Raw identity / 字段持久化
    // ============================================================

    @Test
    @DisplayName("1. Raw identity serialization：runId/experimentId/condition/caseId/repetition 保留")
    void rawIdentitySerialization() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-001", RtmpTaskCategory.SAFE_LOW_RISK,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-001", 1, tc,
                List.of(RtmpB4Fixtures.toolCall("BASELINE_A", "RTMP-001", 1, 1, "queryOrder", "queryOrder", false, 10L)),
                List.of(), List.of(), RunStatus.VALID);

        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-001_1", r.runId());
        assertEquals("RTMP-EXP01", r.experimentId());
        assertEquals("BASELINE_A", r.condition());
        assertEquals("RTMP-001", r.caseId());
        assertEquals(1, r.repetition());
    }

    @Test
    @DisplayName("2. condition persistence：三种 condition 原样持久化")
    void conditionPersistence() {
        assertEquals("BASELINE_B",
                RtmpB4Fixtures.raw("BASELINE_B", "RTMP-002", 1,
                        RtmpB4Fixtures.testCase("RTMP-002", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                                "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL),
                        List.of(), List.of(), List.of(), RunStatus.VALID).condition());
        assertEquals("METHOD_C",
                RtmpB4Fixtures.raw("METHOD_C", "RTMP-003", 1,
                        RtmpB4Fixtures.testCase("RTMP-003", RtmpTaskCategory.OVER_REFUSAL_BOUNDARY,
                                "ANSWER_EXPECTED", "refund", ExpectedToolAction.CALL),
                        List.of(), List.of(), List.of(), RunStatus.VALID).condition());
    }

    @Test
    @DisplayName("3. repetition persistence")
    void repetitionPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-004", 2,
                RtmpB4Fixtures.testCase("RTMP-004", RtmpTaskCategory.MULTI_TOOL,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(), List.of(), RunStatus.VALID);
        assertEquals(2, r.repetition());
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-004_2", r.runId());
    }

    @Test
    @DisplayName("4. run_id round-trip：写盘 + 读回保持 runId")
    void runIdRoundTrip() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-005", 1,
                RtmpB4Fixtures.testCase("RTMP-005", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(), List.of(), RunStatus.VALID);

        Path file = RtmpExperimentPersistence.writeRaw(List.of(r), tempDir);
        RtmpExperimentPersistence.RawFile read = RtmpExperimentPersistence.readRawFile(file);

        assertEquals(1, read.records().size());
        assertEquals(r.runId(), read.records().get(0).runId());
    }

    // ============================================================
    //  Event / evaluation 持久化
    // ============================================================

    @Test
    @DisplayName("5. ToolCallEvent persistence：attempted/executed/blocked/latency 保留")
    void toolCallEventPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_B", "RTMP-006", 1,
                RtmpB4Fixtures.testCase("RTMP-006", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                        "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL),
                List.of(RtmpB4Fixtures.toolCall("BASELINE_B", "RTMP-006", 1, 1, "refund", null, true, 0L)),
                List.of(), List.of(), RunStatus.VALID);

        assertEquals(1, r.toolCalls().size());
        assertEquals("refund", r.toolCalls().get(0).attemptedTool());
        assertNull(r.toolCalls().get(0).executedTool());
        assertTrue(r.toolCalls().get(0).verifierBlocked());
    }

    @Test
    @DisplayName("6. PruningEvent persistence：Method C 保留 pruning 决策")
    void pruningEventPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("METHOD_C", "RTMP-007", 1,
                RtmpB4Fixtures.testCase("RTMP-007", RtmpTaskCategory.OVER_REFUSAL_BOUNDARY,
                        "ANSWER_EXPECTED", "refund", ExpectedToolAction.CALL),
                List.of(), List.of(),
                List.of(RtmpB4Fixtures.pruning("METHOD_C", "RTMP-007", 1, 1)),
                RunStatus.VALID);

        assertEquals(1, r.pruningEvents().size());
        assertEquals(List.of("refund"), r.pruningEvents().get(0).visibleTools());
        assertEquals(4, r.pruningEvents().get(0).inputToolCount());
    }

    @Test
    @DisplayName("7. ControlOverheadEvent persistence：latency 保留、token/cost 为 null")
    void controlOverheadEventPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("METHOD_C", "RTMP-008", 1,
                RtmpB4Fixtures.testCase("RTMP-008", RtmpTaskCategory.MULTI_TOOL,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-008", 1,
                        ControlType.RTMP_ROUTER, 1, 6L)), List.of(), RunStatus.VALID);

        assertEquals(1, r.controlOverheadEvents().size());
        assertEquals(ControlType.RTMP_ROUTER, r.controlOverheadEvents().get(0).controlType());
        assertEquals(6L, r.controlOverheadEvents().get(0).latencyMs());
        assertNull(r.controlOverheadEvents().get(0).promptTokens());
        assertNull(r.controlOverheadEvents().get(0).cost());
    }

    @Test
    @DisplayName("8. RtmpCaseEvaluation persistence：L1/L2/L3/coreTask/overRefusal 保留")
    void evaluationPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-009", 1,
                RtmpB4Fixtures.testCase("RTMP-009", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                        "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL),
                List.of(RtmpB4Fixtures.toolCall("BASELINE_A", "RTMP-009", 1, 1, "refund", "refund", false, 0L)),
                List.of(), List.of(), RunStatus.VALID);

        assertNotNull(r.evaluation());
        assertTrue(r.evaluation().l3ActualSafetyViolation());
        assertTrue(r.evaluation().l2HighRiskToolMisuse());
        assertFalse(r.evaluation().coreTaskEligible());
    }

    // ============================================================
    //  null token / cost 语义
    // ============================================================

    @Test
    @DisplayName("9. null token 不被改写为 0：round-trip 后仍为 null")
    void nullTokenPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("METHOD_C", "RTMP-010", 1,
                RtmpB4Fixtures.testCase("RTMP-010", RtmpTaskCategory.MULTI_TOOL,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-010", 1,
                        ControlType.RTMP_ROUTER, 1, 5L)), List.of(), RunStatus.VALID);

        // 单独序列化 control event，验证 promptTokens 以 null 落盘（不改为 0）
        String eventJson = RtmpExperimentPersistence.toJson(r.controlOverheadEvents().get(0));
        assertTrue(eventJson.contains("\"promptTokens\" : null"),
                "control overhead 的 promptTokens 必须序列化为 null，而非 0");

        Path file = RtmpExperimentPersistence.writeRaw(List.of(r), tempDir);
        RtmpRawRecord read = RtmpExperimentPersistence.readRawFile(file).records().get(0);
        assertNull(read.controlOverheadEvents().get(0).promptTokens());
    }

    @Test
    @DisplayName("10. null cost 不被改写：round-trip 后仍为 null")
    void nullCostPersistence() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("METHOD_C", "RTMP-011", 1,
                RtmpB4Fixtures.testCase("RTMP-011", RtmpTaskCategory.MULTI_TOOL,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-011", 1,
                        ControlType.RTMP_ROUTER, 1, 5L)), List.of(), RunStatus.VALID);

        Path file = RtmpExperimentPersistence.writeRaw(List.of(r), tempDir);
        RtmpRawRecord read = RtmpExperimentPersistence.readRawFile(file).records().get(0);
        assertNull(read.controlOverheadEvents().get(0).cost());
    }

    // ============================================================
    //  invalid / partial 保留
    // ============================================================

    @Test
    @DisplayName("11. invalid run 保留：INVALID_RUN 记录不被删除")
    void invalidRunRetention() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-012", 1,
                RtmpB4Fixtures.testCase("RTMP-012", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(), List.of(), RunStatus.INVALID_RUN);

        assertEquals(RunStatus.INVALID_RUN, r.status());
        assertNull(r.evaluation());
        assertEquals("missing-run-metadata", r.invalidReason());

        Path file = RtmpExperimentPersistence.writeRaw(List.of(r), tempDir);
        RtmpRawRecord read = RtmpExperimentPersistence.readRawFile(file).records().get(0);
        assertEquals(RunStatus.INVALID_RUN, read.status());
    }

    @Test
    @DisplayName("12. partial run 保留：RETRYABLE_FAILURE 记录不被删除")
    void retryableRunRetention() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_B", "RTMP-013", 1,
                RtmpB4Fixtures.testCase("RTMP-013", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(), List.of(), RunStatus.RETRYABLE_FAILURE);

        assertEquals(RunStatus.RETRYABLE_FAILURE, r.status());
        assertEquals("transient-timeout", r.invalidReason());
    }

    // ============================================================
    //  A/B/C 污染隔离 + duplicate
    // ============================================================

    @Test
    @DisplayName("13. A/B/C contamination isolation：非法 event 被 validator 拒绝")
    void abcContaminationIsolation() {
        // A 携带 verifier event → 拒绝
        RtmpRawRecord aWithVerifier = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-014", 1,
                RtmpB4Fixtures.testCase("RTMP-014", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("BASELINE_A", "RTMP-014", 1,
                        ControlType.SAFETY_VERIFIER, 1, 5L)), List.of(), RunStatus.VALID);
        assertFalse(RtmpExperimentValidator.validate(List.of(aWithVerifier)).valid());

        // B 携带 router event → 拒绝
        RtmpRawRecord bWithRouter = RtmpB4Fixtures.raw("BASELINE_B", "RTMP-015", 1,
                RtmpB4Fixtures.testCase("RTMP-015", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("BASELINE_B", "RTMP-015", 1,
                        ControlType.RTMP_ROUTER, 1, 5L)), List.of(), RunStatus.VALID);
        assertFalse(RtmpExperimentValidator.validate(List.of(bWithRouter)).valid());

        // C 携带 verifier event → 拒绝
        RtmpRawRecord cWithVerifier = RtmpB4Fixtures.raw("METHOD_C", "RTMP-016", 1,
                RtmpB4Fixtures.testCase("RTMP-016", RtmpTaskCategory.MULTI_TOOL,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-016", 1,
                        ControlType.SAFETY_VERIFIER, 1, 5L)), List.of(), RunStatus.VALID);
        assertFalse(RtmpExperimentValidator.validate(List.of(cWithVerifier)).valid());

        // 正确 fixture（B 带 verifier）→ 通过
        RtmpRawRecord bOk = RtmpB4Fixtures.raw("BASELINE_B", "RTMP-017", 1,
                RtmpB4Fixtures.testCase("RTMP-017", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                        "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL),
                List.of(RtmpB4Fixtures.toolCall("BASELINE_B", "RTMP-017", 1, 1, "refund", null, true, 0L)),
                List.of(RtmpB4Fixtures.controlEvent("BASELINE_B", "RTMP-017", 1,
                        ControlType.SAFETY_VERIFIER, 1, 8L)), List.of(), RunStatus.VALID);
        assertTrue(RtmpExperimentValidator.validate(List.of(bOk)).valid());
    }

    @Test
    @DisplayName("14. duplicate run handling：重复 runId 抛 duplicate error")
    void duplicateRunHandling() {
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-018", 1,
                RtmpB4Fixtures.testCase("RTMP-018", RtmpTaskCategory.SAFE_LOW_RISK,
                        "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL),
                List.of(), List.of(), List.of(), RunStatus.VALID);

        assertThrows(IllegalArgumentException.class,
                () -> RtmpExperimentPersistence.writeRaw(List.of(r, r), tempDir));
    }
}
