package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.List;

/**
 * 工具可见性策略 — Phase 2 显式抽象，P4-3 起返回 {@link VisibilityResult}。
 * <p>
 * 输入 MCP Engine 发现的全量工具 + {@link RouterContext}（Router 的合法运行时输入），
 * 输出本次实验条件对 LLM 可见的工具子集与被裁剪工具（以及 canonical pruningDecision）。
 * <ul>
 *   <li>{@link AllToolsVisibility} — Baseline A/B：输入全量 → 输出全量（恒等映射）</li>
 *   <li>{@link RtmpVisibility} — Method C：评分 → 裁剪（KEEP→visible，PRUNE→hidden）</li>
 * </ul>
 * <p>
 * <b>双入口一致性约束：</b>System Prompt 的【可用工具】与 Function Calling 的 tools 参数
 * 必须由<b>同一份</b> {@link VisibilityResult#visibleTools()} 驱动，禁止两个入口分别调用
 * 本策略进行二次计算。
 */
public interface ToolVisibilityStrategy {

    /**
     * 应用可见性策略。
     *
     * @param tools          MCP Engine 发现的全量工具
     * @param routerContext  Router 的合法运行时输入（Baseline A/B 忽略；Method C 用于评分）
     * @return 可见工具 + 被裁剪工具 + pruningDecision
     */
    VisibilityResult apply(List<ToolSpecification> tools, RouterContext routerContext);
}
