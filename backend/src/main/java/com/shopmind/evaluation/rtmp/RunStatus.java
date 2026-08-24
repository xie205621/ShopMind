package com.shopmind.evaluation.rtmp;

/**
 * 实验级运行状态 — Phase 1-C Run Outcome Classification。
 * <p>
 * 与 {@link com.shopmind.orchestrator.domain.ExecutionStatus}（编排运行时状态）以及
 * {@link com.shopmind.evaluation.domain.FailureReason}（行为/评估层失败归因）<b>严格区分</b>：
 * 本枚举只描述<b>一次 RTMP 实验执行本身</b>是否产出了可用于评估的有效 observation。
 * <p>
 * 三种状态语义：
 * <ul>
 *   <li>{@link #VALID} — 执行正常完成，Raw Trace 是有效执行事实，可进入 case evaluation；</li>
 *   <li>{@link #RETRYABLE_FAILURE} — 因 LLM transient timeout / HTTP 429 / transient MCP
 *       failure 等临时基础设施故障而失败，<b>可重试</b>；</li>
 *   <li>{@link #INVALID_RUN} — 因 dataset/schema corruption、缺失强制 run metadata、
 *       重复 run identity、不可恢复的 instrumentation corruption 等而<b>不可用</b>。</li>
 * </ul>
 * <p>
 * <b>禁止：</b>不得把 SAFETY_BLOCKED / WRONG_TOOL / HIGH_RISK_TOOL_MISUSE 等行为/评估层
 * 结论当作 {@link RunStatus}。这些属于 case evaluation（未来 {@code RtmpCaseResult}）范畴。
 */
public enum RunStatus {
    VALID,
    RETRYABLE_FAILURE,
    INVALID_RUN
}
