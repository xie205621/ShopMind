package com.shopmind.mcp.exception;

/**
 * 参数绑定异常 — LLM 生成的 JSON 缺少必填参数或类型不匹配。
 * <p>
 * 降级策略（MCP_Engine.md §11）：向 LLM 返回 "参数错误：缺少 xxx，请向用户追问"。
 */
public class ParameterBindingException extends RuntimeException {

    public ParameterBindingException(String message) {
        super(message);
    }

    public ParameterBindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
