package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.List;

/**
 * 工具可见性裁剪结果 — Phase 4 (P4-3)。
 * <p>
 * 这是 {@link ToolVisibilityStrategy} 的正式输出载体，把 P4-2.1 冻结的
 * {@link ToolScoreResult} → {@link ToolDecisionCandidate}（KEEP_CANDIDATE / PRUNE_CANDIDATE）
 * 落实为<b>单一事实源</b>：
 * <ul>
 *   <li>{@code visibleTools} — 对 LLM 可见的工具（System Prompt 与 Function Calling 双入口一致）</li>
 *   <li>{@code prunedTools} — 被裁剪的工具</li>
 *   <li>{@code pruningDecision} — canonical scoring decision（复用 {@link ToolScoreResult}，
 *       不引入第二套 scoring DTO），供 instrumentation 直接序列化/落盘</li>
 * </ul>
 * <p>
 * <b>关键语义区分：</b>{@code candidate}（评分结论）与 {@code visibleTools}（最终可见集合）
 * 是两个不同概念；本记录只负责承载最终可见集合，不重新计算 risk/relevance。
 *
 * @param visibleTools    最终可见工具（有序）
 * @param prunedTools     被裁剪工具（有序）
 * @param pruningDecision 每个工具的评分决策（KEEP_CANDIDATE / PRUNE_CANDIDATE）
 */
public record VisibilityResult(
        List<ToolSpecification> visibleTools,
        List<ToolSpecification> prunedTools,
        List<ToolScoreResult> pruningDecision
) {

    public VisibilityResult {
        visibleTools = visibleTools != null ? List.copyOf(visibleTools) : List.of();
        prunedTools = prunedTools != null ? List.copyOf(prunedTools) : List.of();
        pruningDecision = pruningDecision != null ? List.copyOf(pruningDecision) : List.of();
    }

    /** 裁剪前的工具总数（可见 + 被裁剪）。 */
    public int inputToolCount() {
        return visibleTools.size() + prunedTools.size();
    }
}
