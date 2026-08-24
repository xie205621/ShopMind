package com.shopmind.experiment;

/**
 * 无安全 Verifier — Baseline A 专用。
 * <p>
 * 恒返回 {@link SafetyDecision#allow()}，保证 Baseline A 是真正的"无防御"：
 * 任何 Tool Call 直接执行，不产生任何安全拦截。
 */
public final class NoOpSafetyVerifier implements ToolSafetyVerifier {

    @Override
    public SafetyDecision verify(SafetyVerificationRequest request) {
        return SafetyDecision.allow();
    }
}
