package com.shopmind.workflow.exception;

/**
 * 工作流渲染异常 — Workflow_Engine.md §8 规范。
 * <p>
 * 当 WorkflowDefinition 缺少必填字段（如 Persona）或
 * 渲染模板引擎发生故障时抛出。
 * <p>
 * <b>降级策略：</b>终止 LLM 请求，返回系统异常提示给用户。
 */
public class WorkflowRenderException extends RuntimeException {

    public WorkflowRenderException(String message) {
        super(message);
    }

    public WorkflowRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
