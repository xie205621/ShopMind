package com.shopmind.evaluation.adapter;

import com.shopmind.evaluation.domain.AgentInput;
import com.shopmind.evaluation.port.EvaluableAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * LangChain Agent Adapter（骨架实现）— Phase F: Framework-Agnostic Evaluation。
 * <p>
 * <b>状态：</b>接口设计已完成，实际 LangChain Java 集成待实现。
 * 当前作为规范参考，证明 {@link EvaluableAgent} 接口可适配第三方框架。
 * <p>
 * <b>未来实现要点：</b>
 * <pre>
 * // 1. 引入 LangChain4j 依赖
 * // 2. 创建 ChatLanguageModel + ToolSpecification
 * // 3. 将 AgentInput.userMessage 转为 UserMessage
 * // 4. 调用 AiServices.generate() 或 Chain.run()
 * // 5. 将 AiMessage 返回的 Token 流转为 Flux&lt;String&gt;
 * </pre>
 * <p>
 * <b>评测矩阵中的应用：</b>
 * <pre>
 * EvaluableAgent shopmind = new ShopMindAgentAdapter(...);
 * EvaluableAgent langchain = new LangChainAgentAdapter(model, tools);
 * // 同一份 Dataset，不同 Agent → 对比报告
 * BenchmarkRunner.run(dataset, config).forAgent(shopmind);
 * BenchmarkRunner.run(dataset, config).forAgent(langchain);
 * </pre>
 */
public final class LangChainAgentAdapter implements EvaluableAgent {

    private static final Logger log = LoggerFactory.getLogger(LangChainAgentAdapter.class);

    private final String agentId;
    private final String agentVersion;

    /**
     * @param agentId      评测标识（如 "langchain"）
     * @param agentVersion 版本号（如 "0.31.0"）
     */
    public LangChainAgentAdapter(String agentId, String agentVersion) {
        this.agentId = agentId;
        this.agentVersion = agentVersion;
    }

    // ---- 未来：注入 LangChain4j ChatLanguageModel ----
    // private final ChatLanguageModel model;
    // private final List<ToolSpecification> tools;

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
     * <p>
     * 当 LangChain 集成完成后，此处将替换为实际的
     * {@code model.chat(...)} 调用。
     */
    @Override
    public Flux<String> chat(AgentInput input) {
        log.info("[LangChainAdapter] Skeleton——chat() not yet implemented. Input: sessionId={}, message={}",
                input.sessionId(), input.userMessage());
        return Flux.error(new UnsupportedOperationException(
                "LangChainAgentAdapter.chat() is not yet implemented. "
                + "This is a skeleton adapter for the Framework-Agnostic Evaluation design. "
                + "To enable it, integrate LangChain4j and inject ChatLanguageModel."));
    }
}
