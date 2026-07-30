package com.shopmind.knowledge.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.knowledge.port.EmbeddingProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义千问 Text Embedding 适配器 — Phase F: Real LLM Integration。
 * <p>
 * 通过阿里云 DashScope Embedding API 将文本转换为稠密向量。
 * 默认使用 {@code text-embedding-v3} 模型（1024 维）。
 * <p>
 * <b>激活条件：</b>{@code @Profile("qwen")}，需配置 {@code QWEN_API_KEY} 环境变量。
 */
@Component
@Profile("qwen")
public class DashScopeEmbeddingAdapter implements EmbeddingProviderPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingAdapter.class);

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String EMBED_PATH = "/services/embeddings/text-embedding/text-embedding";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public DashScopeEmbeddingAdapter(
            ObjectMapper objectMapper,
            @Value("${shopmind.llm.qwen.api-key:}") String apiKey,
            @Value("${shopmind.llm.qwen.embedding-model:text-embedding-v3}") String model) {
        this.objectMapper = objectMapper;
        // Spring 未注入时从环境变量兜底
        this.apiKey = (apiKey == null || apiKey.isBlank())
                ? System.getenv("QWEN_API_KEY") : apiKey;
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DashScope] QWEN_API_KEY is empty — EmbeddingAdapter will fail at runtime.");
        } else {
            log.info("[DashScope] EmbeddingAdapter initialized: model={}", model);
        }
    }

    /**
     * 将文本转换为浮点向量。
     * <p>
     * <b>注意：</b>此方法会阻塞等待 HTTP 响应（Embedding 不支持流式）。
     * 如果调用方在 Reactor 线程中调用，建议使用 {@code Mono.fromCallable(() -> embed(text))}
     * 包裹以避免阻塞。
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            log.warn("[DashScope] Empty text for embedding, returning zero vector");
            return new float[1024];
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "QWEN_API_KEY not configured. Set environment variable QWEN_API_KEY=sk-xxx");
        }

        Map<String, Object> body = buildEmbedBody(text);

        try {
            String responseJson = webClient.post()
                    .uri(EMBED_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                            .filter(t -> t.getMessage() != null
                                    && (t.getMessage().contains("429") || t.getMessage().contains("5"))))
                    .block(Duration.ofSeconds(30));

            if (responseJson == null) {
                throw new RuntimeException("DashScope embedding returned null response");
            }

            return parseEmbeddingResponse(responseJson);
        } catch (Exception e) {
            log.error("[DashScope] Embedding request failed: text='{}', error={}",
                    text.length() > 50 ? text.substring(0, 50) + "..." : text, e.getMessage());
            throw new RuntimeException("Failed to get embedding from DashScope: " + e.getMessage(), e);
        }
    }

    // ============================================================
    //  请求 / 响应解析
    // ============================================================

    private Map<String, Object> buildEmbedBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("texts", List.of(text));
        body.put("input", input);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("text_type", "query");
        body.put("parameters", params);

        return body;
    }

    private float[] parseEmbeddingResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // 错误检查
            if (root.has("code") && root.has("message")) {
                String code = root.get("code").asText();
                String msg = root.get("message").asText();
                throw new RuntimeException("DashScope Embedding API error [" + code + "]: " + msg);
            }

            JsonNode output = root.get("output");
            if (output == null) {
                throw new RuntimeException("DashScope Embedding response missing 'output' field");
            }

            JsonNode embeddings = output.get("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.isEmpty()) {
                throw new RuntimeException("DashScope Embedding response missing 'embeddings' array");
            }

            JsonNode embedding = embeddings.get(0).get("embedding");
            if (embedding == null || !embedding.isArray()) {
                throw new RuntimeException("DashScope Embedding response missing 'embedding' array");
            }

            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }

            log.debug("[DashScope] Embedding generated: dims={}, text_len={}",
                    vector.length, Math.min(30, embedding.size()));
            return vector;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DashScope embedding response: " + e.getMessage(), e);
        }
    }
}
