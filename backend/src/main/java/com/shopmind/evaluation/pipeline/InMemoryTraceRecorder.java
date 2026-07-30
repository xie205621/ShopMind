package com.shopmind.evaluation.pipeline;

import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.ObservabilityMetrics;
import com.shopmind.workflow.domain.TraceSpan;
import com.shopmind.workflow.port.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TraceRecorder 内存实现 — 用于评测场景和单元测试。
 * <p>
 * 不依赖 MongoDB，所有 Trace 数据仅存储在 JVM 堆中。
 * 提供 {@link #getAllSavedTraces()} 方法供评测结束后批量提取 Trace 数据。
 * <p>
 * <b>生产环境替代：</b>ReactiveMongoTraceRecorder（异步写入 MongoDB）。
 * <p>
 * <b>线程安全：</b>使用 {@link ConcurrentHashMap} 存储已落盘的 Trace 摘要数据。
 * TraceHandle 实例本身每次请求 new，不跨线程共享。
 */
@Component
public class InMemoryTraceRecorder implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTraceRecorder.class);

    /** 已保存的 Trace 数据（traceId → ExecutionTrace 引用） */
    private final Map<String, ExecutionTrace> savedTraces = new ConcurrentHashMap<>();

    @Override
    public TraceHandle createTrace(String memoryId, String workflowVersion) {
        String traceId = UUID.randomUUID().toString();
        ExecutionTrace trace = new ExecutionTrace(traceId, memoryId, workflowVersion);
        return new InMemoryTraceHandle(trace);
    }

    @Override
    public Mono<Void> save(TraceHandle handle) {
        if (!(handle instanceof InMemoryTraceHandle h)) {
            log.warn("[InMemoryTraceRecorder] Unknown TraceHandle type: {}", handle.getClass());
            return Mono.empty();
        }
        savedTraces.put(handle.getTraceId(), h.trace);
        log.debug("[InMemoryTraceRecorder] Trace saved: traceId={}, spans={}",
                handle.getTraceId(), h.trace.getSpans().size());
        return Mono.empty();
    }

    /**
     * 获取所有已保存的 ExecutionTrace 列表。
     * 通常在 Benchmark 评测结束后调用，用于进一步分析。
     */
    public List<ExecutionTrace> getAllSavedTraces() {
        return List.copyOf(savedTraces.values());
    }

    /**
     * 根据 traceId 获取已保存的 Trace。
     */
    public ExecutionTrace getTrace(String traceId) {
        return savedTraces.get(traceId);
    }

    /** 清空所有已保存的 Trace */
    public void clear() {
        savedTraces.clear();
    }

    // ============================================================
    //  InMemoryTraceHandle — 请求级 Trace 句柄
    // ============================================================

    /**
     * 请求级 Trace 句柄的内存实现。
     * 直接包装 ExecutionTrace 对象，所有操作委托给 ExecutionTrace。
     */
    private static class InMemoryTraceHandle implements TraceHandle {

        final ExecutionTrace trace;

        InMemoryTraceHandle(ExecutionTrace trace) {
            this.trace = trace;
        }

        @Override
        public void addSpan(String stepName, long latencyMs,
                            Map<String, Object> input,
                            Map<String, Object> output,
                            double confidence) {
            trace.addSpan(new TraceSpan(stepName, latencyMs, input, output, confidence));
        }

        @Override
        public void setMetrics(ObservabilityMetrics metrics) {
            // 直接替换：将 metrics 的所有字段拷贝到 trace 的 metrics 中
            ObservabilityMetrics tMetrics = trace.getMetrics();
            tMetrics.setTtftMs(metrics.getTtftMs());
            tMetrics.setTotalLatencyMs(metrics.getTotalLatencyMs());
            tMetrics.setPromptTokens(metrics.getPromptTokens());
            tMetrics.setCompletionTokens(metrics.getCompletionTokens());
            tMetrics.setRetrievedChunksCount(metrics.getRetrievedChunksCount());
            tMetrics.setToolCallCount(metrics.getToolCallCount());
        }

        @Override
        public ObservabilityMetrics getMetrics() {
            return trace.getMetrics();
        }

        @Override
        public String getTraceId() {
            return trace.getTraceId();
        }

        @Override
        public List<TraceSpan> getSpans() {
            return trace.getSpans();
        }

        @Override
        public void markComplete(ExecutionStatus status) {
            trace.markComplete(status);
        }
    }
}
