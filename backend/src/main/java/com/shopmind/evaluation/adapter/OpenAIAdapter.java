package com.shopmind.evaluation.adapter;

import com.shopmind.evaluation.domain.AgentInput;
import com.shopmind.evaluation.port.EvaluableAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Agent SDK Adapter（骨架实现）— Phase F: Framework-Agnostic Evaluation。
 * <p>
 * <b>状态：</b>接口设计已完成，实际 OpenAI Agent SDK 集成待实现。
 * <p>
 * <b>适应场景：</b>企业使用 OpenAI Assistants API 或 Agent SDK 构建的 Agent，
 * 通过此 Adapter 接入 ShopMind Evaluation Engine 统一评测。
 * <p>
 * <b>未来实现要点：</b>
 * <pre>
 * // 1. 引入 OpenAI Java SDK
 * // 2. 创建 Assistant + Thread
 * // 3. 将 AgentInput.userMessage 提交到 Thread
 * // 4. 轮询 Run Status，流式返回 Token
 * // 5. 处理 Function Calling 工具回调
 * </pre>
 */
public final class OpenAIAdapter implements EvaluableAgent {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAdapter.class);

    private final String agentId;
    private final String agentVersion;

    /**
     * @param agentId      评测标识（如 "openai-assistant"）
     * @param agentVersion 版本号（如 "gpt-4.1"）
     */
    public OpenAIAdapter(String agentId, String agentVersion) {
        this.agentId = agentId;
        this.agentVersion = agentVersion;
    }

    // ---- 未来：注入 OpenAI Client + Assistant ID ----
    // private final OpenAIClient client;
    // private final String assistantId;

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String agentVersion() {
        return agentVersion;
    }

    /**
     * 骨架实现：返回 UnsupportedOperationException。
     */
    @Override
    public Flux<String> chat(AgentInput input) {
        log.info("[OpenAIAdapter] Skeleton——chat() not yet implemented. Input: sessionId={}, message={}",
                input.sessionId(), input.userMessage());
        return Flux.error(new UnsupportedOperationException(
                "OpenAIAdapter.chat() is not yet implemented. "
                + "This is a skeleton adapter for the Framework-Agnostic Evaluation design. "
                + "To enable it, integrate OpenAI Java SDK and provide an Assistant ID."));
    }
}
