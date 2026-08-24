package com.shopmind.orchestrator.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.port.IntentAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-1 请求级可观测性单元测试。
 * <p>
 * 覆盖三个核心约束：
 * 1) 结构化 JSON 包含请求级观测全部字段；
 * 2) 脱敏 — 不记录 query 原文与 API Key；
 * 3) requestId 每次请求唯一。
 */
class RequestObservabilityLoggerTest {

    private RequestObservabilityLogger logger;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        logger = new RequestObservabilityLogger(objectMapper);
    }

    private OrchestrationContext buildContext() {
        OrchestrationContext ctx = new OrchestrationContext("session_abc", "我的订单号 ORD123 帮我查一下物流");
        ctx.setModel("qwen-plus");
        ctx.setIntent(IntentAnalyzer.IntentResult.tool("订单咨询"));
        ctx.setIntentLatencyMs(35);
        ctx.setMemoryLatencyMs(12);
        ctx.setRagLatencyMs(210);
        ctx.addLlmLatencyMs(1680);
        ctx.addToolLatency(6);
        ctx.getState().markSuccess();
        return ctx;
    }

    @Test
    @DisplayName("结构化 JSON 包含请求级观测全部字段")
    void shouldContainAllObservabilityFields() throws Exception {
        String json = logger.buildJson(buildContext(), "v2.3", 2030);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("requestId").asText()).isNotBlank();
        assertThat(node.get("sessionId").asText()).isEqualTo("session_abc");
        assertThat(node.get("workflowVersion").asText()).isEqualTo("v2.3");
        assertThat(node.get("model").asText()).isEqualTo("qwen-plus");
        assertThat(node.get("intent").asText()).isEqualTo("订单咨询");
        assertThat(node.get("intentLatencyMs").asLong()).isEqualTo(35);
        assertThat(node.get("memoryLatencyMs").asLong()).isEqualTo(12);
        assertThat(node.get("ragLatencyMs").asLong()).isEqualTo(210);
        assertThat(node.get("llmLatencyMs").asLong()).isEqualTo(1680);
        assertThat(node.get("toolLatenciesMs").size()).isEqualTo(1);
        assertThat(node.get("toolCalls").asInt()).isEqualTo(1);
        assertThat(node.get("totalLatencyMs").asLong()).isEqualTo(2030);
        assertThat(node.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("脱敏：不记录 query 原文与 API Key")
    void shouldNotLeakSensitiveData() {
        String json = logger.buildJson(buildContext(), "v2.3", 2030);

        assertThat(json).doesNotContain("ORD123");
        assertThat(json).doesNotContain("查一下物流");
        assertThat(json).doesNotContain("sk-");
        assertThat(json).doesNotContain("apiKey");
        assertThat(json).doesNotContain("api_key");
    }

    @Test
    @DisplayName("requestId 每次请求唯一且非空")
    void shouldGenerateUniqueRequestIdPerRequest() {
        OrchestrationContext a = new OrchestrationContext("s", "hi");
        OrchestrationContext b = new OrchestrationContext("s", "hi");

        assertThat(a.getRequestId()).isNotBlank();
        assertThat(b.getRequestId()).isNotBlank();
        assertThat(a.getRequestId()).isNotEqualTo(b.getRequestId());
    }
}
