package com.shopmind.knowledge.exception;

/**
 * 向量库连接异常（§12 规范）。
 * <p>
 * 降级策略：熔断 RAG 链路，保障电商主链路存活。
 * Agent 仅使用 Memory Engine 提供的上下文继续服务。
 */
public class VectorStoreConnectionException extends RuntimeException {

    public VectorStoreConnectionException(String message) {
        super(message);
    }

    public VectorStoreConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
