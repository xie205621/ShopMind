package com.shopmind.experiment;

import java.util.List;

/**
 * RTMP 确定性评分引擎 — Phase 4 (P4-2)。
 * <p>
 * 对 {@link RouterContext} 中的每个工具<b>独立评分</b>（禁止 Top-1，允许多个工具同时 KEEP_CANDIDATE），
 * 输出 instrumentation-ready 的 {@link ToolScoreResult} 列表。
 * <p>
 * 评分公式：
 * <pre>
 * RelevanceScore(tool)   = max(intentScore, lexicalScore, descriptionCompatibilityScore)
 * EffectiveRiskScore(tool) = max(StaticToolRiskScore(tool), RuntimeContextRiskScore(context))
 *
 * KEEP_CANDIDATE(tool)  = RelevanceScore >= theta_relevance AND EffectiveRiskScore < theta_risk
 * PRUNE_CANDIDATE(tool) = 其余情况
 * </pre>
 * <p>
 * 本阶段<b>只输出候选结论</b>，不执行 final visibleTools / pruning / empty-tool-set policy /
 * System Prompt 与 Function Calling 同步（这些属 P4-3）。
 */
public final class RtmpScoringEngine {

    private final RelevanceScorer relevanceScorer = new RelevanceScorer();
    private final StaticRiskScorer staticRiskScorer = new StaticRiskScorer();
    private final RuntimeContextRiskScorer runtimeContextRiskScorer = new RuntimeContextRiskScorer();

    /** 对 context 中的全部工具独立评分，返回候选结论列表。 */
    public List<ToolScoreResult> score(RouterContext context) {
        return context.toolMetadata().stream()
                .map(tool -> scoreTool(tool, context))
                .toList();
    }

    private ToolScoreResult scoreTool(ToolRuntimeMetadata tool, RouterContext context) {
        RelevanceScore relevance = relevanceScorer.score(tool, context);
        double staticRisk = staticRiskScorer.score(tool.staticRisk());
        double runtimeRisk = runtimeContextRiskScorer.score(context, tool.toolName());
        RiskScore risk = new RiskScore(staticRisk, runtimeRisk);
        ToolDecisionCandidate candidate = decide(relevance.value(), risk.effective());
        return new ToolScoreResult(tool.toolName(), relevance, risk, candidate);
    }

    private static ToolDecisionCandidate decide(double relevance, double effectiveRisk) {
        if (relevance >= RtmpScoringConfig.THETA_RELEVANCE
                && effectiveRisk < RtmpScoringConfig.THETA_RISK) {
            return ToolDecisionCandidate.KEEP_CANDIDATE;
        }
        return ToolDecisionCandidate.PRUNE_CANDIDATE;
    }
}
