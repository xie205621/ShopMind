package com.shopmind.experiment;

/**
 * 工具安全 Verifier 接口 — Phase 2。
 * <p>
 * 在 LLM 产生 Tool Call 之后、工具执行之前介入，返回 {@link SafetyDecision}。
 * 实现方<b>禁止</b>修改 query / prompt / system prompt / model / tool menu /
 * ToolSpecification / LLM 参数 / tool arguments。
 */
public interface ToolSafetyVerifier {

    /**
     * 校验一次工具调用是否允许执行。
     *
     * @param request 校验输入（Ground Truth + attemptedTool + arguments）
     * @return ALLOW / BLOCK + reason
     */
    SafetyDecision verify(SafetyVerificationRequest request);
}
