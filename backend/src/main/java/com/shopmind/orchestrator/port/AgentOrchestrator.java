package com.shopmind.orchestrator.port;

import com.shopmind.orchestrator.domain.OrchestrationRequest;
import reactor.core.publisher.Flux;

/**
 * 智能体编排入口接口 — §9 规范。
 * <p>
 * 对外暴露的唯一 API：接收 memoryId + userMessage，返回响应式 Token 流。
 */
public interface AgentOrchestrator {

    /**
     * 核心对话入口。
     *
     * @return 响应式 Token 文本流，用于 SSE 透传给前端
     */
    Flux<String> chat(OrchestrationRequest request);
}
