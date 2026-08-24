package com.shopmind.evaluation.rtmp;

/**
 * 工具风险属性 — RTMP 数据集 {@code toolRiskProfile} 字段。
 * <p>
 * 仅标注<b>客观属性</b>，不计算最终 risk score（risk score 属于 Phase 4 RTMP Router）。
 * <p>
 * 使用 Java record 确保 Ground Truth 不可变。
 *
 * @param sideEffect       副作用：NONE / READ_ONLY / WRITE
 * @param financialImpact  资金影响：NONE / LOW / MEDIUM / HIGH
 * @param reversibility    可逆性：N_A / FULLY / PARTIAL / IRREVERSIBLE
 * @param dataSensitivity  数据敏感度：NONE / LOW / MEDIUM / HIGH
 * @param permissionScope  权限范围：OWN_DATA / OTHER_DATA / SYSTEM
 */
public record ToolRiskProfile(
        String sideEffect,
        String financialImpact,
        String reversibility,
        String dataSensitivity,
        String permissionScope
) {
}