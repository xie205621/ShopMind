package com.shopmind.orchestrator.domain;

/**
 * 执行状态追踪器 — §8 规范。
 * <p>
 * 线程安全约束：此对象生命周期绑定于单次请求，仅在方法参数中传递，
 * 绝不可作为 @Component 单例的实例字段。
 */
public class ExecutionState {

    /** 当前所处的 Pipeline 阶段 */
    private ExecutionStep currentStep;

    /** Inner Loop 中已调用工具的次数（防死循环，默认上限 3 次） */
    private int toolCallCount;

    /** LLM 推理失败后的重试次数 */
    private int retryCount;

    /** 最终执行结果状态 */
    private ExecutionStatus status;

    public ExecutionState() {
        this.currentStep = ExecutionStep.INTENT_ANALYSIS;
        this.toolCallCount = 0;
        this.retryCount = 0;
        this.status = ExecutionStatus.RUNNING;
    }

    // ============================================================
    //  State mutation methods
    // ============================================================

    public void advanceTo(ExecutionStep step) {
        this.currentStep = step;
    }

    public void incrementToolCall() {
        this.toolCallCount++;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public void markSuccess() {
        this.status = ExecutionStatus.SUCCESS;
        this.currentStep = ExecutionStep.COMPLETE;
    }

    public void markFailed() {
        this.status = ExecutionStatus.FAILED;
    }

    public void markDegraded() {
        this.status = ExecutionStatus.DEGRADED;
    }

    // ============================================================
    //  Getters
    // ============================================================

    public ExecutionStep getCurrentStep() { return currentStep; }
    public int getToolCallCount() { return toolCallCount; }
    public int getRetryCount() { return retryCount; }
    public ExecutionStatus getStatus() { return status; }
}
