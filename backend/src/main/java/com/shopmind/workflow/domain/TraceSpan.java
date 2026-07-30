package com.shopmind.workflow.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行步骤 Span — Workflow_Engine.md §6.3 规范。
 * <p>
 * 对应 OpenTelemetry 中的 Span 概念，记录单个 Pipeline 步骤的输入、输出、
 * 耗时和置信度。每个 Span 由 {@link com.shopmind.workflow.port.TraceRecorder.TraceHandle}
 * 创建并聚合到 {@link ExecutionTrace} 中。
 * <p>
 * 线程安全：本类实例仅在单次请求内创建和使用，不跨线程共享。
 */
public class TraceSpan {

    /** 步骤名称（如 "INTENT_ANALYSIS", "KNOWLEDGE_RETRIEVAL"） */
    private final String stepName;

    /** 步骤耗时（毫秒） */
    private final long latencyMs;

    /** 结构化输入参数 */
    private final Map<String, Object> input;

    /** 结构化输出结果 */
    private final Map<String, Object> output;

    /** 步骤置信度或分数（如 RAG Score，范围 0.0 ~ 1.0） */
    private final double confidence;

    /** Span 创建时间戳 */
    private final Instant timestamp;

    public TraceSpan(String stepName, long latencyMs,
                     Map<String, Object> input,
                     Map<String, Object> output,
                     double confidence) {
        this.stepName = stepName;
        this.latencyMs = latencyMs;
        this.input = input != null ? Collections.unmodifiableMap(new HashMap<>(input)) : Collections.emptyMap();
        this.output = output != null ? Collections.unmodifiableMap(new HashMap<>(output)) : Collections.emptyMap();
        this.confidence = confidence;
        this.timestamp = Instant.now();
    }

    /**
     * 快速构造一个无输入输出的 Span（仅记录耗时）。
     */
    public static TraceSpan metricOnly(String stepName, long latencyMs, double confidence) {
        return new TraceSpan(stepName, latencyMs, Collections.emptyMap(), Collections.emptyMap(), confidence);
    }

    // ============================================================
    //  Getters
    // ============================================================

    public String getStepName() { return stepName; }
    public long getLatencyMs() { return latencyMs; }
    public Map<String, Object> getInput() { return input; }
    public Map<String, Object> getOutput() { return output; }
    public double getConfidence() { return confidence; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "TraceSpan{step='" + stepName + "', latency=" + latencyMs + "ms, confidence=" + confidence + "}";
    }
}
