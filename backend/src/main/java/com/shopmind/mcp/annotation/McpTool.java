package com.shopmind.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具注解 — 标记对外暴露给 AI 大模型调用的业务方法。
 * <p>
 * 系统启动时，由 {@link com.shopmind.mcp.registry.ToolRegistry} 自动扫描并注册。
 * 所有被此注解标记的方法，其签名和描述将被提取为 JSON Schema 供 LLM 理解。
 *
 * <pre>
 * &#64;McpTool(name = "confirmPayment", description = "执行模拟付款，需提供订单号")
 * public String payOrder(&#64;McpParam(required = true, description = "18位订单编号") String orderNo) {
 *     // ...
 * }
 * </pre>
 *
 * @see com.shopmind.mcp.annotation.McpParam
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {

    /**
     * 工具名称，必须全局唯一。
     * LLM 通过此名称来指定要调用的工具。
     */
    String name();

    /**
     * 工具描述，详细说明该工具的功能和用途。
     * 描述越清晰，LLM 的意图识别越准确。
     */
    String description();
}
