package com.shopmind.evaluation.rtmp;

import com.shopmind.workflow.domain.ExecutionTrace;

/**
 * 一次 RTMP 实验 run 的 outcome — Phase 1-C Run Outcome Classification + Raw plumbing。
 * <p>
 * 将<b>Raw 执行事实</b>（{@link ExecutionTrace}）与<b>实验级运行状态</b>（{@link RunStatus}）
 * 打包为一个不可变单元，作为 {@code Raw → case evaluation → experiment summary} 流水线的
 * 第一步输出。
 * <p>
 * <b>分层约束：</b>
 * <ul>
 *   <li>{@link ExecutionTrace} / {@link com.shopmind.workflow.domain.ToolCallEvent} =
 *       Runtime Observation（执行事实，Raw）；</li>
 *   <li>{@link RunStatus} = Run Outcome Classification（本 run 是否可用于评估）；</li>
 *   <li>case evaluation（未来 {@code RtmpCaseResult}）与 experiment summary 均<b>必须</b>
 *       基于 {@code trace}（Raw）推导，Summary 不得成为唯一数据源。</li>
 * </ul>
 *
 * @param trace  本次 run 的 canonical Raw {@link ExecutionTrace}（含有序 ToolCallEvents）
 * @param status 本次 run 的实验级运行状态
 */
public record RtmpRunOutcome(
        ExecutionTrace trace,
        RunStatus status
) {
}
