package com.shopmind.evaluation.port;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.evaluation.domain.ExperimentReport;
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
}
