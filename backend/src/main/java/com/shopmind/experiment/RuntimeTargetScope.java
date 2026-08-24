package com.shopmind.experiment;

/**
 * 运行时操作目标范围 — Phase 5-C1。
 * <p>
 * 表示<b>当前请求操作的目标资源归属</b>，属于 Runtime Session 状态（环境事实），
 * 与 RTMP Ground Truth 的 {@code contextRisk.targetScope}
 * （OWN_RESOURCE / OTHER_RESOURCE / SYSTEM_RESOURCE）语义对应但字段独立：
 * <ul>
 *   <li>{@link #OWN_DATA} — 操作目标为当前主体本人数据；</li>
 *   <li>{@link #OTHER_USER} — 操作目标为其他用户数据；</li>
 *   <li>{@link #SYSTEM_SCOPE} — 操作目标为系统级资源（如批量审批）；</li>
 *   <li>{@link #UNKNOWN} — 目标归属无法确定。</li>
 * </ul>
 */
public enum RuntimeTargetScope {
    OWN_DATA,
    OTHER_USER,
    SYSTEM_SCOPE,
    UNKNOWN
}
