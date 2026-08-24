package com.shopmind.workflow.domain;

/**
 * Canonical Run Identity — Phase 1 Evaluation/Instrumentation Foundation。
 * <p>
 * 定义一次实验执行的<b>全局唯一身份</b>，格式：
 *
 * <pre>{@code
 * RTMP-EXP01_{condition}_{caseId}_{repetition}
 * }</pre>
 *
 * 例如：{@code RTMP-EXP01_BASELINE_A_RTMP-001_1}。
 * <p>
 * <b>关键约束：</b>{@code memoryId == runId}。任何组件<b>禁止</b>自行生成另一套
 * memory identity，避免多 repetition / 多 condition 实验中的记忆串扰。
 * <p>
 * 分层关系：
 *
 * <pre>
 * experimentId → runId → traceId
 *                   └→ memoryId (== runId)
 * </pre>
 *
 * 其中 traceId 是技术 tracing ID（UUID），与 runId 不等价。
 * <p>
 * 使用 Java record 确保不可变语义——一次实验执行的 identity 在创建后不应改变。
 *
 * @param experimentId 实验全局标识，如 {@code RTMP-EXP01}
 * @param condition    实验条件，如 {@code BASELINE_A} / {@code BASELINE_B} / {@code METHOD_C}
 * @param caseId       用例标识，如 {@code RTMP-001}
 * @param repetition   repetition 序号（Mock 固定 1；Real 为 1..3）
 */
public record RunIdentity(
        String experimentId,
        String condition,
        String caseId,
        int repetition
) {

    /**
     * 紧凑构造器：校验必填字段，禁止生成残缺的 run identity。
     */
    public RunIdentity {
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("experimentId must not be blank");
        }
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("condition must not be blank");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition must be >= 1, got: " + repetition);
        }
    }

    /**
     * 生成 canonical run_id，格式：{@code {experimentId}_{condition}_{caseId}_{repetition}}。
     */
    public String runId() {
        return experimentId + "_" + condition + "_" + caseId + "_" + repetition;
    }

    /**
     * 返回本次执行使用的 memory identity。
     * <p>
     * <b>强制约束：</b>memoryId 必须等于 runId。
     */
    public String memoryId() {
        return runId();
    }
}