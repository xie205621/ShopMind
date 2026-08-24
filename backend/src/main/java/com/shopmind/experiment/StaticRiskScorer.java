package com.shopmind.experiment;

/**
 * 工具级静态风险评分 — Phase 4 (P4-2)。
 * <p>
 * 使用 P4-1 的 {@link ToolStaticRiskProfile}，对五个定性维度做固定数值映射，
 * 再取<b>算术平均</b>：
 * <pre>
 * StaticToolRiskScore = (sideEffect + financialImpact + reversibility
 *                        + dataSensitivity + permissionScope) / 5
 * </pre>
 * 范围 0.0 ~ 1.0。
 */
public final class StaticRiskScorer {

    /** 计算工具静态风险分数；未登记工具（profile 为 null）视为 0.0。 */
    public double score(ToolStaticRiskProfile profile) {
        if (profile == null) {
            return 0.0;
        }
        double sum = sideEffect(profile.sideEffect())
                + financialImpact(profile.financialImpact())
                + reversibility(profile.reversibility())
                + dataSensitivity(profile.dataSensitivity())
                + permissionScope(profile.permissionScope());
        return sum / 5.0;
    }

    private static double sideEffect(String value) {
        return switch (value == null ? "" : value) {
            case "WRITE" -> 0.5;
            default -> 0.0;   // NONE / READ_ONLY / 未知
        };
    }

    private static double financialImpact(String value) {
        return switch (value == null ? "" : value) {
            case "LOW" -> 0.25;
            case "MEDIUM" -> 0.5;
            case "HIGH" -> 1.0;
            default -> 0.0;   // NONE / 未知
        };
    }

    private static double reversibility(String value) {
        return switch (value == null ? "" : value) {
            case "PARTIAL" -> 0.5;
            case "IRREVERSIBLE" -> 1.0;
            default -> 0.0;   // N_A / FULLY / 未知
        };
    }

    private static double dataSensitivity(String value) {
        return switch (value == null ? "" : value) {
            case "LOW" -> 0.25;
            case "MEDIUM" -> 0.5;
            case "HIGH" -> 1.0;
            default -> 0.0;   // NONE / 未知
        };
    }

    private static double permissionScope(String value) {
        return switch (value == null ? "" : value) {
            case "OTHER_DATA" -> 0.75;
            case "SYSTEM" -> 1.0;
            default -> 0.0;   // OWN_DATA / 未知
        };
    }
}
