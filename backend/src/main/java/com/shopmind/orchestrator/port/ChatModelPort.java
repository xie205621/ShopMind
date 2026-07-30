package com.shopmind.orchestrator.port;

import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 大模型提供者抽象接口 — §6 DIP 约束。
 * <p>
 * 通过此接口屏蔽底层大模型厂商实现（OpenAI / DashScope）。对 Orchestrator 而言，
 * 只需调用此端口获取响应式 Token 流。
 */
public interface ChatModelPort {

    /**
     * 流式推理：传入组装好的消息列表和工具定义，返回 SSE Token 流。
     * <p>
     * 返回的 Flux 在 LLM 决定调用工具时，会先发射一个特殊标记：
     * {@code __TOOL_CALL__} 行，后跟工具名和 JSON 参数。
     * Orchestrator 通过 Flux 中的内容检测 Function Calling 事件。
     *
     * @param messages    完整的对话消息列表（System + History + User）   
     * @param tools       可用工具 Schema（MCP Engine 注册的 ToolSpecification）
     * @return Token 文本流，可能包含工具调用标记
     */
    Flux<String> stream(List<ChatMessage> messages, List<ToolSpecification> tools);
}
