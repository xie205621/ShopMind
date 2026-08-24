package com.shopmind.experiment;

import com.shopmind.mcp.model.ParameterSpec;

import java.util.List;

/**
 * Router 可见的工具运行时元数据 — Phase 4 (P4-1)。
 * <p>
 * 组合 {@link com.shopmind.mcp.model.ToolSpecification} 的描述性字段与
 * {@link ToolStaticRiskProfile}，作为 {@link RouterContext} 的工具输入。
 * 不修改 {@link com.shopmind.mcp.model.ToolSpecification} 既有语义。
 *
 * @param toolName   工具全局唯一名称
 * @param description 工具功能描述
 * @param parameters 参数描述列表
 * @param staticRisk 工具级静态风险（未登记工具可为 null）
 */
public record ToolRuntimeMetadata(
        String toolName,
        String description,
        List<ParameterSpec> parameters,
        ToolStaticRiskProfile staticRisk
) {
}
