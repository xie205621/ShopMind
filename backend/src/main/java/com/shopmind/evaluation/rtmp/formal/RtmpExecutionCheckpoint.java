package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;

import java.time.Instant;

/**
 * 正式实验的 execution checkpoint — Phase 5-R6。
 * <p>
 * checkpoint 是 <b>execution recovery artifact</b>，不是 canonical final source of truth。
 * 每个 {@code canonical unit = caseId × condition × repetition} 只产生一条 completed checkpoint，
 * 且只能恢复 canonical units，<b>不得创造新的 statistical units</b>。
 * <p>
 * identity 字段（{@code runId}/{@code caseId}/{@code condition}/{@code repetition}/
 * {@code conditionOrderIndex}）必须与 frozen canonical plan 一致；{@code attempts} 仅允许 1 或 2；
 * {@code resumeCount} 是 execution audit metadata（§13），不进入 H1–H5，不改变 canonical unit count。
 *
 * @param schemaVersion       checkpoint schema 版本（{@code rtmp-checkpoint-v1}）
 * @param experimentId        实验全局标识（如 RTMP-EXP01）
 * @param runId               canonical run_id
 * @param caseId              用例标识
 * @param condition           实验条件（BASELINE_A / BASELINE_B / METHOD_C）
 * @param repetition          repetition 序号
 * @param conditionOrderIndex 本 case/repetition 内 condition 的执行顺序索引（0/1/2）
 * @param status              最终 canonical RunStatus 名称
 * @param attempts            本次 unit 实际 attempt 次数（1 或 2）
 * @param completed           是否为 completed canonical unit（checkpoint 恒为 true）
 * @param resumeCount         恢复代际（0=首次执行；每次恢复 +1）
 * @param record              该 unit 的 canonical Raw 记录（事实源）
 * @param checkpointedAt      checkpoint 落盘时间
 */
public record RtmpExecutionCheckpoint(
        String schemaVersion,
        String experimentId,
        String runId,
        String caseId,
        String condition,
        int repetition,
        int conditionOrderIndex,
        String status,
        int attempts,
        boolean completed,
        int resumeCount,
        RtmpRawRecord record,
        String checkpointedAt
) {

    /**
     * 从一个已完成 canonical unit 的 Raw record 构建 completed checkpoint。
     *
     * @param record      canonical Raw record（status 已为最终状态）
     * @param attempts    实际 attempt 次数（1 或 2）
     * @param resumeCount 当前恢复代际
     */
    public static RtmpExecutionCheckpoint of(RtmpRawRecord record, int attempts, int resumeCount) {
        return new RtmpExecutionCheckpoint(
                RtmpCheckpointStore.CHECKPOINT_SCHEMA_VERSION,
                record.experimentId(),
                record.runId(),
                record.caseId(),
                record.condition(),
                record.repetition(),
                record.conditionOrderIndex(),
                record.status() != null ? record.status().name() : null,
                attempts,
                true,
                resumeCount,
                record,
                Instant.now().toString());
    }
}
