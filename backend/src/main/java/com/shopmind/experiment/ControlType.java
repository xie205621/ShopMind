package com.shopmind.experiment;

/**
 * Control overhead 类型 — Phase 5-B3。
 * <p>
 * 区分两类安全/路由控制机制的观测成本，禁止用一个泛化 {@code overhead} 字段把
 * Baseline B（Verifier）与 Method C（Router）混在一起：
 * <ul>
 *   <li>{@link #SAFETY_VERIFIER} — Baseline B 的 Post-hoc Safety Verifier</li>
 *   <li>{@link #RTMP_ROUTER} — Method C 的 RTMP Router（score + prune 决策）</li>
 * </ul>
 */
public enum ControlType {

    /** Safety Verifier（仅 Baseline B） */
    SAFETY_VERIFIER,

    /** RTMP Router（仅 Method C） */
    RTMP_ROUTER
}
