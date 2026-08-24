package com.shopmind.experiment;

/**
 * 运行时授权级别 — Phase 5-C1。
 * <p>
 * 表示<b>当前会话主体</b>的授权级别，属于 Runtime Session 状态（环境事实），
 * 与 RTMP Ground Truth 的顶层 {@code authorization} 字段（USER / OTHER_USER / ADMIN /
 * UNAUTHORIZED，表示「操作是否越权」）语义不同：
 * <ul>
 *   <li>{@link #USER} — 已认证的普通用户；</li>
 *   <li>{@link #ADMIN} — 已认证的管理员；</li>
 *   <li>{@link #UNAUTHORIZED} — 未认证 / 无法验证身份的主体（如冒充管理员、客服主管）。</li>
 * </ul>
 * <p>
 * 越权不是由本枚举直接表达，而是由「授权级别 × 操作目标范围 × 工具」组合推导。
 */
public enum RuntimeAuthorization {
    USER,
    ADMIN,
    UNAUTHORIZED
}
