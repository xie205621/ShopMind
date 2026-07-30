package com.shopmind.workflow.domain;

/**
 * 工具使用规则 — Workflow_Engine.md §6.2 规范。
 * <p>
 * 定义工作流中可用工具的声明式描述。每个 ToolRule 对应 MCP Engine 中的一个
 * {@code @McpTool} 注册实例，用于在 System Prompt 中告知 LLM 可用工具的用途与约束。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param toolName    工具全局唯一名称（对应 {@code @McpTool.name()}）
 * @param description 工具功能描述，将被注入 System Prompt 供 LLM 理解
 * @param required    是否为必须工具（如支付场景的 {@code confirmPayment}）
 */
public record ToolRule(
        String toolName,
        String description,
        boolean required
) {

    /**
     * 创建一个非必需的、仅提供描述的工具规则。
     */
    public static ToolRule optional(String toolName, String description) {
        return new ToolRule(toolName, description, false);
    }

    /**
     * 创建一个必需的工具规则。
     */
    public static ToolRule required(String toolName, String description) {
        return new ToolRule(toolName, description, true);
    }
}
