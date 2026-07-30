package com.shopmind.orchestrator.domain;

/**
 * 编排请求 — 不可变输入（§8 规范）。
 * <p>
 * 在系统各层传递时不携带可变状态，遵循 DDD 值对象模式。
 * 使用 Java record 确保 immutability。
 *
 * @param memoryId   租户会话唯一标识（由 Memory Engine 管理）
 * @param userMessage 用户当前轮输入的自然语言文本
 */
public record OrchestrationRequest(
        String memoryId,
        String userMessage
) {}
