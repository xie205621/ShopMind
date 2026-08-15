package com.shopmind.orchestrator.port;

import com.shopmind.orchestrator.domain.ChatStreamEvent;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import reactor.core.publisher.Flux;

/**
 * 对话流式入口 — 面向 HTTP/SSE 等外部通道的结构化事件流。
 * <p>
 * 与 {@link AgentOrchestrator#chat(OrchestrationRequest)}（纯文本 Token 流，供评测使用）区分：
 * 本接口返回带意图/工具调用/统计信息的 {@link ChatStreamEvent} 事件流。
 */
public interface ChatStreamingPort {

    /**
     * 以结构化事件流形式执行一次对话编排。
     *
     * @return 结构化事件流（token / intent / tool_call / tool_result / done / error）
     */
    Flux<ChatStreamEvent> stream(OrchestrationRequest request);
}
