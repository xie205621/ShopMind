package com.shopmind.orchestrator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.BenchmarkConfigHolder;
import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.ChatModelPort;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 大模型适配器 — OpenAI 兼容接口。
 * <p>
 * 通过 DeepSeek REST API（<a href="https://api.deepseek.com/v1">OpenAI 兼容</a>）调用 DeepSeek 模型，
 * 支持流式文本生成和 Function Calling。
 * <p>
 * <b>激活条件：</b>{@code @Profile("deepseek")}，需配置 {@code DEEPSEEK_API_KEY} 环境变量。
 * <p>
 * <b>使用方式：</b>
 * <pre>
 * export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxx
 * mvn spring-boot:run -Dspring-boot.run.profiles=deepseek
 * </pre>
 * <p>
 * <b>注意：</b>DeepSeek 不提供 Embedding API，Embedding 仍使用 Mock 适配器。
 */
@Component
@Primary
@Profile("deepseek")
public class DeepSeekChatAdapter implements ChatModelPort {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatAdapter.class);

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String CHAT_PATH = "/v1/chat/completions";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final Integer seed;  // FR2 修复：seed 参数用于提升可复现性

    /** 累积 tool_calls 的 arguments（跨 SSE chunks 拼接） */
    private final ThreadLocal<StringBuilder> toolCallAccumulator = ThreadLocal.withInitial(StringBuilder::new);

    public DeepSeekChatAdapter(
            ObjectMapper objectMapper,
            @Value("${shopmind.llm.deepseek.api-key:}") String apiKey,
            @Value("${shopmind.llm.deepseek.chat-model:deepseek-v4-flash}") String model,
            @Value("${shopmind.llm.deepseek.seed:}") Integer seed) {
        this.objectMapper = objectMapper;
        // Spring 未注入时从环境变量兜底
        this.apiKey = (apiKey == null || apiKey.isBlank())
                ? System.getenv("DEEPSEEK_API_KEY") : apiKey;
        this.model = model;
        this.seed = seed;
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .responseTimeout(Duration.ofSeconds(120))
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DeepSeek] DEEPSEEK_API_KEY is empty — ChatAdapter will fail at runtime.");
        } else {
            log.info("[DeepSeek] ChatAdapter initialized: model={}, apiKey={}...***",
                    model, apiKey.substring(0, Math.min(5, apiKey.length())));
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Flux<String> stream(List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.error(new IllegalStateException(
                    "DEEPSEEK_API_KEY not configured. Set environment variable DEEPSEEK_API_KEY=sk-xxx"));
        }

        Map<String, Object> body;
        try {
            body = buildRequestBody(messages, tools);
        } catch (Exception e) {
            return Flux.error(new RuntimeException("Failed to build DeepSeek request body", e));
        }

        // 重置 tool_calls 累加器
        toolCallAccumulator.get().setLength(0);

        log.debug("[DeepSeek] Sending chat request: model={}, messages={}, tools={}",
                model, messages.size(), tools.size());

        return webClient.post()
                .uri(CHAT_PATH)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), response ->
                        response.bodyToMono(String.class).flatMap(respBody ->
                                Mono.error(new RuntimeException(
                                        "DeepSeek " + response.statusCode() + ": " + respBody))))
                .onStatus(status -> status.is5xxServerError(), response ->
                        Mono.error(new RuntimeException("DeepSeek 5xx: " + response.statusCode())))
                .onStatus(status -> status.value() == 429, response ->
                        Mono.error(new RuntimeException("DeepSeek rate limited (429)")))
                .bodyToFlux(String.class)
                .flatMap(chunk -> Flux.fromStream(chunk.lines()))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.startsWith("data:") ? line.substring(5).strip() : line)
                .filter(json -> !"[DONE]".equals(json))
                .concatMap(this::extractTokens)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(1000))
                        .filter(t -> t.getMessage() != null
                                && (t.getMessage().contains("429") || t.getMessage().contains("5"))))
                .doFinally(signalType -> toolCallAccumulator.remove())
                .doOnError(e -> {
                    try {
                        log.error("[DeepSeek] Chat request failed: model={}, error={}, body={}",
                                model, e.getMessage(), objectMapper.writeValueAsString(body));
                    } catch (Exception ignored) {
                        log.error("[DeepSeek] Chat request failed: model={}, error={}",
                                model, e.getMessage());
                    }
                });
    }

    // ============================================================
    //  SSE 解析
    // ============================================================

    /**
     * 从 DeepSeek SSE 数据行中提取 Token。
     * <p>
     * OpenAI 兼容格式：{@code delta.content} 为文本增量，
     * {@code delta.tool_calls} 为工具调用增量（跨多个 chunk 拼接 arguments）。
     */
    private Flux<String> extractTokens(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // 检查错误
            if (root.has("error")) {
                JsonNode error = root.get("error");
                String msg = error.has("message") ? error.get("message").asText() : error.toString();
                log.error("[DeepSeek] API error: {}", msg);
                return Flux.error(new RuntimeException("DeepSeek API error: " + msg));
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) return Flux.empty();

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta == null) return Flux.empty();

            // ---- 工具调用 ----
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                return handleToolCalls(toolCalls, choice);
            }

            // ---- 文本内容 ----
            JsonNode content = delta.get("content");
            if (content != null && !content.isNull()) {
                String text = content.asText();
                if (!text.isEmpty()) {
                    return Flux.just(text);
                }
            }

            return Flux.empty();
        } catch (Exception e) {
            log.warn("[DeepSeek] Failed to parse SSE chunk: {}", json.substring(0, Math.min(200, json.length())), e);
            return Flux.empty();
        }
    }

    /**
     * 处理 DeepSeek 工具调用增量。
     * <p>
     * OpenAI 格式的 tool_calls 是增量的——每个 chunk 只包含 arguments 的一部分，
     * 需要跨 chunk 拼接。当 {@code finish_reason == "tool_calls"} 时输出完整标记。
     */
    private Flux<String> handleToolCalls(JsonNode toolCalls, JsonNode choice) {
        for (JsonNode tc : toolCalls) {
            JsonNode function = tc.get("function");
            if (function == null) continue;

            // 拼接 tool name（只在第一个 chunk 出现）
            if (function.has("name")) {
                toolCallAccumulator.get().append("__NAME__")
                        .append(function.get("name").asText())
                        .append("__NAME__");
            }

            // 拼接 arguments 增量
            if (function.has("arguments")) {
                toolCallAccumulator.get().append(function.get("arguments").asText());
            }
        }

        // 检查是否完成
        String finishReason = choice.has("finish_reason")
                ? choice.get("finish_reason").asText() : null;

        if ("tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
            String accumulated = toolCallAccumulator.get().toString();
            toolCallAccumulator.get().setLength(0); // reset

            if (!accumulated.isEmpty()) {
                // 解析: __NAME__toolName__NAME__{jsonArgs}
                int nameEnd = accumulated.indexOf("__NAME__", 8);
                if (nameEnd > 0) {
                    String toolName = accumulated.substring(8, nameEnd);
                    String jsonArgs = accumulated.substring(nameEnd + 8);
                    log.info("[DeepSeek] Tool call: {} args={}", toolName, jsonArgs.length() > 100
                            ? jsonArgs.substring(0, 100) + "..." : jsonArgs);
                    return Flux.just("\n\n__TOOL_CALL__" + toolName + jsonArgs);
                }
            }
        }

        return Flux.empty();
    }

    // ============================================================
    //  请求体构建
    // ============================================================

    private Map<String, Object> buildRequestBody(List<ChatMessage> messages,
                                                  List<ToolSpecification> tools) {
        Map<String, Object> body = new LinkedHashMap<>();

        // P2-0.5C: 优先从 BenchmarkConfig（单一事实源）读取，降级到 application.yml
        BenchmarkConfig config = BenchmarkConfigHolder.get();
        String effectiveModel = (config != null && config.llmProvider() != null)
                ? config.llmProvider() : model;
        double effectiveTemperature = (config != null) ? config.temperature() : 0.1;

        body.put("model", effectiveModel);
        body.put("stream", true);

        // messages
        List<Map<String, String>> msgList = new ArrayList<>();
        for (ChatMessage m : messages) {
            msgList.add(Map.of(
                    "role", toOpenAIRole(m.getType()),
                    "content", m.getContent() != null ? m.getContent() : ""));
        }
        body.put("messages", msgList);

        // tools
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolDefs = new ArrayList<>();
            for (ToolSpecification t : tools) {
                toolDefs.add(buildToolDef(t));
            }
            body.put("tools", toolDefs);
        }

        // P2-0.5C: temperature 从 BenchmarkConfig 读取（不再硬编码）
        body.put("temperature", effectiveTemperature);

        // P2-0.5C: topP 从 BenchmarkConfig 读取并发送（DeepSeek OpenAI 兼容 API 支持）
        if (config != null) {
            body.put("top_p", config.topP());
        }

        // P2-0.5C: maxTokens 从 BenchmarkConfig 读取并发送
        if (config != null && config.maxTokens() != null) {
            body.put("max_tokens", config.maxTokens());
        }

        // P2-0.5C: seed 优先从 BenchmarkConfig 读取，降级到 application.yml
        Integer effectiveSeed = (config != null && config.seed() != null)
                ? config.seed() : seed;
        if (effectiveSeed != null) {
            body.put("seed", effectiveSeed);
        }

        return body;
    }

    /**
     * 将 ShopMind ToolSpecification 转换为 OpenAI function calling 格式。
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

    private static String toOpenAIRole(String type) {
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
