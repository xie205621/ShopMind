package com.shopmind.orchestrator.pipeline;

import com.shopmind.orchestrator.domain.ExecutionState;
import com.shopmind.orchestrator.exception.MaxIterationExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具迭代拦截器 — §10 规范。
 * <p>
 * 检查 Inner Loop 中已调用工具次数是否超过 {@code maxIterations}（默认 3）。
 * 若超限则抛出 {@link MaxIterationExceededException}，由 Orchestrator 全局异常处理接管。
 * <p>
 * 线程安全：无状态 @Component，所有数据通过方法参数传入。
 */
@Component
public class ToolIterationGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolIterationGuard.class);

    /** 允许的最大推理轮次（可通过 {@code shopmind.orchestrator.max-iterations} 覆盖） */
    private final int maxIterations;

    public ToolIterationGuard(
            @Value("${shopmind.orchestrator.max-iterations:3}") int maxIterations) {
        this.maxIterations = maxIterations;
        log.info("[Orchestrator] ToolIterationGuard initialized: maxIterations={}", maxIterations);
    }

    /**
     * 检查工具调用次数是否越界。
     *
     * @param state 当前执行状态
     * @throws MaxIterationExceededException 超过最大迭代次数时抛出
     */
    public void check(ExecutionState state) {
        if (state.getToolCallCount() >= maxIterations) {
            log.warn("[Orchestrator] Max iterations reached: {}/{}. Terminating inner loop.",
                    state.getToolCallCount(), maxIterations);
            throw new MaxIterationExceededException(state.getToolCallCount(), maxIterations);
        }
        log.debug("[Orchestrator] Iteration guard OK: {}/{}", state.getToolCallCount(), maxIterations);
    }

    /**
     * 校验并增加计数（原子化操作）。
     * 先 check 再 increment，check 失败会抛异常，不会污染状态。
     */
    public void checkAndIncrement(ExecutionState state) {
        check(state);
        state.incrementToolCall();
    }
}
