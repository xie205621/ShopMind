package com.shopmind.workflow.exception;

/**
 * Trace 异步落盘异常 — Workflow_Engine.md §8 规范。
 * <p>
 * 当 MongoDB 异步写入 ExecutionTrace 失败时抛出。
 * <p>
 * <b>降级策略：</b>捕获异常，转储到本地 Error 日志，
 * <b>绝对禁止阻断用户的正常对话流</b>。
 */
public class TraceSaveException extends RuntimeException {

    public TraceSaveException(String message) {
        super(message);
    }

    public TraceSaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
