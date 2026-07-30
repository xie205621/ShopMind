package com.shopmind.mcp.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.mcp.McpEngine;
import com.shopmind.mcp.exception.ParameterBindingException;
import com.shopmind.mcp.exception.ToolNotFoundException;
import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.mcp.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP Engine 核心执行器 — MCP_Engine.md 第 10 节规范。
 * <p>
 * 实现完整的三步流程：
 * <ol>
 *   <li><b>工具发现</b>：从 ToolRegistry 获取所有已注册工具</li>
 *   <li><b>参数映射</b>：Jackson 反序列化 JSON → 强类型参数数组</li>
 *   <li><b>反射调用</b>：通过 Method.invoke() 执行 + 超时熔断</li>
 * </ol>
 * <p>
 * <b>异常降级策略（§11）：</b>
 * <ul>
 *   <li>ToolNotFoundException → 返回 "工具不存在，请重新规划"</li>
 *   <li>ParameterBindingException → 返回 "参数错误：xxx，请向用户追问"</li>
 *   <li>业务异常 → 捕获 Message 作为 Observation 反哺给 LLM</li>
 * </ul>
 */
@Component
public class McpExecutor implements McpEngine {

    private static final Logger log = LoggerFactory.getLogger(McpExecutor.class);

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;

    /** 工具调用超时时间，默认 3000ms（§6） */
    @Value("${shopmind.mcp.timeout-ms:3000}")
    private long timeoutMs;

