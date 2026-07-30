package com.shopmind.evaluation.adapter;

import com.shopmind.evaluation.domain.AgentInput;
import com.shopmind.evaluation.port.EvaluableAgent;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import reactor.core.publisher.Flux;

/**
 * ShopMind Agent Adapter — 将 ShopMind 原生的 {@link AgentOrchestrator} 适配为
 * {@link EvaluableAgent}，使其可被 Evaluation Engine 驱动。
 * <p>
 * <b>职责：</b>
 * <ol>
 *   <li>{@code AgentInput} → {@code OrchestrationRequest} 协议转换</li>
 *   <li>{@code Flux<String>} 透传（无需修改 Token 流）</li>
 *   <li>提供 agentId / agentVersion 元数据</li>
 * </ol>
 * <p>
 * <b>无状态：</b>纯函数适配器，线程安全。
 */
public final class ShopMindAgentAdapter implements EvaluableAgent {

    private final AgentOrchestrator orchestrator;
    private final String agentId;
    private final String agentVersion;

    /**
     * @param orchestrator ShopMind 原生编排器
     * @param agentId      评测标识（如 "shopmind"）
     * @param agentVersion 版本号（如 "v2.0"）
     */
    public ShopMindAgentAdapter(AgentOrchestrator orchestrator, String agentId, String agentVersion) {
        this.orchestrator = orchestrator;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String agentVersion() {
        return agentVersion;
    }

    @Override
    public Flux<String> chat(AgentInput input) {
        OrchestrationRequest request = new OrchestrationRequest(
                input.sessionId(),
                input.userMessage()
        );
        return orchestrator.chat(request);
    }
}
