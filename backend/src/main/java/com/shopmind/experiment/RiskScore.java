package com.shopmind.experiment;

/**
 * Risk 特征映射结果 — Phase 4 (P4-2)。
 * <p>
 * 两个风险来源，最终有效风险取两者最大值：
 * <pre>
 * EffectiveRiskScore = max(StaticToolRiskScore, RuntimeContextRiskScore)
 * </pre>
 * 范围 0.0 ~ 1.0。
 *
 * @param staticRiskScore        工具级静态风险（来自 P4-1 ToolStaticRiskCatalog）
 * @param runtimeContextRiskScore 运行时上下文风险（仅 query/history/intent，无 GT）
 */
public record RiskScore(
        double staticRiskScore,
        double runtimeContextRiskScore
) {

    /** 有效风险：两者最大值。 */
    public double effective() {
        return Math.max(staticRiskScore, runtimeContextRiskScore);
    }
}
