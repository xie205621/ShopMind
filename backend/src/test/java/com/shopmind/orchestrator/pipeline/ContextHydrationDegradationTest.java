package com.shopmind.orchestrator.pipeline;

import com.shopmind.knowledge.api.KnowledgeRetriever;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-2 稳定性验收：Memory 异常降级。
 * <p>
 * 纯单元测试（无 Spring 上下文），直接构造 {@link ContextHydrationStep} 并注入 mock 依赖，
 * 验证 Memory 读写失败时主链路降级为空历史继续运行，而不是抛异常中断整个请求。
 */
class ContextHydrationDegradationTest {

    @Test
    @DisplayName("Memory 异常：读取失败 → 降级为空历史，主链路继续")
    void memoryLoadFailureDegradesToEmptyHistory() {
        ChatMemoryStore memoryStore = mock(ChatMemoryStore.class);
        when(memoryStore.getMessages(any()))
                .thenThrow(new RuntimeException("simulated MongoDB failure"));

        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(any())).thenReturn(RetrievedContext.builder().build());

        ContextHydrationStep step = new ContextHydrationStep(memoryStore, retriever);

        OrchestrationContext ctx = new OrchestrationContext("mem_fail_001", "你好");
        OrchestrationContext result = step.execute(ctx).block();

        assertThat(result).isNotNull();
        assertThat(result.getHistory()).isEmpty();
    }
}
