package com.shopmind.workflow.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一次 Tool Call 的 runtime observation — Phase 1 Evaluation/Instrumentation Foundation。
 * <p>
 * 记录系统在本次执行中<b>真实发生</b>的某一次工具调用事实，属于 Runtime Observation，
 * 不是 Evaluation Result。
 * <p>
 * 三个关键字段严格区分三层事实：
 * <ul>
 *   <li>{@code attemptedTool} — LLM 想调用什么工具（call attempt）</li>
 *   <li>{@code executedTool}  — 系统实际执行了什么工具（可为 null）</li>
 *   <li>{@code verifierBlocked} — 是否被 Post-hoc Verifier 拦截</li>
 * </ul>
 * <p>
 * 必须支持但不限于以下语义组合：
 * <ul>
 *   <li>A. attempted=refund, executed=refund, verifierBlocked=false</li>
 *   <li>B. attempted=refund, executed=null, verifierBlocked=true（Safety Intervention）</li>
 * </ul>
 * <p>
 * <b>重要：</b>{@code verifierBlocked == true && executedTool == null} 只能表示
 * Safety Intervention，不能直接表示 L3 Actual Safety Violation。
 * <p>
 * 一次 run 可包含<b>多个</b>有序 ToolCallEvent（多轮 / 多工具调用），
 * 禁止用 run-level 单值字段覆盖历史事实。未发生 Tool Call 时不应创建伪造的 null event。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param runId          关联的 canonical run_id（{@link RunIdentity#runId()}）
 * @param timestamp      事件发生时间戳
 * @param iteration      第几次工具调用（从 1 开始，保持有序序列）
 * @param attemptedTool  LLM 尝试调用的工具名（可为 null 表示无法确定）
 * @param executedTool   实际执行的工具名（被拦截或未执行时为 null）
 * @param verifierBlocked 是否被 Post-hoc Verifier 拦截
 * @param arguments      工具调用入参（结构化）
 * @param blockReason    拦截原因（未被拦截时为 null）
 * @param latencyMs      工具执行耗时（毫秒）
 */
public record ToolCallEvent(
        String runId,
        Instant timestamp,
        int iteration,
        String attemptedTool,
        String executedTool,
        boolean verifierBlocked,
        Map<String, Object> arguments,
        String blockReason,
        long latencyMs
) {

    /**
     * 紧凑构造器：防御性拷贝 arguments，确保不为 null。
     */
    public ToolCallEvent {
        arguments = arguments != null
                ? Collections.unmodifiableMap(new HashMap<>(arguments))
                : Collections.emptyMap();
    }

    /**
     * 便捷工厂：时间戳默认为当前时刻。
     */
    public static ToolCallEvent of(String runId, int iteration,
                                   String attemptedTool, String executedTool,
                                   boolean verifierBlocked,
                                   Map<String, Object> arguments, String blockReason, long latencyMs) {
        return new ToolCallEvent(runId, Instant.now(), iteration,
                attemptedTool, executedTool, verifierBlocked, arguments, blockReason, latencyMs);
    }
}