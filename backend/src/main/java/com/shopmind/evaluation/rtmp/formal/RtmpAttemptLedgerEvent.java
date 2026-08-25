package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.RunStatus;

import java.time.Instant;

/**
 * Crash-safe attempt execution provenance event — Phase 5-R6.1。
 * <p>
 * 与 {@link RtmpExecutionCheckpoint}（canonical final completion）<b>严格分离</b>：
 * 本 record 只描述<b>单个 attempt</b>的生命周期事件，用于在 process interruption 后
 * 精确重建“哪些 attempt 已被真实消耗”，从而保证 run-level max retry = 1 在任意
 * crash/recovery 路径下都不会被突破。
 * <p>
 * 事件类型：
 * <ul>
 *   <li>{@link #STARTED} — attempt 真实 invocation <b>开始前</b>持久化；</li>
 *   <li>{@link #COMPLETED} — attempt 真实 invocation <b>完成后</b>持久化（携带 {@code status}）。</li>
 * </ul>
 * <p>
 * {@code attempt} 取值恒为 1 或 2；retry attempt <b>不</b>产生新的 statistical unit，
 * 也不改变 runId / memoryId。
 *
 * @param schemaVersion ledger schema 版本（{@code rtmp-attempt-ledger-v1}）
 * @param experimentId  实验全局标识
 * @param runId         canonical run_id
 * @param caseId        用例标识
 * @param condition     实验条件
 * @param repetition    repetition 序号
 * @param attempt       attempt 序号（1 或 2）
 * @param eventType     {@code STARTED} 或 {@code COMPLETED}
 * @param status        attempt 完成时的 {@link RunStatus} 名称（STARTED 为 null）
 * @param recordedAt    事件落盘时间
 */
public record RtmpAttemptLedgerEvent(
        String schemaVersion,
        String experimentId,
        String runId,
        String caseId,
        String condition,
        int repetition,
        int attempt,
        String eventType,
        String status,
        String recordedAt
) {

    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";

    /** attempt 开始前持久化 {@code STARTED}。 */
    public static RtmpAttemptLedgerEvent started(String experimentId, String runId,
                                                 String caseId, String condition,
                                                 int repetition, int attempt) {
        return new RtmpAttemptLedgerEvent(
                RtmpAttemptLedgerStore.LEDGER_SCHEMA_VERSION,
                experimentId, runId, caseId, condition, repetition, attempt,
                STARTED, null, Instant.now().toString());
    }

    /** attempt 完成后持久化 {@code COMPLETED + status}。 */
    public static RtmpAttemptLedgerEvent completed(String experimentId, String runId,
                                                   String caseId, String condition,
                                                   int repetition, int attempt,
                                                   RunStatus status) {
        return new RtmpAttemptLedgerEvent(
                RtmpAttemptLedgerStore.LEDGER_SCHEMA_VERSION,
                experimentId, runId, caseId, condition, repetition, attempt,
                COMPLETED, status != null ? status.name() : null, Instant.now().toString());
    }
}
