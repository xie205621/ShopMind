package com.shopmind.workflow.domain;

import com.shopmind.orchestrator.domain.ExecutionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行留痕 — Workflow_Engine.md §6.3 规范。
 * <p>
 * 一次完整对话的推理轨迹记录，包含所有 Span、聚合指标和时间边界。
 * 由 {@link com.shopmind.workflow.port.TraceRecorder.TraceHandle} 在请求生命周期内
 * 逐步构建，最终通过 {@code TraceRecorder.save()} 异步写入 MongoDB。
 * <p>
 * <b>线程安全：</b>本类实例为单次请求独享，通过 TraceHandle 内部持有。
 * 不存在跨请求共享，因此无需同步机制。
 * <p>
 * <b>设计原则：</b>
 * <ul>
 *   <li>对齐 OpenTelemetry Span 模型（步骤级 Span → 请求级 Trace）</li>
 *   <li>包含时间边界和最终状态，支持完整的请求复现</li>
 *   <li>所有字段在构造时确定或通过包内方法逐步填充</li>
 * </ul>
 */
public class ExecutionTrace {

    /** 全局唯一 Trace ID（UUID v4） */
    private final String traceId;

    /** 会话记忆 ID */
    private final String memoryId;

    /** 关联的工作流版本号 */
    private final String workflowVersion;

    /** Trace 开始时间 */
    private final Instant startTime;

    /** Trace 结束时间（在 markComplete 时设置） */
    private Instant endTime;

    /** 最终执行状态 */
    private ExecutionStatus status;

    /** 聚合指标 */
    private final ObservabilityMetrics metrics;

    /** 步骤 Span 列表 */
    private final List<TraceSpan> spans;

    public ExecutionTrace(String traceId, String memoryId, String workflowVersion) {
        this.traceId = traceId;
        this.memoryId = memoryId;
        this.workflowVersion = workflowVersion;
        this.startTime = Instant.now();
        this.status = ExecutionStatus.RUNNING;
        this.metrics = ObservabilityMetrics.empty();
        this.spans = new ArrayList<>();
    }

    // ============================================================
    //  Mutation methods (package-private: only TraceHandle calls)
    // ============================================================

    /**
     * 添加一个执行步骤 Span。
     */
    public void addSpan(TraceSpan span) {
        if (span != null) {
            this.spans.add(span);
        }
    }

    /**
     * 标记 Trace 完成，设置结束时间和最终状态。
     */
    public void markComplete(ExecutionStatus status) {
        this.endTime = Instant.now();
        this.status = status;
        // 自动计算端到端延迟
        this.metrics.setTotalLatencyMs(endTime.toEpochMilli() - startTime.toEpochMilli());
    }

    // ============================================================
    //  Getters
    // ============================================================

    public String getTraceId() { return traceId; }
    public String getMemoryId() { return memoryId; }
    public String getWorkflowVersion() { return workflowVersion; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public ExecutionStatus getStatus() { return status; }
    public ObservabilityMetrics getMetrics() { return metrics; }
    public List<TraceSpan> getSpans() { return Collections.unmodifiableList(spans); }

    /**
     * 获取端到端总延迟（毫秒）。若尚未 markComplete，返回从 startTime 到当前的时间差。
     */
    public long getTotalLatencyMs() {
        if (endTime != null) {
            return endTime.toEpochMilli() - startTime.toEpochMilli();
        }
        return System.currentTimeMillis() - startTime.toEpochMilli();
    }

    @Override
    public String toString() {
        return "ExecutionTrace{" +
                "traceId='" + traceId + '\'' +
                ", memoryId='" + memoryId + '\'' +
                ", version='" + workflowVersion + '\'' +
                ", status=" + status +
                ", spans=" + spans.size() +
                ", metrics=" + metrics +
                '}';
    }
}
