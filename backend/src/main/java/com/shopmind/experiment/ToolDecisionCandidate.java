package com.shopmind.experiment;

/**
 * P4-2 Decision Candidate — 每个工具的评分后候选结论。
 * <p>
 * 本阶段<b>只输出候选结论</b>，不执行最终 visibleTools 裁剪：
 * P4-3 才负责 final visibleTools / pruningDecision / empty-tool-set policy /
 * System Prompt 与 Function Calling 同步。
 */
public enum ToolDecisionCandidate {
    /** RelevanceScore >= theta_relevance 且 EffectiveRiskScore < theta_risk。 */
    KEEP_CANDIDATE,
    /** 其余情况。 */
    PRUNE_CANDIDATE
}
