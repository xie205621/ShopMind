package com.shopmind.workflow.domain;

import com.shopmind.experiment.ControlType;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 单个 run 的 control overhead 聚合值 — Phase 5-B3。
 * <p>
 * 对某个 {@link ControlType} 的多次 {@link ControlOverheadEvent} 做 run-level 聚合，
 * 供 B4（落盘）/ evaluator / summary 消费。本阶段<b>不</b>计算 total-cost comparison，
 * 只提供原始 component measurements。
 * <p>
 * <b>no-invocation 语义（冻结）：</b>无调用时 {@code invocationCount=0}、{@code totalLatencyMs=0}，
 * token/cost 保持 {@code null}（unavailable 不得以 0 伪造真实观测）。
 *
 * @param controlType          SAFETY_VERIFIER / RTMP_ROUTER
 * @param invocationCount      该 control 的调用次数
 * @param totalLatencyMs       该 control 的总耗时（毫秒）
 * @param totalPromptTokens    输入 token 合计（全部 unavailable 时为 null）
 * @param totalCompletionTokens 输出 token 合计（全部 unavailable 时为 null）
 * @param totalTokens          总 token 合计（全部 unavailable 时为 null）
 * @param totalCost            cost 合计（全部 unavailable 时为 null）
 */
public record ControlOverhead(
        ControlType controlType,
        int invocationCount,
        long totalLatencyMs,
        Long totalPromptTokens,
        Long totalCompletionTokens,
        Long totalTokens,
        BigDecimal totalCost
) {

    /**
     * 聚合指定类型的事件为 run-level aggregate。
     *
     * @param type   目标 control 类型
     * @param events 该 run 的全部 control 事件（会按类型过滤）
     * @return run-level aggregate（无匹配事件时 count=0 / latency=0 / token=cost=null）
     */
    public static ControlOverhead aggregate(ControlType type, List<ControlOverheadEvent> events) {
        List<ControlOverheadEvent> scoped = events == null
                ? List.of()
                : events.stream().filter(e -> e != null && e.controlType() == type).toList();

        long latency = 0L;
        for (ControlOverheadEvent e : scoped) {
            latency += e.latencyMs();
        }

        return new ControlOverhead(
                type,
                scoped.size(),
                latency,
                sumOrNull(scoped, ControlOverheadEvent::promptTokens),
                sumOrNull(scoped, ControlOverheadEvent::completionTokens),
                sumOrNull(scoped, ControlOverheadEvent::totalTokens),
                costOrNull(scoped));
    }

    private static Long sumOrNull(List<ControlOverheadEvent> events,
                                  Function<ControlOverheadEvent, Long> getter) {
        boolean anyPresent = events.stream().anyMatch(e -> getter.apply(e) != null);
        if (!anyPresent) {
            return null;
        }
        long sum = 0L;
        for (ControlOverheadEvent e : events) {
            Long v = getter.apply(e);
            if (v != null) {
                sum += v;
            }
        }
        return sum;
    }

    private static BigDecimal costOrNull(List<ControlOverheadEvent> events) {
        boolean anyPresent = events.stream().anyMatch(e -> e.cost() != null);
        if (!anyPresent) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ControlOverheadEvent e : events) {
            if (e.cost() != null) {
                sum = sum.add(e.cost());
            }
        }
        return sum;
    }
}
