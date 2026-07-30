package com.shopmind.evaluation.port;

import com.shopmind.evaluation.domain.AgentInput;
import reactor.core.publisher.Flux;

/**
 * 可评测 Agent 统一接口 — Phase F: Framework-Agnostic Evaluation。
 * <p>
 * <b>核心思想：</b>Evaluation Engine 不关心 Agent 的内部实现（ShopMind / LangChain /
 * OpenAI SDK / ...），只要求 Agent 实现此接口。BenchmarkRunner 通过此接口
 * 统一驱动任意 Agent 框架。
 * <p>
 * <b>使用方式：</b>
 * <pre>
 * EvaluableAgent agent = new ShopMindAgentAdapter(orchestrator);
 * BenchmarkRunner runner = new BenchmarkRunnerImpl(agent, ...);
 * </pre>
 * <p>
 * <b>实现约束：</b>
 * <ul>
 *   <li>{@link #chat(AgentInput)} 必须返回非阻塞的 {@code Flux<String>}</li>
 *   <li>{@link #agentId()} 和 {@link #agentVersion()} 用于实验报告标识</li>
 *   <li>所有 Adapter 实现均为纯函数，不持有可变状态</li>
 * </ul>
 *
 * @see com.shopmind.evaluation.adapter.ShopMindAgentAdapter
 * @see com.shopmind.evaluation.adapter.LangChainAgentAdapter
 */
public interface EvaluableAgent {

    /**
     * Agent 唯一标识（用于实验报告）。
     * 例如："shopmind", "langchain", "openai-assistant"
     */
    String agentId();

    /**
     * Agent 版本（用于实验报告）。
     * 例如："v2.0", "0.1.0"
     */
    String agentVersion();

    /**
     * 核心对话入口：接收用户消息，返回响应式 Token 流。
     *
     * @param input 框架无关的输入（sessionId + userMessage）
     * @return 响应式 Token 文本流，用于 SSE 透传或基准评测
     */
    Flux<String> chat(AgentInput input);
}
