package com.shopmind.evaluation.port;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.evaluation.domain.ExperimentReport;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.workflow.domain.ExecutionTrace;
import reactor.core.publisher.Mono;

/**
 * 评测运行器接口 — 6_Evaluation_Engine.md §6.1 规范（v2.1 异步化）。
 * <p>
 * 驱动全量 Benchmark 评测的核心入口。负责：
 * <ol>
 *   <li>遍历 {@link EvaluationDataset} 中的所有 TestCase</li>
 *   <li>以并发约束（maxConcurrency + RateLimiter）驱动 Agent Orchestrator</li>
 *   <li>从 Reactor Context 中提取每个用例的 ExecutionTrace</li>
 *   <li>委托 MetricEvaluator 和 FailureAnalyzer 进行指标计算与归因</li>
 *   <li>使用 Flux.reduce() 线程安全地聚合为 ExperimentReport</li>
 * </ol>
 * <p>
 * <b>并发安全：</b>即使部分用例失败（超时、429、LLM 返回垃圾），
 * Runner 仍应产出包含失败详情的完整 Report，而非整体报错。
 * <p>
 * <b>实现约束（v2.1）：</b>
 * <ul>
 *   <li>必须组合 {@code Flux.flatMap(..., maxConcurrency)} 和 Resilience4j {@code RateLimiter}</li>
 *   <li>评测过程中生成的 memoryId 必须使用 {@code isolationPrefix} 前缀，防止污染线上数据</li>
 *   <li>整个响应式链中禁止调用 {@code block()} 或任何同步 I/O</li>
 * </ul>
 */
public interface BenchmarkRunner {

    /**
     * 执行全量 Benchmark 评测。
     *
     * @param dataset          评测数据集（含场景分类和所有 TestCase）
     * @param config           实验超参数配置（保证可复现性）
     * @param isolationPrefix  memoryId 前缀，如 "eval_v1.0_"，确保评测会话与线上数据隔离
     * @return Mono&lt;ExperimentReport&gt; 聚合后的实验报告。即使部分用例失败，
     *         Report 中仍会包含完整的失败分布和采样详情
     */
    Mono<ExperimentReport> run(EvaluationDataset dataset, BenchmarkConfig config, String isolationPrefix);

    /**
     * 执行单个 RTMP 用例的 instrumentation run（Phase 1-B）。
     * <p>
     * 基于 {@code experimentId / condition / caseId / repetition} 生成 canonical
     * {@link com.shopmind.workflow.domain.RunIdentity}，保证
     * {@code memoryId == runId}，并返回本次 run 的 canonical {@link ExecutionTrace}。
     * <p>
     * 该方法<b>只做 instrumentation wiring</b>：真实驱动 Orchestrator、生成并追加
     * {@code ToolCallEvent}，不包含任何 Verifier / pruning / 策略切换。
     *
     * @param testCase   RTMP 用例（提供 caseId 与 query）
     * @param config     实验超参数配置（提供 experimentId）
     * @param condition  实验条件（BASELINE_A / BASELINE_B / METHOD_C）
     * @param repetition repetition 序号（1..3）
     * @return 完成后的 canonical ExecutionTrace（含 runtime ToolCallEvent）
     */
    Mono<ExecutionTrace> runRtmpCase(RtmpTestCase testCase, BenchmarkConfig config,
                                     ExperimentCondition condition, int repetition);

    /**
     * 执行单个 RTMP 用例的 instrumentation run，并返回分类后的 Run Outcome（Phase 1-C）。
     * <p>
     * 与 {@link #runRtmpCase} 的区别：额外基于 canonical Trace 与异常进行
     * {@link com.shopmind.evaluation.rtmp.RunStatus} 分类，将 Raw 执行事实与
     * 实验级运行状态打包为 {@link RtmpRunOutcome}。
     * <p>
     * 本方法<b>仍只做 instrumentation wiring + run outcome classification</b>，
     * 不包含任何 Verifier / pruning / 策略切换 / safety metric / statistical testing。
     *
     * @param testCase   RTMP 用例（提供 caseId 与 query）
     * @param config     实验超参数配置（提供 experimentId）
     * @param condition  实验条件（BASELINE_A / BASELINE_B / METHOD_C）
     * @param repetition repetition 序号（1..3）
     * @return 分类后的 {@link RtmpRunOutcome}（canonical Trace + RunStatus）
     */
    Mono<RtmpRunOutcome> runRtmpCaseOutcome(RtmpTestCase testCase, BenchmarkConfig config,
                                            ExperimentCondition condition, int repetition);
}
