package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.workflow.domain.ObservabilityMetrics;

/**
 * Raw 层 runtime metrics 快照 — Phase 5-B4。
 * <p>
 * 从 {@link ObservabilityMetrics} 抽取当前可观察的运行指标，作为 Raw record 的一部分。
 * 这些是<b>执行事实</b>（Runtime Observation），不是评估结论。
 * <p>
 * <b>单位：</b>所有 latency 均为毫秒（long）。
 *
 * @param totalLatencyMs   端到端总延迟（ms）
 * @param ttftMs           首字延迟 TTFT（ms）
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param toolCallCount    工具调用次数
 */
public record RtmpRawRuntimeMetrics(
        long totalLatencyMs,
        long ttftMs,
        int promptTokens,
        int completionTokens,
        int toolCallCount
) {

    /**
     * 从 {@link ObservabilityMetrics} 构建 runtime 快照（null 视为全 0）。
     */
    public static RtmpRawRuntimeMetrics from(ObservabilityMetrics m) {
        if (m == null) {
            return new RtmpRawRuntimeMetrics(0, 0, 0, 0, 0);
        }
        return new RtmpRawRuntimeMetrics(
                m.getTotalLatencyMs(),
                m.getTtftMs(),
                m.getPromptTokens(),
                m.getCompletionTokens(),
                m.getToolCallCount());
    }
}
