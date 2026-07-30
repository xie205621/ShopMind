package com.shopmind.orchestrator.exception;

/**
 * LLM Provider 超时/不可用异常 — §11 规范。
 * <p>
 * 降级策略：通过 CircuitBreaker 统一返回流式降级提示。
 */
public class LlmProviderTimeoutException extends RuntimeException {

    public LlmProviderTimeoutException(String message) {
        super(message);
    }

    public LlmProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