    public McpExecutor(ToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    //  discoverTools — 工具发现（§3 功能需求1）
    // ============================================================

    @Override
    public List<ToolSpecification> discoverTools() {
        return registry.getAllTools();
    }

    // ============================================================
    //  executeTool — 路由与执行（§3 功能需求3）
    // ============================================================

    @Override
    public String executeTool(String toolName, String jsonArguments) {
        // Step 1 — 查表
        ToolSpecification spec = registry.getTool(toolName);
        if (spec == null) {
            // §11: ToolNotFoundException → 降级提示
            log.warn("[MCP] Tool '{}' not found. LLM may have hallucinated.", toolName);
            return "工具不存在，请重新规划";
        }

        // Step 2 — JSON 参数映射
        Object[] args;
        try {
            args = bindArguments(spec, jsonArguments);
        } catch (ParameterBindingException e) {
            // §11: 参数绑定失败 → 降级提示
            log.warn("[MCP] Parameter binding failed for tool '{}': {}", toolName, e.getMessage());
            return e.getMessage();
        } catch (Exception e) {
            log.error("[MCP] Unexpected error during argument binding for tool '{}'", toolName, e);
            return "参数解析异常：" + e.getMessage() + "，请向用户追问";
        }

        // Step 3 — 反射调用 + 超时熔断
        return invokeWithTimeout(spec, args);
    }

    // ============================================================
    //  bindArguments — JSON → Java 参数数组（§3 功能需求2）
    // ============================================================

    /**
     * 将 LLM 传入的 JSON 字符串按 {@link ParameterSpec} 定义，
     * 逐一映射为目标方法参数数组。
     */
    private Object[] bindArguments(ToolSpecification spec, String jsonArguments) throws Exception {
        List<ParameterSpec> paramSpecs = spec.getParameters();
        Method method = spec.getTargetMethod();
        Class<?>[] paramTypes = method.getParameterTypes();

        // 无参方法
        if (paramTypes.length == 0) {
            return new Object[0];
        }

        // 解析 JSON 为 Map<String, Object>
        if (jsonArguments == null || jsonArguments.isBlank()) {
            jsonArguments = "{}";
        }

        Map<String, Object> jsonMap;
        try {
            jsonMap = objectMapper.readValue(jsonArguments,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ParameterBindingException(
                    "参数格式错误：无法解析 JSON，请检查参数格式后重试");
        }

        // 逐参数映射：paramSpecs[i].name → paramTypes[i]
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            ParameterSpec ps = paramSpecs.get(i);
            Object rawValue = jsonMap.get(ps.getName());

            // 必填校验
            if (ps.isRequired() && (rawValue == null || rawValue.toString().isBlank())) {
                throw new ParameterBindingException(
                        "参数错误：缺少 " + ps.getName() + getParamHint(ps) + "，请向用户追问");
            }

            // 类型转换（Jackson 已做基础转换，这里处理 String→数字等边界情况）
            try {
                args[i] = convertValue(rawValue, paramTypes[i]);
            } catch (Exception e) {
                throw new ParameterBindingException(
                        "参数类型错误：" + ps.getName() + " 应为 " + ps.getType()
                                + getParamHint(ps) + "，请向用户追问");
            }
        }
        return args;
    }

    // ============================================================
    //  invokeWithTimeout — 反射调用 + 超时熔断（§4 + §6）
    // ============================================================

    /**
     * 通过 {@link Method#invoke} 反射调用目标方法，
     * 并使用 {@link CompletableFuture#orTimeout} 实现超时熔断。
     */
    @SuppressWarnings("unchecked")
    private String invokeWithTimeout(ToolSpecification spec, Object[] args) {
        Method method = spec.getTargetMethod();
        Object bean = spec.getTargetBean();

        try {
            // 使用 CompletableFuture 包装反射调用，设置超时
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    method.setAccessible(true);
                    return method.invoke(bean, args);
                } catch (Exception e) {
                    // 解包 InvocationTargetException，取业务根因
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        throw new BusinessInvocationException(cause);
                    }
                    throw new BusinessInvocationException(e);
                }
            });

            Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result != null ? result.toString() : "";

        } catch (TimeoutException e) {
            // §6 + §4: 超时熔断 → 返回降级提示
            log.error("[MCP] Tool '{}' execution timed out after {}ms",
                    spec.getToolName(), timeoutMs);
            return "工具执行超时，请稍后重试";

        } catch (ExecutionException e) {
            // §11: 业务方法抛出异常 → 捕获 Message 反哺给 LLM
            Throwable cause = e.getCause();
            if (cause instanceof BusinessInvocationException) {
                Throwable root = ((BusinessInvocationException) cause).getCause();
                log.warn("[MCP] Biz exception in tool '{}': {}", spec.getToolName(),
                        root != null ? root.getMessage() : cause.getMessage());
                return root != null ? root.getMessage() : cause.getMessage();
            }
            log.error("[MCP] Unexpected error during tool '{}' execution", spec.getToolName(), e);
            return "工具执行异常：" + e.getMessage();

        } catch (Exception e) {
            log.error("[MCP] Unexpected error for tool '{}'", spec.getToolName(), e);
            return "工具执行异常，请稍后重试";
        }
    }

    // ============================================================
    //  convertValue — 类型转换辅助
    // ============================================================

    /**
     * 将 JSON 解析出的原始值转换为目标 Java 类型。
     * <p>
     * ObjectMapper 解析时已将数字转为 Integer/Long/Double，
     * 这里处理跨类型转换（如 Integer ← Long, String → int）。
     */
    private Object convertValue(Object rawValue, Class<?> targetType) {
        if (rawValue == null) {
            return null;
        }

        // 已经是目标类型，直接返回
        if (targetType.isInstance(rawValue)) {
            return rawValue;
        }

        // 基础数值类型转换
        String strValue = rawValue.toString();
        if (targetType == String.class) {
            return strValue;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(strValue);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(strValue);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.valueOf(strValue);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(strValue);
        }

        // 复杂对象：尝试 Jackson 转换
        if (rawValue instanceof Map) {
            return objectMapper.convertValue(rawValue, targetType);
        }

        return rawValue;
    }

    /**
     * 构建参数描述提示（用于错误消息中补充上下文）。
     */
    private String getParamHint(ParameterSpec ps) {
        if (ps.getDescription() != null && !ps.getDescription().isBlank()) {
            return "（" + ps.getDescription() + "）";
        }
        return "";
    }

    /**
     * 内部异常包装类，用于在 CompletableFuture 中将反射调用异常
     * 与 CompletableFuture 自身的执行异常区分开。
     */
    private static class BusinessInvocationException extends RuntimeException {
        BusinessInvocationException(Throwable cause) {
            super(cause);
        }
    }
}
