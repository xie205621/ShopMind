package com.shopmind.orchestrator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.BenchmarkConfigHolder;
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

    /** 累积流式 tool_calls 的 name + arguments（跨 SSE chunks 拼接，与 DeepSeek 一致） */
    private final ThreadLocal<StringBuilder> toolCallAccumulator = ThreadLocal.withInitial(StringBuilder::new);

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
    public String modelName() {
        return model;
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

        // 重置 tool_calls 累加器（跨 SSE chunks 拼接）
        toolCallAccumulator.get().setLength(0);

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
                .flatMap(chunk -> Flux.fromStream(chunk.lines()))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.startsWith("data:") ? line.substring(5).strip() : line)
                .filter(json -> !"[DONE]".equals(json))
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

            // ---- 工具调用：流式分块返回，name 在首块、arguments 在后续块增量返回，需跨块累积 ----
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                accumulateToolCalls(toolCalls);
            }

            // ---- 工具调用完成标志（finish_reason 可能位于 choice 或 output 层级） ----
            String finishReason = readFinishReason(choice, output);
            if ("tool_calls".equals(finishReason)) {
                return flushToolCall();
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
     * 累积流式 tool_calls 的 name + arguments。
     * <p>
     * DashScope 流式模式下工具调用是分块返回的：函数名在首个 chunk，
     * 参数在后续 chunk 中以增量方式返回，需跨 chunk 拼接。
     */
    private void accumulateToolCalls(JsonNode toolCalls) {
        for (JsonNode tc : toolCalls) {
            JsonNode function = tc.get("function");
            if (function == null) continue;
            if (function.has("name") && !function.get("name").isNull()) {
                toolCallAccumulator.get().append("__NAME__")
                        .append(function.get("name").asText())
                        .append("__NAME__");
            }
            if (function.has("arguments") && !function.get("arguments").isNull()) {
                toolCallAccumulator.get().append(function.get("arguments").asText());
            }
        }
    }

    /** 工具调用完成后，将累积的 name + arguments 组合成 {@code __TOOL_CALL__} 标记。 */
    private Flux<String> flushToolCall() {
        String accumulated = toolCallAccumulator.get().toString();
        toolCallAccumulator.get().setLength(0);
        if (accumulated.isEmpty()) {
            return Flux.empty();
        }
        int nameEnd = accumulated.indexOf("__NAME__", 8);
        if (nameEnd <= 0) {
            return Flux.empty();
        }
        String toolName = accumulated.substring(8, nameEnd);
        String jsonArgs = accumulated.substring(nameEnd + 8);
        if (jsonArgs.isEmpty()) {
            jsonArgs = "{}";
        }
        log.info("[DashScope] Tool call: {} args={}", toolName,
                jsonArgs.length() > 100 ? jsonArgs.substring(0, 100) + "..." : jsonArgs);
        return Flux.just("\n\n__TOOL_CALL__" + toolName + jsonArgs);
    }

    /** 读取 finish_reason（兼容 choice 与 output 两个层级）。 */
    private String readFinishReason(JsonNode choice, JsonNode output) {
        if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
            String fr = choice.get("finish_reason").asText();
            if (!"null".equals(fr)) return fr;
        }
        if (output.has("finish_reason") && !output.get("finish_reason").isNull()) {
            String fr = output.get("finish_reason").asText();
            if (!"null".equals(fr)) return fr;
        }
        return null;
    }

    // ============================================================
    //  请求体构建
    // ============================================================

    Map<String, Object> buildRequestBody(List<ChatMessage> messages,
                                         List<ToolSpecification> tools) {
        Map<String, Object> body = new LinkedHashMap<>();

        // P2-0.5C: 优先从 BenchmarkConfig（单一事实源）读取，降级到 application.yml
        BenchmarkConfig config = BenchmarkConfigHolder.get();
        String effectiveModel = (config != null && config.llmProvider() != null)
                ? config.llmProvider() : model;
        double effectiveTemperature = (config != null) ? config.temperature() : 0.1;

        body.put("model", effectiveModel);

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

        // P2-0.5C: temperature 从 BenchmarkConfig 读取（不再硬编码）
        params.put("temperature", effectiveTemperature);

        // P2-0.5C: topP 从 BenchmarkConfig 读取并发送（DashScope API 支持）
        if (config != null) {
            params.put("top_p", config.topP());
        }

        // P2-0.5C: maxTokens 从 BenchmarkConfig 读取并发送（DashScope API 支持）
        if (config != null && config.maxTokens() != null) {
            params.put("max_tokens", config.maxTokens());
        }

        // P2-0.5C: Qwen (DashScope) 不支持 seed 参数，明确记录
        // seed 仅在 BenchmarkConfig 中记录，不发送到 DashScope API

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
