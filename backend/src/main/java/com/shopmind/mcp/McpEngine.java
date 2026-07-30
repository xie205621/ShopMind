package com.shopmind.mcp;

import com.shopmind.mcp.model.ToolSpecification;

import java.util.List;

/**
 * MCP Engine 顶层接口 — MCP_Engine.md 第 9 节规范。
 * <p>
 * 本模块为内部框架级组件，提供给 Agent Engine 调用。
 * 封装了工具发现与远程调用能力，使 AI 层无需关心底层业务实现细节。
 */
public interface McpEngine {

    /**
     * 发现并获取所有已注册的工具描述（供大模型 Prompt 组装使用）。
     *
     * @return 所有已注册 Tool 的完整规格列表
     */
    List<ToolSpecification> discoverTools();

    /**
     * 执行具体的工具调用。
     * <p>
     * 内部流程：查表 → JSON 参数映射 → 类型校验 → 反射调用 → 异常降级。
     *
     * @param toolName      工具名称（对应 @McpTool.name()）
     * @param jsonArguments LLM 生成的 JSON 参数字符串
     * @return 工具执行结果（成功返回业务结果字符串，失败返回降级提示）
     */
    String executeTool(String toolName, String jsonArguments);
}
