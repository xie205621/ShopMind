package com.shopmind.orchestrator.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.evaluation.pipeline.BenchmarkConfigHolder;
import com.shopmind.evaluation.rtmp.formal.RtmpFormalExperimentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 5-E1.1（#2）：adapter effective model proof。
 * <p>
 * 不调用真实 Qwen，直接验证 formal config 的最终 request body：
 * {@code BenchmarkConfig → BenchmarkConfigHolder → DashScopeChatAdapter → request body}。
 * 构造 adapter 时显式传入默认 {@code qwen-plus}，证明最终 {@code model} 被 formal config
 * 的 {@code qwen-max} 覆盖（即 config 路径真正生效，而非依赖 application.yml 默认值）。
 */
class DashScopeChatAdapterEffectiveModelTest {

    private final DashScopeChatAdapter adapter =
            new DashScopeChatAdapter(new ObjectMapper(), "test-api-key", "qwen-plus");

    @AfterEach
    void tearDown() {
        BenchmarkConfigHolder.clear();
    }

    @Test
    @DisplayName("#2. formal config reaches adapter request body with qwen-max + frozen params")
    void formalConfig_reachesRequestBod() {
        BenchmarkConfigHolder.set(RtmpFormalExperimentConfig.build("RTMP-EXP01"));

        Map<String, Object> body = adapter.buildRequestBody(List.of(), List.of());

        assertEquals("qwen-max", body.get("model"),
                "formal config 的 qwen-max 必须覆盖默认 qwen-plus");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("parameters");
        assertEquals(0.1, ((Number) params.get("temperature")).doubleValue(), 1e-9);
        assertEquals(0.9, ((Number) params.get("top_p")).doubleValue(), 1e-9);
        assertFalse(params.containsKey("seed"), "seed 不得发送到 DashScope");
        assertFalse(params.containsKey("max_tokens"), "maxTokens=null 时不得发送 max_tokens");
    }
}
