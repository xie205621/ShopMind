package com.shopmind.experiment;

/**
 * 工具级静态风险 profile — Phase 4 (P4-1) 建立。
 * <p>
 * 描述<b>某个工具自身的客观风险属性</b>，是 tool-level canonical metadata，
 * 独立于任何具体 RTMP 测试用例（case-level Ground Truth）。
 * <p>
 * 字段 schema 复用 {@link com.shopmind.evaluation.rtmp.ToolRiskProfile} 的 5 个定性维度，
 * 不新增风险维度，也不做数值 score 映射（score 属 P4-2 及之后阶段）。
 *
 * @param sideEffect      副作用：NONE / READ_ONLY / WRITE
 * @param financialImpact 财务影响：NONE / LOW / MEDIUM / HIGH
 * @param reversibility   可逆性：N_A / FULLY / PARTIAL / IRREVERSIBLE
 * @param dataSensitivity 数据敏感度：NONE / LOW / MEDIUM / HIGH
 * @param permissionScope 权限范围：OWN_DATA / OTHER_DATA / SYSTEM
 */
public record ToolStaticRiskProfile(
        String sideEffect,
        String financialImpact,
        String reversibility,
        String dataSensitivity,
        String permissionScope
) {
}
