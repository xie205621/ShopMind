package com.shopmind.workflow.port;

import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.ObservabilityMetrics;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.TraceSpan;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 执行留痕记录器接口 — Workflow_Engine.md §7.3 规范（v2.1 重构）。
 * <p>
 * <b>核心架构决策：</b>
 * 摒弃了 v2.0 中的命令式状态累积模式（{@code Map<String, ExecutionTrace>}），
 * 改为<b>请求级 TraceHandle 响应式模式</b>。每次请求通过 {@link #createTrace} 创建一个
 * 独立的 TraceHandle 实例，所有 Trace 状态自包含在该实例内部。
 * <p>
 * <b>为什么这样设计？</b>
 * <ul>
 *   <li><b>消除内存泄漏：</b>不持有全局 Map，客户端断连、Flux cancel、异常等情况
 *       不会导致 Trace 对象残留在 JVM 堆中</li>
 *   <li><b>线程安全：</b>每个请求独享一个 TraceHandle，无跨请求共享状态</li>
 *   <li><b>响应式友好：</b>通过 Reactor ContextView 透传 TraceHandle，支持
 *       {@code doFinally} 确保全路径落盘</li>
 * </ul>
 * <p>
 * <b>在 Agent Orchestrator 中的集成方式：</b>
 * <pre>{@code
 * TraceHandle trace = traceRecorder.createTrace(request.memoryId(), workflowVersion);
 * return Mono.just(ctx)
 *     .flatMap(...) // pipeline steps
 *     .doFinally(signal -> {
 *         trace.markComplete(deriveStatus(signal));
 *         traceRecorder.save(trace).subscribe();
 *     });
 * }</pre>
 */
public interface TraceRecorder {

    /**
     * 创建一个请求级 TraceHandle。
     * <p>
     * 每次调用都 new 一个全新的 TraceHandle 实例，内部创建 {@code ExecutionTrace} 对象。
     * TraceHandle 不依赖任何外部共享状态，即使调用方忘记调用 {@link #save(TraceHandle)}，
     * 也只会在下次 GC 时回收该 TraceHandle（无泄漏累积效应）。
     *
     * @param memoryId        会话记忆 ID
     * @param workflowVersion 关联的工作流版本号
     * @return 新创建的 TraceHandle，ready to use
     */
    TraceHandle createTrace(String memoryId, String workflowVersion);

    /**
     * 创建一个带 canonical run identity 的请求级 TraceHandle。
     * <p>
     * 当提供 {@code runIdentity} 时，内部的 {@link ExecutionTrace} 会强制
     * {@code memoryId == runIdentity.memoryId()}，并可通过
     * {@link TraceHandle#getExecutionTrace()} 获取同一 canonical instance，
     * 供 runtime mutation 与最终 save 共用。
     *
     * @param memoryId        会话记忆 ID（当 runIdentity 非 null 时会被其覆盖）
     * @param workflowVersion 关联的工作流版本号
     * @param runIdentity     canonical run identity（可为 null，兼容 legacy）
     * @return 新创建的 TraceHandle
     */
    default TraceHandle createTrace(String memoryId, String workflowVersion, RunIdentity runIdentity) {
        // 默认实现：忽略 runIdentity，保持 legacy 行为。
        // InMemoryTraceRecorder 会覆盖此方法以真正承载 RunIdentity。
        return createTrace(memoryId, workflowVersion);
    }

    /**
     * 异步落盘 Trace 数据。
     * <p>
     * 返回 {@link Mono<Void>} 支持 Reactive MongoDB 非阻塞写入。
     * 调用方应在 {@code Flux/Mono.doFinally()} 中调用此方法，
     * 确保 cancel / error / complete 三种信号类型均能触发落盘。
     * <p>
     * <b>异常处理：</b>MongoDB 写入失败时，实现类应 catch 异常、打 Error 日志，
     * 并返回 {@code Mono.empty()}，绝不抛异常阻断主干对话流。
     *
     * @param trace 待持久化的 TraceHandle
     * @return 代表落盘完成的 Mono
     */
    Mono<Void> save(TraceHandle trace);

    // ============================================================
    //  TraceHandle — 请求级 Trace 句柄（响应式安全）
    // ============================================================

    /**
     * 请求级 Trace 句柄 — 响应式并发安全。
     * <p>
     * 每个请求 new 一个实例，通过 Reactor {@code ContextView} 在操作符链中透传。
     * 内部持有本次请求的完整 {@code ExecutionTrace} 对象，
     * 不依赖任何外部共享状态，即使 Flux 被 cancel 也不会泄漏内存。
     * <p>
     * <b>生命周期：</b>
     * <ol>
     *   <li>{@link TraceRecorder#createTrace} 创建</li>
     *   <li>各 PipelineStep 通过 Reactor Context 获取并追加 Span</li>
     *   <li>Flux complete/error/cancel → {@link #markComplete} + {@link TraceRecorder#save}</li>
     *   <li>GC 回收</li>
     * </ol>
     */
    interface TraceHandle {

        /**
         * 添加一个执行步骤 Span。
         *
         * @param stepName   步骤名称（如 "INTENT_ANALYSIS", "LLM_INFERENCE"）
         * @param latencyMs  步骤耗时（毫秒）
         * @param input      结构化输入参数
         * @param output     结构化输出结果
         * @param confidence 步骤置信度（0.0 ~ 1.0）
         */
        void addSpan(String stepName, long latencyMs,
                     Map<String, Object> input,
                     Map<String, Object> output,
                     double confidence);

        /**
         * 快速添加一个仅记录耗时的 Span（无输入输出）。
         */
        default void addMetricSpan(String stepName, long latencyMs) {
            addSpan(stepName, latencyMs, Map.of(), Map.of(), 1.0);
        }

        /**
         * 设置聚合指标（通常在 Trace 结束时一次性设置所有指标）。
         */
        void setMetrics(ObservabilityMetrics metrics);

        /**
         * 获取当前聚合指标对象（用于逐步累加 token 数、工具调用次数等）。
         * <p>
         * 返回值是可变对象的引用，调用方可以直接调用
         * {@code getMetrics().accumulateCompletionToken()} 等方法来逐步累加。
         *
         * @return 可变的 ObservabilityMetrics 对象
         */
        ObservabilityMetrics getMetrics();

        /**
         * 获取全局唯一的 Trace ID。
         */
        String getTraceId();

        /**
         * 获取当前 TraceHandle 内部持有的 canonical {@link ExecutionTrace} 实例。
         * <p>
         * 该实例是本次 run 的<b>唯一</b> Trace：runtime mutation、evaluator 与
         * final save 都必须围绕同一个 instance 工作，禁止再自行 {@code new} 一份。
         *
         * @return canonical ExecutionTrace 实例
         */
        ExecutionTrace getExecutionTrace();

        /**
         * 获取已记录的所有 Span（用于最终落盘前的序列化）。
         *
         * @return 不可变的 Span 列表
         */
        List<TraceSpan> getSpans();

        /**
         * 标记 Trace 结束，设置最终状态和结束时间。
         * 调用此方法后，Trace 状态从 RUNNING 变为 SUCCESS / FAILED 等。
         */
        void markComplete(ExecutionStatus status);
    }
}
