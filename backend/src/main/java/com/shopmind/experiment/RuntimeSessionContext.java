package com.shopmind.experiment;

/**
 * Runtime Session Context — Phase 5-C1。
 * <p>
 * 承载一次实验会话的<b>合法运行时环境事实</b>（不是实验 answer key），由
 * {@link RuntimeSessionContextProvider} 提供，供 Router（Method C）与
 * Post-hoc Verifier（Baseline B）共享同一份授权信息，从而修复 B/C 信息不对称（C0-C2）。
 * <p>
 * <b>严格隔离：</b>本 record 禁止携带任何 Ground Truth 字段（expectedTool /
 * expectedOutcome / taskCategory / riskLabel / adversarial / contextRisk / toolRiskProfile /
 * candidateTools / mockResponse）。它的语义是「会话主体是谁、其授权级别、操作目标范围」，
 * 与「期望结果」无关。
 *
 * @param authenticatedPrincipal 当前认证主体标识（未认证时可为 null）
 * @param runtimeAuthorization   当前主体的授权级别
 * @param runtimeTargetScope     操作目标资源归属
 */
public record RuntimeSessionContext(
        String authenticatedPrincipal,
        RuntimeAuthorization runtimeAuthorization,
        RuntimeTargetScope runtimeTargetScope
) {
}
