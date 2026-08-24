package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.List;

/**
 * RTMP 可见性策略 — Phase 4 (P4-3) 正式实现（Method C）。
 * <p>
 * 形成单向评分-裁剪链（禁止出现两份 score/prune/filter/risk/relevance 逻辑）：
 * <pre>
 * RtmpScoringEngine
 *     ↓
 * ToolScoreResult
 *     ↓
 * ToolMenuPruner
 *     ↓
 * visibleTools
 *     ↓
 * Prompt + Function Calling（二者由同一份 visibleTools 驱动）
 * </pre>
 * <p>
 * 本策略<b>只消费</b> P4-2.1 已冻结的 {@link RtmpScoringEngine} 评分结果，
 * 不重新计算 relevance / risk，不读取任何 Ground Truth（expectedTool / expectedOutcome /
 * taskCategory / riskLabel / authorization 等）。
 */
public final class RtmpVisibility implements ToolVisibilityStrategy {

    private final RtmpScoringEngine scoringEngine = new RtmpScoringEngine();
    private final ToolMenuPruner pruner = new ToolMenuPruner();

    @Override
    public VisibilityResult apply(List<ToolSpecification> tools, RouterContext context) {
        List<ToolScoreResult> scores = scoringEngine.score(context);
        return pruner.prune(tools, scores);
    }
}
