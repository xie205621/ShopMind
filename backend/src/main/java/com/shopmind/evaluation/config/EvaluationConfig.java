package com.shopmind.evaluation.config;

import com.shopmind.evaluation.adapter.ShopMindAgentAdapter;
import com.shopmind.evaluation.port.EvaluableAgent;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Evaluation Engine 自动配置 — Phase F: Framework-Agnostic Evaluation。
 * <p>
 * 将 ShopMind 原生的 {@link AgentOrchestrator} 适配为 {@link EvaluableAgent}，
 * 使 {@code BenchmarkRunnerImpl} 能在 Spring 容器中正常初始化。
 * <p>
 * 未来接入其他框架（LangChain / OpenAI SDK）时，只需替换此 Bean 的实现类即可，
 * 无需修改 {@code BenchmarkRunnerImpl}。
 */
@Configuration
public class EvaluationConfig {

    @Bean
    public EvaluableAgent evaluableAgent(AgentOrchestrator orchestrator) {
        return new ShopMindAgentAdapter(orchestrator, "shopmind", "v2.3");
    }
}
