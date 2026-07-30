package com.shopmind.evaluation.port;

import com.shopmind.evaluation.domain.FailureReason;
import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.workflow.domain.ExecutionTrace;
import reactor.core.publisher.Mono;

/**
 * 失败归因分析器接口 — 6_Evaluation_Engine.md §6.4 规范（v2.1 异步化）。
 * <p>
 * 对已判定为"失败"的用例进行根因分析（Root Cause Analysis）。
 * 接收 {@link TestCaseResult}（由 {@link MetricEvaluator} 预计算的各维度通过/失败标志）
 * 和完整的 {@link ExecutionTrace}，判断失败具体出自哪个环节。
 * <p>
 * <b>归因逻辑（按优先级）：</b>
 * <ol>
 *   <li>若 ExecutionTrace.status == DEGRADED / FAILED → 检查是否是 TIMEOUT 或 SAFETY_BLOCKED</li>
 *   <li>若 !intentMatch → {@code WRONG_INTENT}</li>
 *   <li>若 !toolMatch → 检查是选择了错误工具（{@code WRONG_TOOL}）还是参数错误（{@code WRONG_PARAMETER}）</li>
 *   <li>若 !knowledgeRecalled → {@code KNOWLEDGE_MISS}</li>
 *   <li>若以上均通过但仍失败 → 委托 {@link HallucinationEvaluator} 判断是否出现幻觉</li>
 * </ol>
 * <p>
 * <b>异步约束（v2.1 核心修复）：</b>返回 {@link Mono}{@code <FailureReason>}。
 * 归因链路中的最后一步（幻觉检测）可能需要调用 LLM API，
 * 因此整个接口必须为异步以消除阻塞风险。
 * <p>
 * <b>线程安全：</b>实现类应为无状态 {@code @Component} 单例。
 * 分析过程中的所有状态通过方法参数传入和 Mono 链返回。
 */
@FunctionalInterface
public interface FailureAnalyzer {

    /**
     * 对失败用例执行根因分析。
     *
     * @param expected 预期结果（Ground Truth）
     * @param metrics  指标评估结果（MetricEvaluator 已计算的各维度标志）
     * @param trace    完整执行 Trace（包含所有 Span，用于深入诊断）
     * @return Mono&lt;FailureReason&gt; 异步返回失败根因分类
     */
    Mono<FailureReason> analyze(TestCase expected, TestCaseResult metrics, ExecutionTrace trace);
}
