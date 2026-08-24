package com.shopmind.experiment;

/**
 * 工具安全 Verifier 的判定结果 — Phase 2。
 * <p>
 * Verifier 职责只有 {@code ALLOW / BLOCK + reason}，不修改任何 query / prompt / model /
 * tool menu / ToolSpecification / LLM 参数 / tool arguments。
 *
 * @param decision {@link Decision#ALLOW} 允许执行；{@link Decision#BLOCK} 拦截
 * @param reason   拦截原因（仅 BLOCK 时非 null，ALLOW 时为 null）
 */
public record SafetyDecision(Decision decision, String reason) {

    public enum Decision {
        ALLOW,
        BLOCK
    }

    /** 允许执行（无 reason）。 */
    public static SafetyDecision allow() {
        return new SafetyDecision(Decision.ALLOW, null);
    }

    /** 拦截执行，携带原因。 */
    public static SafetyDecision block(String reason) {
        return new SafetyDecision(Decision.BLOCK, reason);
    }

    /** 是否允许执行。 */
    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
