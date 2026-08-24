package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具菜单裁剪器 — Phase 4 (P4-3)。
 * <p>
 * 消费 P4-2.1 已冻结的 {@link ToolScoreResult}（含 {@link ToolDecisionCandidate}），
 * 产生最终 {@code visibleTools} / {@code prunedTools}。这是 P4-3 唯一允许的
 * candidate → visible 落实点，<b>不得</b>在此重新计算 relevance / risk。
 * <p>
 * <b>Final visibility rule（冻结）：</b>
 * <pre>
 * KEEP_CANDIDATE  → visible
 * PRUNE_CANDIDATE → hidden
 * </pre>
 * 即 {@code visibleTools = { tool | candidate(tool) == KEEP_CANDIDATE }}。
 * <p>
 * <b>Empty-tool-set policy：</b>若 visibleTools = ∅，则保持 ∅，不做任何 fallback 恢复
 * （不恢复 refund / queryOrder / “最低风险工具”）。empty-tool-set 本身就是 RTMP 的有效
 * 决策结果，系统进入 No-Tool Answer / Clarification 路径。
 * <p>
 * <b>Multi-tool 约束：</b>每个工具独立评分，禁止 Top-1；多个 KEEP 全部保留。
 */
public final class ToolMenuPruner {

    /**
     * 依据评分结论裁剪工具。
     *
     * @param tools  候选工具（来自 {@code discoverWorkflowTools()}）
     * @param scores 每个工具的评分结论（与 {@code tools} 按 toolName 对齐）
     * @return 可见工具 + 被裁剪工具 + 原始评分决策
     */
    public VisibilityResult prune(List<ToolSpecification> tools, List<ToolScoreResult> scores) {
        Set<String> keepNames = scores == null
                ? Set.of()
                : scores.stream()
                        .filter(ToolScoreResult::keepCandidate)
                        .map(ToolScoreResult::toolName)
                        .collect(Collectors.toSet());

        List<ToolSpecification> visible = new ArrayList<>();
        List<ToolSpecification> pruned = new ArrayList<>();
        if (tools != null) {
            for (ToolSpecification tool : tools) {
                if (keepNames.contains(tool.getToolName())) {
                    visible.add(tool);
                } else {
                    pruned.add(tool);
                }
            }
        }

        return new VisibilityResult(visible, pruned, scores);
    }
}
