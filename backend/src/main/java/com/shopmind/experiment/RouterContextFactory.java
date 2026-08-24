package com.shopmind.experiment;

import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.orchestrator.domain.OrchestrationContext;

import java.util.List;
import java.util.Optional;

/**
 * RouterContext 工厂 — Phase 4 (P4-1)。
 * <p>
 * 从运行时 {@link OrchestrationContext} 与工具列表构建 {@link RouterContext}，
 * 严格隔离 RTMP Ground Truth（不读取 RtmpTestCase / contextRisk / case-level toolRiskProfile）。
 * <p>
 * intentConfidence / runtimeRequestType 当前无真实 runtime 来源，统一填 {@link Optional#empty()}。
 * runtimeAuthorization / runtimeTargetScope 来自 {@link RuntimeSessionContextProvider} 提供的
 * {@link RuntimeSessionContext}（真实运行时会话来源，非 GT）；无来源时保持 empty。
 */
public final class RouterContextFactory {

    /**
     * 构建 RouterContext（无 Runtime Session Context，legacy 场景）。
     *
     * @param ctx   运行时编排上下文（提供 userQuery / history / intent）
     * @param tools 工具列表（来自 {@code discoverWorkflowTools()}）
     */
    public RouterContext build(OrchestrationContext ctx, List<ToolSpecification> tools) {
        return build(ctx, tools, null);
    }

    /**
     * 构建 RouterContext（Phase 5-C1：注入 Runtime Session Context 的授权信号）。
     *
     * @param ctx            运行时编排上下文（提供 userQuery / history / intent）
     * @param tools          工具列表（来自 {@code discoverWorkflowTools()}）
     * @param sessionContext 运行时会话上下文（可为 null，表示无 runtime 来源）
     */
    public RouterContext build(OrchestrationContext ctx, List<ToolSpecification> tools,
                               RuntimeSessionContext sessionContext) {
        List<ToolRuntimeMetadata> metadata = tools.stream()
                .map(t -> new ToolRuntimeMetadata(
                        t.getToolName(),
                        t.getDescription(),
                        t.getParameters(),
                        ToolStaticRiskCatalog.forTool(t.getToolName()).orElse(null)))
                .toList();

        Optional<RuntimeAuthorization> authorization = sessionContext != null
                ? Optional.ofNullable(sessionContext.runtimeAuthorization())
                : Optional.empty();
        Optional<RuntimeTargetScope> targetScope = sessionContext != null
                ? Optional.ofNullable(sessionContext.runtimeTargetScope())
                : Optional.empty();

        return new RouterContext(
                ctx.getUserMessage(),
                ctx.getHistory(),
                ctx.getIntent(),
                Optional.empty(),   // intentConfidence：当前无 runtime source
                authorization,
                targetScope,
                Optional.empty(),   // runtimeRequestType：当前无 runtime source
                metadata);
    }
}
