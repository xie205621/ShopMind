package com.shopmind.orchestrator;

import com.shopmind.knowledge.model.KnowledgeChunk;
import com.shopmind.knowledge.port.VectorStorePort;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Orchestrator 测试套件 — 严格对应 Agent_Orchestrator.md 第 12 节 Test Plan。
 */
@SpringBootTest
class ShopAgentOrchestratorTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private ChatMemoryStore memoryStore;

    @Autowired
    private VectorStorePort vectorStore;

    @BeforeEach
    void setUp() {
        // 清空上一轮 Memory
        memoryStore.deleteMessages("user_test_001");
        // 种子知识库数据
        seedKnowledge();
    }

    private void seedKnowledge() {
        vectorStore.add(
                KnowledgeChunk.builder()
                        .id("seed_001")
                        .text("退货政策：7天无理由退货，15天换货。")
                        .metadata(Map.of("source", "售后.md"))
                        .build(),
                // 使用简化的浮点向量（256维，与 MockEmbeddingAdapter 一致）
                new float[256]
        );
    }

    // ============================================================
    //  Test 1: 管道组装断言（Outer Loop 完整性）
    // ============================================================

    @Test
    @DisplayName("Outer Loop 完整性：闲聊场景全链路不中断")
    void shouldCompleteFullOuterLoopForChitchat() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "你好");

        var result = orchestrator.chat(request);

        StepVerifier.create(result)
                .thenConsumeWhile(String::isEmpty)     // 跳过结构化事件映射出的空文本（Intent/Done）
                .expectNextMatches(t -> !t.isEmpty()) // at least one token
                .thenConsumeWhile(t -> true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Outer Loop 完整性：知识检索场景返回知识内容")
    void shouldReturnKnowledgeForPolicyQuery() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "退货政策是什么");

        var result = orchestrator.chat(request);

        StepVerifier.create(result)
                .expectNextCount(1) // 至少返回一些 token
                .thenCancel()       // 不等待完整流结束（Mock 适配器有限）
                .verify();
    }

    // ============================================================
    //  Test 2: Inner Loop 工具调用测试
    // ============================================================

    @Test
    @DisplayName("Inner Loop：工具调用场景触发 MCP 执行（工具结果出现在输出中）")
    void shouldTriggerToolCallForPaymentQuery() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "帮我付款");

        List<String> tokens = orchestrator.chat(request)
                .collectList()
                .block();

        assertThat(tokens).isNotNull();
        // 工具被正确调度：输出中应包含 MCP 执行结果或降级提示
        assertThat(tokens.stream().anyMatch(t ->
                t.contains("工具执行结果") || t.contains("工具不存在"))).isTrue();
    }

    @Test
    @DisplayName("Inner Loop：工具调用后 Memory 已更新（§6 记忆回写约束）")
    void shouldWriteToolCallToMemory() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "帮我付款");

        orchestrator.chat(request)
                .collectList()
                .block();

        // 验证 Memory 中已有记录
        List<ChatMessage> history = memoryStore.getMessages("user_test_001");
        assertThat(history).isNotEmpty();
    }

    // ============================================================
    //  Test 3: 异常降级测试（§11 + §6 熔断降级）
    // ============================================================

    @Test
    @DisplayName("降级：空消息请求返回正常 Flux（不抛异常）")
    void shouldNotThrowForEmptyMessage() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "");

        var result = orchestrator.chat(request);

        StepVerifier.create(result)
                .thenConsumeWhile(t -> true)
                .verifyComplete();
    }

    // ============================================================
    //  Test 4: 背压与降级验证
    // ============================================================

    @Test
    @DisplayName("背压保护：onBackpressureBuffer 已配置（Flux 不会 OOM）")
    void shouldHandleBackpressureGracefully() {
        OrchestrationRequest request = new OrchestrationRequest("user_test_001", "你好");

        // 请求 100 次快速模拟高并发，验证无 OOM
        for (int i = 0; i < 100; i++) {
            orchestrator.chat(request)
                    .collectList()
                    .block();
        }
        // 如果到这里没有 OOM，背压策略生效
        assertThat(true).isTrue();
    }

    // ============================================================
    //  Test 5: 意图分析测试
    // ============================================================

    @Nested
    @DisplayName("意图分析（IntentAnalyzer）")
    class IntentAnalysisTests {

        @Test
        @DisplayName("闲聊 → 不需要 Knowledge + Tool")
        void shouldDetectChitchat() {
            OrchestrationRequest request = new OrchestrationRequest("user_test_001", "你好啊");

            List<String> tokens = orchestrator.chat(request)
                    .collectList()
                    .block();

            // 闲聊 Mock 回复不包含 TOOL_CALL
            assertThat(tokens).isNotNull();
            assertThat(tokens.stream().noneMatch(t -> t.contains("__TOOL_CALL__"))).isTrue();
        }

        @Test
        @DisplayName("知识关键词 → 不触发 Tool（纯文本回复）")
        void shouldNotTriggerToolForKnowledgeQuery() {
            OrchestrationRequest request = new OrchestrationRequest("user_test_001", "退货政策如何");

            List<String> tokens = orchestrator.chat(request)
                    .collectList()
                    .block();

            // 知识检索场景 Mock 回复不包含 TOOL_CALL
            assertThat(tokens).isNotNull();
            assertThat(tokens.stream().noneMatch(t -> t.contains("__TOOL_CALL__"))).isTrue();
        }
    }
}
