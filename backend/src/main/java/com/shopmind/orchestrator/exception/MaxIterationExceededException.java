package com.shopmind.orchestrator.exception;

/**
 * 最大推理轮次超限异常 — §11 规范。
 * <p>
 * 降级策略：终止循环，截断并输出"系统当前遇到一些复杂情况，已为您转接人工客服。"
 */
public class MaxIterationExceededException extends RuntimeException {

    private final int currentIterations;
    private final int maxIterations;

    public MaxIterationExceededException(int currentIterations, int maxIterations) {
        super(String.format("推理轮次已达上限：%d/%d，可能存在死循环 Tool 调用。", currentIterations, maxIterations));
        this.currentIterations = currentIterations;
        this.maxIterations = maxIterations;
    }

    public int getCurrentIterations() { return currentIterations; }
    public int getMaxIterations() { return maxIterations; }
}
