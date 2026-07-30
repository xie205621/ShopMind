package com.shopmind.orchestrator.domain;

/**
 * 编排执行状态枚举 — §8 规范，替代 String 类型。
 * <p>
 * RUNNING:  正常执行中
 * SUCCESS:  全部步骤顺利完成
 * FAILED:   非降级异常导致的失败
 * DEGRADED: 发生降级（如 RAG 不可用、LLM 超时），但核心对话链路仍存活
 */
public enum ExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    DEGRADED
}
