package com.shopmind.workflow.domain;

import com.shopmind.experiment.ControlType;
import com.shopmind.orchestrator.domain.ExecutionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /**
     * Reactor Context 键 — 用于在 BenchmarkRunner → Agent Orchestrator 之间
     * 透传 canonical {@link ExecutionTrace} 实例，确保 runtime mutation、
     * evaluator 与 save 围绕同一个 Trace instance 工作。
     */
    public static final String CONTEXT_KEY = "com.shopmind.workflow.domain.ExecutionTrace";

    /** 全局唯一 Trace ID（UUID v4） */
    private final String traceId;

    /** 会话记忆 ID */
    private final String memoryId;

    /** 关联的工作流版本号 */
    private final String workflowVersion;

    /** Canonical run identity（可为 null，兼容 legacy benchmark 场景） */
    private final RunIdentity runIdentity;

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

    /** 有序 Runtime Tool Call 事件列表 */
    private final List<ToolCallEvent> toolCallEvents;

    /** 有序 RTMP Router pruning 决策列表（每次 LLM 迭代一个） */
    private final List<PruningEvent> pruningEvents;

    /** 有序 Control Overhead 观测列表（每次 Verifier / Router invocation 一个） */
    private final List<ControlOverheadEvent> controlOverheadEvents;

    public ExecutionTrace(String traceId, String memoryId, String workflowVersion) {
        this(traceId, memoryId, workflowVersion, null);
    }

    /**
     * 构造带 canonical run identity 的执行留痕。
     * <p>
     * 当提供 {@code runIdentity} 时，memoryId 会被强制覆盖为
     * {@code runIdentity.memoryId()}（即 run_id），以满足 {@code memoryId == runId} 约束。
     */
    public ExecutionTrace(String traceId, String memoryId, String workflowVersion, RunIdentity runIdentity) {
        this.traceId = traceId;
        this.workflowVersion = workflowVersion;
        this.runIdentity = runIdentity;
        this.memoryId = runIdentity != null ? runIdentity.memoryId() : memoryId;
        this.startTime = Instant.now();
        this.status = ExecutionStatus.RUNNING;
        this.metrics = ObservabilityMetrics.empty();
        this.spans = new ArrayList<>();
        this.toolCallEvents = new ArrayList<>();
        this.pruningEvents = new ArrayList<>();
        this.controlOverheadEvents = new ArrayList<>();
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
     * 追加一个有序 Tool Call runtime event。
     * <p>
     * Event 是执行事实；追加顺序即事件发生顺序，调用方需保证按发生顺序追加，
     * 以便后续从 Raw 数据重建完整的多轮 / 多工具调用序列。
     */
    public void addToolCallEvent(ToolCallEvent event) {
        if (event != null) {
            this.toolCallEvents.add(event);
        }
    }

    /**
     * 追加一个有序 RTMP Router pruning 决策。
     * <p>
     * 追加顺序即 Router 调用顺序（每次 LLM 迭代产生一个），调用方需保证按发生顺序追加。
     */
    public void addPruningEvent(PruningEvent event) {
        if (event != null) {
            this.pruningEvents.add(event);
        }
    }

    /**
     * 追加一个有序 Control Overhead 观测（每次 Verifier / Router invocation 一个）。
     * <p>
     * 追加顺序即 control invocation 发生顺序，调用方需保证按发生顺序追加。
     */
    public void addControlOverheadEvent(ControlOverheadEvent event) {
        if (event != null) {
            this.controlOverheadEvents.add(event);
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
    public RunIdentity getRunIdentity() { return runIdentity; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public ExecutionStatus getStatus() { return status; }
    public ObservabilityMetrics getMetrics() { return metrics; }
    public List<TraceSpan> getSpans() { return Collections.unmodifiableList(spans); }
    public List<ToolCallEvent> getToolCallEvents() { return Collections.unmodifiableList(toolCallEvents); }
    public List<PruningEvent> getPruningEvents() { return Collections.unmodifiableList(pruningEvents); }

    /** 返回本 run 的全部 control overhead 观测（有序，按发生顺序）。 */
    public List<ControlOverheadEvent> getControlOverheadEvents() {
        return Collections.unmodifiableList(controlOverheadEvents);
    }

    /**
     * 聚合指定 control 类型的 run-level overhead（B4 / evaluator 消费）。
     *
     * @param type SAFETY_VERIFIER / RTMP_ROUTER
     * @return 聚合值（无匹配事件时 count=0 / latency=0 / token=cost=null）
     */
    public ControlOverhead controlOverhead(ControlType type) {
        return ControlOverhead.aggregate(type, controlOverheadEvents);
    }

    /** 返回两种 control 类型的 run-level 聚合值映射。 */
    public Map<ControlType, ControlOverhead> controlOverheads() {
        return Map.of(
                ControlType.SAFETY_VERIFIER, controlOverhead(ControlType.SAFETY_VERIFIER),
                ControlType.RTMP_ROUTER, controlOverhead(ControlType.RTMP_ROUTER));
    }

    /**
     * 获取 canonical run_id。若未关联 {@link RunIdentity}（legacy benchmark），返回 null。
     */
    public String getRunId() {
        return runIdentity != null ? runIdentity.runId() : null;
    }

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
                ", runId='" + getRunId() + '\'' +
                ", memoryId='" + memoryId + '\'' +
                ", version='" + workflowVersion + '\'' +
                ", status=" + status +
                ", spans=" + spans.size() +
                ", toolCallEvents=" + toolCallEvents.size() +
                ", pruningEvents=" + pruningEvents.size() +
                ", controlOverheadEvents=" + controlOverheadEvents.size() +
                ", metrics=" + metrics +
                '}';
    }
}
