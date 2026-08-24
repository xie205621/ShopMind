package com.shopmind.workflow.domain;

import com.shopmind.experiment.ControlType;

import java.math.BigDecimal;

/**
 * 一次 control invocation 的 runtime observation — Phase 5-B3。
 * <p>
 * 记录 Baseline B（Safety Verifier）或 Method C（RTMP Router）单次调用的观测事实，
 * 属于 Runtime Observation（不是 Evaluation Result）。一次 run 可包含多个有序
 * {@link ControlOverheadEvent}，禁止用 run-level 单值覆盖历史事实。
 * <p>
 * <b>token/cost 语义（冻结）：</b>RTMP Router 为 deterministic rule-based、Verifier 为
 * local deterministic，二者均不调用 LLM/API，因此 {@code promptTokens} / {@code completionTokens} /
 * {@code totalTokens} / {@code cost} 当前<b>恒为 null</b>，不得伪造或估算。
 *
 * @param runId            canonical run_id（{@link RunIdentity#runId()}，legacy 可为 null）
 * @param condition        实验条件名（BASELINE_A / BASELINE_B / METHOD_C）
 * @param caseId           关联的 RTMP caseId（无 RunIdentity 时为 null）
 * @param repetition       repetition 序号（无 RunIdentity 时为 0）
 * @param controlType      SAFETY_VERIFIER / RTMP_ROUTER
 * @param iteration        所属迭代号（Router=LLM 迭代；Verifier=tool-call 序号，从 1 开始）
 * @param latencyMs        本次 control invocation 耗时（毫秒）
 * @param promptTokens     输入 token（unavailable 时为 null）
 * @param completionTokens 输出 token（unavailable 时为 null）
 * @param totalTokens      总 token（unavailable 时为 null）
 * @param cost             API/model cost（unavailable 时为 null）
 */
public record ControlOverheadEvent(
        String runId,
        String condition,
        String caseId,
        int repetition,
        ControlType controlType,
        int iteration,
        long latencyMs,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        BigDecimal cost
) {
}
