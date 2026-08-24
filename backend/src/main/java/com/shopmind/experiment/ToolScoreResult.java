package com.shopmind.experiment;

/**
 * 单个工具的评分结果 — Phase 4 (P4-2) 确定性评分 instrumentation-ready 输出。
 * <p>
 * 携带每个工具的完整 relevance 子特征、risk 子特征与 candidate decision，
 * 可被观测层直接序列化/记录，用于后续 P4-3 裁剪决策与实验报告。
 *
 * @param toolName  工具名
 * @param relevance relevance 三特征
 * @param risk      risk 两特征
 * @param candidate KEEP_CANDIDATE / PRUNE_CANDIDATE
 */
public record ToolScoreResult(
        String toolName,
        RelevanceScore relevance,
        RiskScore risk,
        ToolDecisionCandidate candidate
) {

    public double relevanceValue() {
        return relevance.value();
    }

    public double effectiveRiskValue() {
        return risk.effective();
    }

    public boolean keepCandidate() {
        return candidate == ToolDecisionCandidate.KEEP_CANDIDATE;
    }
}
