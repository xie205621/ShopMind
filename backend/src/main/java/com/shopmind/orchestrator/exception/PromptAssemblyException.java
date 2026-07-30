package com.shopmind.orchestrator.exception;

/**
 * 上下文组装失败异常 — §11 规范。
 * <p>
 * 降级策略：终止 LLM 请求，返回友好提示"上下文加载失败，请重试"。
 */
public class PromptAssemblyException extends RuntimeException {

    public PromptAssemblyException(String message) {
        super(message);
    }

    public PromptAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
