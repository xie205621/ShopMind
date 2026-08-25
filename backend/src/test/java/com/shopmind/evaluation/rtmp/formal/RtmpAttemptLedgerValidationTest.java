package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5-R6.1 问题 3：Attempt Ledger corrupted-state 校验（fail-closed）。
 * <p>
 * 分两层证明 recovery journal 对坏状态 fail-closed：
 * <ul>
 *   <li><b>结构校验</b>（{@code load()} 内 {@code parse()}）：eventType / attempt / status 语义，
 *       非法即抛 {@link IllegalStateException}；</li>
 *   <li><b>跨事件 + identity 校验</b>（{@link RtmpAttemptLedgerStore#validate}）：
 *       experimentId / runId / caseId / condition / repetition 属于当前 plan、重复 STARTED/COMPLETED、
 *       STARTED(2) 缺 STARTED(1)、COMPLETED(n) 缺 STARTED(n)，返回非空错误列表。</li>
 * </ul>
 * 这些测试不把数量做大，而是确保：任何不完整的、未知的、或结构非法的 ledger 都不能让
 * formal runner 静默继续。
 */
class RtmpAttemptLedgerValidationTest {

    @TempDir
    Path tempDir;

    private static final RtmpEvaluationDataset DATASET = RtmpDatasetLoader.load();
    private static final String EXP = "RTMP-EXP01";

    // ============================================================
    //  Layer 1 — 结构校验（load → parse → validateEvent，抛异常）
    // ============================================================

    @Test
    @DisplayName("invalid eventType → reject")
    void invalidEventType_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        writeRawLine(LEDGER_VERSION, EXP, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), 1, "BOGUS", null);
        assertThrows(IllegalStateException.class, () -> RtmpAttemptLedgerStore.load(ledgerFile()));
    }

    @Test
    @DisplayName("attempt ∉ {1,2} → reject")
    void attemptOutOfRange_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        writeRawLine(LEDGER_VERSION, EXP, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), 3, RtmpAttemptLedgerEvent.STARTED, null);
        assertThrows(IllegalStateException.class, () -> RtmpAttemptLedgerStore.load(ledgerFile()));
    }

    @Test
    @DisplayName("STARTED 携带 status → reject")
    void startedWithStatus_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        writeRawLine(LEDGER_VERSION, EXP, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), 1, RtmpAttemptLedgerEvent.STARTED, "VALID");
        assertThrows(IllegalStateException.class, () -> RtmpAttemptLedgerStore.load(ledgerFile()));
    }

    @Test
    @DisplayName("COMPLETED 无合法 terminal status → reject")
    void completedWithoutTerminalStatus_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        writeRawLine(LEDGER_VERSION, EXP, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), 1, RtmpAttemptLedgerEvent.COMPLETED, "WEIRD");
        assertThrows(IllegalStateException.class, () -> RtmpAttemptLedgerStore.load(ledgerFile()));
    }

    @Test
    @DisplayName("错误 schemaVersion → reject")
    void wrongSchemaVersion_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        writeRawLine("rtmp-attempt-ledger-v999", EXP, unit.runId(), unit.caseId(), unit.condition(),
                unit.repetition(), 1, RtmpAttemptLedgerEvent.STARTED, null);
        assertThrows(IllegalStateException.class, () -> RtmpAttemptLedgerStore.load(ledgerFile()));
    }

    // ============================================================
    //  Layer 2 — identity 校验（validate → 非空错误列表）
    // ============================================================

    @Test
    @DisplayName("wrong experimentId → reject")
    void wrongExperimentId_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                event("WRONG-EXP", unit.runId(), unit.caseId(), unit.condition(),
                        unit.repetition(), 1, RtmpAttemptLedgerEvent.STARTED, null));
        assertFalse(validateErrors(events).isEmpty(), "wrong experimentId 必须被拒绝");
    }

    @Test
    @DisplayName("unknown runId → reject")
    void unknownRunId_reject() {
        List<RtmpAttemptLedgerEvent> events = List.of(
                event(EXP, "RTMP-EXP01_UNKNOWN_CASE_BASELINE_A_1", "UNKNOWN_CASE",
                        "BASELINE_A", 1, 1, RtmpAttemptLedgerEvent.STARTED, null));
        assertFalse(validateErrors(events).isEmpty(), "unknown runId 必须被拒绝");
    }

    @Test
    @DisplayName("wrong caseId → reject")
    void wrongCaseId_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                event(EXP, unit.runId(), "WRONG_CASE", unit.condition(),
                        unit.repetition(), 1, RtmpAttemptLedgerEvent.STARTED, null));
        assertFalse(validateErrors(events).isEmpty(), "wrong caseId 必须被拒绝");
    }

    @Test
    @DisplayName("wrong condition → reject")
    void wrongCondition_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                event(EXP, unit.runId(), unit.caseId(), "WRONG_CONDITION",
                        unit.repetition(), 1, RtmpAttemptLedgerEvent.STARTED, null));
        assertFalse(validateErrors(events).isEmpty(), "wrong condition 必须被拒绝");
    }

    @Test
    @DisplayName("wrong repetition → reject")
    void wrongRepetition_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        int wrongRepetition = unit.repetition() == 1 ? 2 : 1;
        List<RtmpAttemptLedgerEvent> events = List.of(
                event(EXP, unit.runId(), unit.caseId(), unit.condition(),
                        wrongRepetition, 1, RtmpAttemptLedgerEvent.STARTED, null));
        assertFalse(validateErrors(events).isEmpty(), "wrong repetition 必须被拒绝");
    }

    // ============================================================
    //  Layer 2 — 跨事件校验（validate → 非空错误列表）
    // ============================================================

    @Test
    @DisplayName("duplicate STARTED(1) → reject")
    void duplicateStarted_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                started(unit, 1), started(unit, 1));
        assertFalse(validateErrors(events).isEmpty(), "重复 STARTED(1) 必须被拒绝");
    }

    @Test
    @DisplayName("STARTED(2) without STARTED(1) → reject")
    void started2WithoutStarted1_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(started(unit, 2));
        assertFalse(validateErrors(events).isEmpty(), "STARTED(2) 缺 STARTED(1) 必须被拒绝");
    }

    @Test
    @DisplayName("COMPLETED(1, VALID) without STARTED(1) → reject")
    void completed1WithoutStarted1_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                completed(unit, 1, RunStatus.VALID));
        assertFalse(validateErrors(events).isEmpty(), "COMPLETED(1) 缺 STARTED(1) 必须被拒绝");
    }

    @Test
    @DisplayName("COMPLETED(2, VALID) without STARTED(2) → reject")
    void completed2WithoutStarted2_reject() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                started(unit, 1),
                completed(unit, 2, RunStatus.VALID));
        assertFalse(validateErrors(events).isEmpty(), "COMPLETED(2) 缺 STARTED(2) 必须被拒绝");
    }

    // ============================================================
    //  Positive controls — 合法 ledger 不得被误拒绝
    // ============================================================

    @Test
    @DisplayName("完整合法 attempt ledger → accept")
    void validLedger_accept() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(
                started(unit, 1),
                completed(unit, 1, RunStatus.RETRYABLE_FAILURE),
                started(unit, 2),
                completed(unit, 2, RunStatus.VALID));
        assertTrue(validateErrors(events).isEmpty(), "完整合法 ledger 必须被接受");
    }

    @Test
    @DisplayName("仅 STARTED(1)（attempt 中断未完成）→ accept")
    void startedOnly_intermediate_accept() {
        RtmpFormalExperimentPlan.Unit unit = unit();
        List<RtmpAttemptLedgerEvent> events = List.of(started(unit, 1));
        assertTrue(validateErrors(events).isEmpty(), "STARTED(1) 未完成是合法中间态，不得被拒绝");
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static final String LEDGER_VERSION = RtmpAttemptLedgerStore.LEDGER_SCHEMA_VERSION;

    private RtmpFormalExperimentPlan.Unit unit() {
        return buildPlan().units().get(0);
    }

    private static RtmpFormalExperimentPlan.Plan buildPlan() {
        return RtmpFormalExperimentPlan.build(EXP, DATASET);
    }

    private Path ledgerFile() {
        return RtmpAttemptLedgerStore.ledgerFile(tempDir, EXP);
    }

    /** 构造任意字段的事件（绕过工厂，用于 identity/结构非法测试）。 */
    private static RtmpAttemptLedgerEvent event(String experimentId, String runId,
                                                String caseId, String condition, int repetition,
                                                int attempt, String eventType, String status) {
        return new RtmpAttemptLedgerEvent(LEDGER_VERSION, experimentId, runId, caseId, condition,
                repetition, attempt, eventType, status, Instant.now().toString());
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

    private List<String> validateErrors(List<RtmpAttemptLedgerEvent> events) {
        return RtmpAttemptLedgerStore.validate(events, EXP, buildPlan());
    }

    /** 将单行 raw JSON 直接写入 ledger 文件（模拟坏 ledger / 结构非法行）。 */
    private void writeRawLine(String schemaVersion, String experimentId, String runId,
                              String caseId, String condition, int repetition,
                              int attempt, String eventType, String status) {
        RtmpAttemptLedgerEvent e = new RtmpAttemptLedgerEvent(
                schemaVersion, experimentId, runId, caseId, condition, repetition,
                attempt, eventType, status, Instant.now().toString());
        String line = RtmpExperimentPersistence.toJsonLine(e) + "\n";
        try {
            Files.createDirectories(tempDir);
            Files.writeString(ledgerFile(), line, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
