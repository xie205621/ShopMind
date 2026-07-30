package com.shopmind.evaluation.pipeline;

import com.shopmind.evaluation.domain.AgentInput;
import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.evaluation.domain.ExperimentReport;
import com.shopmind.evaluation.domain.FailureReason;
import com.shopmind.evaluation.domain.TestCase;
import com.shopmind.evaluation.domain.TestCaseResult;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.port.EvaluableAgent;
import com.shopmind.evaluation.port.FailureAnalyzer;
import com.shopmind.evaluation.port.MetricEvaluator;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.workflow.domain.ObservabilityMetrics;
import com.shopmind.workflow.domain.TraceSpan;
import com.shopmind.workflow.port.TraceRecorder;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * BenchmarkRunner 实现 — 6_Evaluation_Engine.md §8.1 + Phase F: Framework-Agnostic。
 * <p>
 * <b>核心职责：</b>将数据集中的每个 TestCase 以受控并发度驱动 {@link EvaluableAgent}，
 * 收集 ExecutionTrace，委托评估与归因，最终线程安全地聚合为 ExperimentReport。
 * <p> * <b>Framework-Agnostic（Phase F）：</b>不依赖具体的 Agent 实现。
 * 只要实现 {@link EvaluableAgent} 接口，任何框架（ShopMind / LangChain / OpenAI SDK）
 * 都可以被统一的 Evaluation Engine 评测。
 * <p>
 * <b>双重限流策略（v2.1 核心修复）：</b>
 * <ol>
 *   <li>{@code Flux.flatMap(..., maxConcurrency)} — 控制同时进行的 Orchestrator 调用数</li>
 *   <li>Resilience4j {@link RateLimiter} — 基于 Token Bucket 的 RPM 速率控制，
 *       防止瞬发流量触发 LLM 厂商 HTTP 429 限流</li>
 * </ol>
 * <p>
 * <b>线程安全：</b>
 * <ul>
 *   <li>每个 TestCase 的评估链路是独立的 Mono，通过 {@code flatMap} 并发执行</li>
 *   <li>最终聚合使用 {@link Flux#reduce(Object, java.util.function.BiFunction)}，
 *      累加器串行执行，天然线程安全</li>
 *   <li>不持有任何共享可变状态</li>
 * </ul>
 * <p>
 * <b>容错设计：</b>单个 TestCase 的失败（超时、LLM 异常）不会终止整个 Benchmark，
 * 而是被捕获为带 {@code FailureReason} 的 {@code TestCaseResult}，最终出现在 Report 中。
 *
 * @see BenchmarkRunner
 * @see BenchmarkConfig
 * @see ExperimentReport
 */
@Component
@ConditionalOnBean({MetricEvaluator.class, FailureAnalyzer.class, TraceRecorder.class})
public class BenchmarkRunnerImpl implements BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunnerImpl.class);

    /** 评测专用 RateLimiter 名称（对应 resilience4j.ratelimiter.instances.llmRateLimiter 配置） */
    private static final String LLM_RATE_LIMITER = "llmRateLimiter";

    /** 答案截断长度（存入 TestCaseResult.answerSnippet） */
    private static final int ANSWER_SNIPPET_LENGTH = 500;

    private final EvaluableAgent agent;
    private final MetricEvaluator evaluator;
    private final FailureAnalyzer failureAnalyzer;
    private final TraceRecorder traceRecorder;
    private final RateLimiterRegistry rateLimiterRegistry;

    public BenchmarkRunnerImpl(
            EvaluableAgent agent,
            MetricEvaluator evaluator,
            FailureAnalyzer failureAnalyzer,
            TraceRecorder traceRecorder,
            RateLimiterRegistry rateLimiterRegistry) {
        this.agent = agent;
        this.evaluator = evaluator;
        this.failureAnalyzer = failureAnalyzer;
        this.traceRecorder = traceRecorder;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    // ============================================================
    //  run — 核心入口
    // ============================================================

    /**
     * 执行全量 Benchmark 评测。
     * <p>
     * 流程概览：
     * <pre>
     * Dataset
     *   → Flux.fromIterable(testCases)
     *   → flatMap (RateLimiter + maxConcurrency)
     *     → executeAndCollectTrace   (驱动 Orchestrator + 提取 Trace)
     *     → evaluator.evaluate       (指标计算)
     *     → failureAnalyzer.analyze  (失败归因，仅在 !isAllPassed 时)
     *   → reduce                     (线程安全聚合 → ExperimentReport)
     *   → finalize                   (计算百分比、P95、成本)
     * </pre>
     */
    @Override
    public Mono<ExperimentReport> run(EvaluationDataset dataset,
                                       BenchmarkConfig config,
                                       String isolationPrefix) {
        if (dataset.isEmpty()) {
            log.warn("[Evaluation] Dataset '{}' is empty, returning empty report.", dataset.datasetId());
            return Mono.just(new ExperimentReport().finalize(config));
        }

        // 获取 RateLimiter 实例（token bucket 算法，按 RPM 限流）
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(LLM_RATE_LIMITER);
        int maxConcurrency = config.maxConcurrency();

        log.info("[Evaluation] Starting benchmark: experimentId={}, cases={}, scenario={}, "
                        + "maxConcurrency={}, rpmLimit={}",
                config.experimentId(), dataset.size(), dataset.scenario(),
                maxConcurrency, config.rpmLimit());

        final long startTime = System.currentTimeMillis();

        return Flux.fromIterable(dataset.testCases())
                // ---- Step 0: 并发执行每个 TestCase ----
                .flatMap(testCase ->
                        Mono.just(testCase)
                                // RateLimiter: 令牌桶限流，防止 HTTP 429
                                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                                // Step 1: 驱动 Orchestrator → 收集完整回答 + 构建 ExecutionTrace
                                .flatMap(tc -> executeAndCollectTrace(tc, isolationPrefix, config))
                                // Step 2: 指标评估
                                .flatMap(ctx -> evaluator.evaluate(ctx.testCase, ctx.trace)
                                        .map(result -> ctx.withMetrics(result)))
                                // Step 3: 失败归因（总是调用——即使表面指标通过，也可能有深层问题如 WRONG_PARAMETER）
                                .flatMap(ctx -> failureAnalyzer.analyze(ctx.testCase, ctx.metrics, ctx.trace)
                                        .map(reason -> ctx.withFailureReason(reason))
                                        .onErrorResume(e -> {
                                            log.warn("[Evaluation] Failure analysis failed for {}, "
                                                    + "falling back to TIMEOUT", ctx.testCase.testCaseId(), e);
                                            return Mono.just(ctx.withFailureReason(FailureReason.TIMEOUT));
                                        })
                                        // 如果 analyzer 诊断出问题，覆盖；否则保持原样
                                        .defaultIfEmpty(ctx))
                                // Step 4: 产出最终的 TestCaseResult
                                .map(BenchmarkRunnerImpl::toFinalResult),
                        maxConcurrency  // ← 并发度上限
                )
                // ---- Step 5: 线程安全聚合 ----
                .reduce(new ExperimentReport(), (report, result) -> report.accumulate(result))
                // ---- Step 6: 最终计算 ----
                .map(report -> report.finalize(config))
                .doOnSuccess(report -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[Evaluation] Benchmark complete: experimentId={}, totalCases={}, "
                                    + "passed={}, elapsed={}ms, cost=${}",
                            config.experimentId(), report.getTotalCases(),
                            report.getPassedCases(), elapsed, report.getCost().estimatedCostUsd());
                })
                .doOnError(e -> log.error("[Evaluation] Benchmark failed for experimentId={}", config.experimentId(), e));
    }

    // ============================================================
    //  executeAndCollectTrace — 驱动 Orchestrator + 构建 Trace
    // ============================================================

    /**
     * 为单个 TestCase 执行一次完整的 Orchestrator 调用，
     * 并收集所有可观测数据构建 ExecutionTrace。
     * <p>
     * <b>测量内容：</b>
     * <ul>
     *   <li>TTFT — 第一个 Token 到达的时间</li>
     *   <li>端到端延迟 — 从发出请求到 Flux complete</li>
     *   <li>输出 Token 数 — 从 Flux 中统计 Token 数量</li>
     *   <li>完整回答文本 — 拼接所有 Token，用于幻觉检测</li>
     * </ul>
     * <p>
     * <b>容错：</b>Orchestrator 调用失败时（如 CircuitBreaker 熔断、LLM 超时），
     * 不会抛出异常终止整个 Flux，而是返回一个包含 {@code TIMEOUT} 状态的 EvalContext。
     */
    private Mono<EvalContext> executeAndCollectTrace(TestCase testCase,
                                                      String isolationPrefix,
                                                      BenchmarkConfig config) {
        // 生成隔离的 memoryId
        String memoryId = isolationPrefix + testCase.testCaseId();
        AgentInput input = new AgentInput(memoryId, testCase.query());

        // 创建 TraceHandle（请求级独立实例，无泄漏风险）
        TraceRecorder.TraceHandle traceHandle = traceRecorder.createTrace(memoryId, config.workflowVersion());
        ExecutionTrace trace = new ExecutionTrace(traceHandle.getTraceId(), memoryId, config.workflowVersion());

        // TTFT 计时器
        AtomicLong firstTokenAt = new AtomicLong(-1);
        long requestStart = System.currentTimeMillis();

        return agent.chat(input)
                // 记录 TTFT
                .doOnNext(token -> firstTokenAt.compareAndSet(-1, System.currentTimeMillis()))
                // 收集所有 Token 为完整字符串
                .collect(Collectors.joining())
                // 成功路径：构建 Trace
                .map(fullAnswer -> {
                    long elapsed = System.currentTimeMillis() - requestStart;
                    long ttft = firstTokenAt.get() > 0
                            ? firstTokenAt.get() - requestStart
                            : elapsed;

                    // 填充 ObservabilityMetrics
                    ObservabilityMetrics metrics = trace.getMetrics();
                    metrics.setTtftMs(ttft);
                    metrics.setCompletionTokens(estimateTokens(fullAnswer));

                    // 将完整回答存入 TraceSpan，供下游 MetricEvaluator / FailureAnalyzer 消费
                    trace.addSpan(new TraceSpan("ANSWER_OUTPUT", elapsed,
                            Map.of("query", testCase.query()),
                            Map.of("answer", fullAnswer),
                            1.0));

                    // 标记 Trace 完成
                    trace.markComplete(ExecutionStatus.SUCCESS);

                    log.debug("[Evaluation] Case {} completed: ttft={}ms, total={}ms, tokens={}",
                            testCase.testCaseId(), ttft, elapsed, metrics.getCompletionTokens());

                    return new EvalContext(testCase, trace, fullAnswer);
                })
                // 失败路径：Orchestrator 异常时包装为带 TIMEOUT 的 Context
                .onErrorResume(error -> {
                    log.warn("[Evaluation] Orchestrator failed for case {}: {}",
                            testCase.testCaseId(), error.getMessage());

                    long elapsed = System.currentTimeMillis() - requestStart;
                    trace.markComplete(ExecutionStatus.FAILED);
                    trace.getMetrics().setTtftMs(elapsed);

                    return Mono.just(new EvalContext(testCase, trace, "[ORCHESTRATOR_ERROR] " + error.getMessage()));
                })
                // 确保 Trace 落盘（全路径覆盖：complete / error / cancel）
                .doFinally(signalType -> traceRecorder.save(traceHandle).subscribe());
    }

    // ============================================================
    //  toFinalResult — 将评估+归因的结果合并为 TestCaseResult
    // ============================================================

    /**
     * 从 EvalContext 中提取最终的 TestCaseResult。
     * 如果 FailureAnalyzer 给出了 failureReason，将其设置到结果中。
     */
    private static TestCaseResult toFinalResult(EvalContext ctx) {
        TestCaseResult metrics = ctx.metrics;
        FailureReason resolvedReason = ctx.failureReason != null
                ? ctx.failureReason
                : metrics.failureReason();  // 使用 MetricEvaluator 的判定

        String snippet = truncate(ctx.fullAnswer, ANSWER_SNIPPET_LENGTH);

        return new TestCaseResult(
                metrics.testCaseId(),
                ctx.testCase.query(),
                metrics.intentMatch(),
                metrics.toolMatch(),
                metrics.knowledgeRecalled(),
                metrics.recallAtK(),
                metrics.ttftMs(),
                metrics.totalLatencyMs(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                resolvedReason,
                snippet,
                metrics.rawMetrics()
        );
    }

    // ============================================================
    //  Utility
    // ============================================================

    /** 估算 Token 数量：基于英文 1 token ≈ 4 chars，中文 1 token ≈ 1-2 chars。精度不高但够用。 */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 粗略估计：平均 2.5 字符 = 1 token
        return Math.max(1, (int) (text.length() / 2.5));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ============================================================
    //  EvalContext — 内部数据传输对象
    // ============================================================

    /**
     * 评测流水线内部的数据传输对象（DTO）。
     * <p>
     * 在 BenchmarkRunnerImpl 的 flatMap 链中传递 TestCase、
     * ExecutionTrace、MetricEvaluator 的中间结果和 FailureAnalyzer 的归因结果。
     * 对外部组件完全不可见。
     */
    private static final class EvalContext {
        final TestCase testCase;
        final ExecutionTrace trace;
        final String fullAnswer;

        // 逐步填充的字段
        TestCaseResult metrics;
        FailureReason failureReason;

        EvalContext(TestCase testCase, ExecutionTrace trace, String fullAnswer) {
            this.testCase = testCase;
            this.trace = trace;
            this.fullAnswer = fullAnswer;
        }

        EvalContext withMetrics(TestCaseResult metrics) {
            this.metrics = metrics;
            return this;
        }

        EvalContext withFailureReason(FailureReason reason) {
            this.failureReason = reason;
            return this;
        }
    }
}
