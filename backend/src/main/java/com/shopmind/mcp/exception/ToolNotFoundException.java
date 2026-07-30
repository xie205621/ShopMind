package com.shopmind.mcp.exception;

/**
 * 工具未找到异常 — LLM 幻觉生成了不存在的工具名。
 * <p>
 * 降级策略（MCP_Engine.md §11）：向 LLM 返回 "工具不存在，请重新规划"。
 */
public class ToolNotFoundException extends RuntimeException {

    private final String toolName;

    public ToolNotFoundException(String toolName) {
        super("工具 [%s] 不存在".formatted(toolName));
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
