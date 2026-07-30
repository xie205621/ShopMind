package com.shopmind.evaluation.port;

import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.workflow.domain.ExecutionTrace;
import reactor.core.publisher.Mono;

/**
 * 指标评估器接口 — 6_Evaluation_Engine.md §6.2 规范（v2.1 异步化）。
 * <p>
 * 从 Workflow Engine 产生的 {@link ExecutionTrace} 中提取各维度数据：
 * <ul>
 *   <li>从 TraceSpan 中解析意图分类结果 → 与 TestCase.expectedIntent 对比</li>
 *   <li>从 TraceSpan 中检查工具调用记录 → 与 TestCase.expectedTool 对比</li>
 *   <li>从 ObservabilityMetrics 中提取 TTFT、Token 数、知识命中块数</li>
 *   <li>从 ExecutionTrace 中获取最终状态和延迟</li>
 * </ul>
 * <p>
 * <b>异步约束（v2.1）：</b>返回 {@link Mono}{@code <TestCaseResult>}，
 * 即使当前实现是纯 CPU 计算（非 I/O），也使用 {@code Mono.just()} 包裹。
 * 这保证了接口的向后兼容性——未来如果引入外部评判服务，无需修改接口签名。
 * <p>
 * <b>线程安全：</b>实现类应为无状态 {@code @Component} 单例。
 */
@FunctionalInterface
public interface MetricEvaluator {

    /**
     * 对单个用例进行指标评估。
     * <p>
     * ExecutionTrace 中的所有 Span 和 Metrics 字段已在 Orchestrator
     * 完成时通过 {@code TraceHandle.markComplete()} 固化。本方法仅做读取和对比，
     * 不修改 Trace 内容。
     *
     * @param expected 预期结果（Ground Truth）
     * @param actual   实际执行 Trace（来自 Workflow Engine）
     * @return Mono&lt;TestCaseResult&gt; 异步返回评估结果
     */
    Mono<TestCaseResult> evaluate(TestCase expected, ExecutionTrace actual);
}
