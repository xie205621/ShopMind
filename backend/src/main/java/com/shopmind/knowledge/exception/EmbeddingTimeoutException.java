package com.shopmind.knowledge.exception;

/**
 * Embedding API 超时异常（§12 规范）。
 * <p>
 * 降级策略：记录 Error 日志，跳过 RAG 环节，Agent 仅基于 Memory 继续对话。
 */
public class EmbeddingTimeoutException extends RuntimeException {

    public EmbeddingTimeoutException(String message) {
        super(message);
    }

    public EmbeddingTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
