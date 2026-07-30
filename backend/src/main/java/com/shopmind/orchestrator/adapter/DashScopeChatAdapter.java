package com.shopmind.orchestrator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.ChatModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义千问（DashScope）真实大模型适配器 — Phase F: Real LLM Integration。
 * <p>
 * 通过阿里云 DashScope REST API 调用 Qwen 系列模型，支持：
 * <ol>
 *   <li>流式文本生成（SSE incremental output）</li>
 *   <li>Function Calling 工具调用（转换为 {@code __TOOL_CALL__} 协议）</li>
 *   <li>自动重试（429 / 5xx）</li>
 * </ol>
 * <p>
 * <b>激活条件：</b>{@code @Profile("qwen")}，需配置 {@code QWEN_API_KEY} 环境变量。
 * <p>
 * <b>配置示例：</b>
 * <pre>
 * export QWEN_API_KEY=sk-xxxxx
 * mvn spring-boot:run -Dspring-boot.run.profiles=qwen
 * </pre>
 */
@Component
@Profile("qwen")
public class DashScopeChatAdapter implements ChatModelPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatAdapter.class);

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String CHAT_PATH = "/services/aigc/text-generation/generation";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public DashScopeChatAdapter(
            ObjectMapper objectMapper,
            @Value("${shopmind.llm.qwen.api-key:}") String apiKey,
            @Value("${shopmind.llm.qwen.chat-model:qwen-plus}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DashScope] QWEN_API_KEY is empty — ChatAdapter will fail at runtime. "
                    + "Set environment variable QWEN_API_KEY or shopmind.llm.qwen.api-key.");
        } else {
            log.info("[DashScope] ChatAdapter initialized: model={}, apiKey={}...***",
                    model, apiKey.substring(0, Math.min(4, apiKey.length())));
        }
    }

    @Override
    public Flux<String> stream(List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "QWEN_API_KEY not configured. Set environment variable QWEN_API_KEY=sk-xxx"));
        }

        Map<String, Object> body;
        try {
            body = buildRequestBody(messages, tools);
        } catch (Exception e) {
            return Flux.error(new RuntimeException("Failed to build DashScope request body", e));
        }

        log.debug("[DashScope] Sending chat request: model={}, messages={}, tools={}",
                model, messages.size(), tools.size());

        return webClient.post()
                .uri(CHAT_PATH)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-SSE", "enable")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is5xxServerError(), response ->
                        Mono.error(new RuntimeException("DashScope 5xx: " + response.statusCode())))
                .onStatus(status -> status.value() == 429, response ->
                        Mono.error(new RuntimeException("DashScope rate limited (429)")))
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).strip())
                .filter(json -> !json.isEmpty() && !"[DONE]".equals(json))
                .concatMap(this::extractTokens)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .filter(t -> t.getMessage() != null && t.getMessage().contains("429")))
                .doOnError(e -> log.error("[DashScope] Chat request failed: model={}, error={}",
                        model, e.getMessage()));
    }

    // ============================================================
    //  SSE 解析
    // ============================================================

    /**
     * 从 DashScope SSE 数据行中提取 Token。
     * <p>
     * 文本内容 → 直接发射；工具调用 → 发射 {@code __TOOL_CALL__} 标记。
     */
    private Flux<String> extractTokens(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // 检查错误响应
            if (root.has("code") && root.has("message")) {
                String code = root.get("code").asText();
                String msg = root.get("message").asText();
                log.error("[DashScope] API error: code={}, message={}", code, msg);
                return Flux.error(new RuntimeException("DashScope API error [" + code + "]: " + msg));
            }

            JsonNode output = root.get("output");
            if (output == null) return Flux.empty();

            JsonNode choices = output.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) return Flux.empty();

            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null) return Flux.empty();

            // ---- 工具调用 ----
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                return parseToolCalls(toolCalls);
            }

            // ---- 文本内容 ----
            JsonNode content = message.get("content");
            if (content != null && !content.isNull()) {
                String text = content.asText();
                if (!text.isEmpty()) {
                    return Flux.just(text);
                }
            }

            return Flux.empty();
        } catch (Exception e) {
            log.debug("[DashScope] Failed to parse SSE chunk: {}", json, e);
            return Flux.empty();
        }
    }

    /**
     * 将 DashScope tool_calls 转换为 ShopMind 的 {@code __TOOL_CALL__} 协议。
     */
    private Flux<String> parseToolCalls(JsonNode toolCalls) {
        List<String> markers = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            JsonNode function = tc.get("function");
            if (function == null) continue;
            String name = function.get("name").asText();
            String args = function.has("arguments") ? function.get("arguments").asText() : "{}";
            markers.add("\n\n__TOOL_CALL__" + name + args);
        }
        log.info("[DashScope] Tool call detected: {}", markers);
        return Flux.fromIterable(markers);
    }

    // ============================================================
    //  请求体构建
    // ============================================================

    private Map<String, Object> buildRequestBody(List<ChatMessage> messages,
                                                  List<ToolSpecification> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        // input.messages
        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, String>> msgList = new ArrayList<>();
        for (ChatMessage m : messages) {
            msgList.add(Map.of(
                    "role", toDashScopeRole(m.getType()),
                    "content", m.getContent() != null ? m.getContent() : ""));
        }
        input.put("messages", msgList);
        body.put("input", input);

        // parameters
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("result_format", "message");
        params.put("incremental_output", true);

        // tools → function calling
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolDefs = new ArrayList<>();
            for (ToolSpecification t : tools) {
                toolDefs.add(buildToolDef(t));
            }
            params.put("tools", toolDefs);
        }

        body.put("parameters", params);
        return body;
    }

    /**
     * 将 ShopMind ToolSpecification 转换为 DashScope function calling 格式。
     */
    private Map<String, Object> buildToolDef(ToolSpecification t) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (t.getParameters() != null) {
            for (ParameterSpec p : t.getParameters()) {
                Map<String, Object> propDef = new LinkedHashMap<>();
                propDef.put("type", mapJavaTypeToJsonType(p.getType()));
                propDef.put("description", p.getDescription() != null ? p.getDescription() : "");
                properties.put(p.getName(), propDef);
                if (p.isRequired()) {
                    required.add(p.getName());
                }
            }
        }

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", t.getToolName());
        function.put("description", t.getDescription() != null ? t.getDescription() : "");
        function.put("parameters", Map.of(
                "type", "object",
                "properties", properties,
                "required", required));

        return Map.of("type", "function", "function", function);
    }

    // ============================================================
    //  工具方法
    // ============================================================

    private static String toDashScopeRole(String type) {
        return switch (type) {
            case "SYSTEM" -> "system";
            case "AI" -> "assistant";
            case "USER" -> "user";
            default -> "user";
        };
    }

    private static String mapJavaTypeToJsonType(String javaType) {
        if (javaType == null) return "string";
        return switch (javaType.toLowerCase()) {
            case "int", "integer", "long", "short", "byte" -> "integer";
            case "double", "float", "bigdecimal" -> "number";
            case "boolean", "bool" -> "boolean";
            case "list", "array" -> "array";
            case "map", "object" -> "object";
            default -> "string";
        };
    }
}
