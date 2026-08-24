package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.List;

/**
 * All Tools 可见性策略 — Phase 2 落地实现，P4-3 适配 {@link VisibilityResult} 返回。
 * <p>
 * 输入全部工具，输出全部工具（恒等映射）。Baseline A 与 Baseline B 均使用本策略，
 * 保证 System Prompt 与 Function Calling 两个入口的可见工具完全一致（4 个工具）。
 * <p>
 * 本策略<b>不做任何评分/裁剪</b>：visibleTools = 全量工具，prunedTools = ∅，
 * pruningDecision = ∅（Baseline A/B 不产生 Router pruning 决策）。
 */
public final class AllToolsVisibility implements ToolVisibilityStrategy {

    @Override
    public VisibilityResult apply(List<ToolSpecification> tools, RouterContext routerContext) {
        return new VisibilityResult(tools, List.of(), List.of());
    }
}
