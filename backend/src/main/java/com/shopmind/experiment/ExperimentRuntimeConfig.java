package com.shopmind.experiment;

import com.shopmind.evaluation.rtmp.RtmpTestCase;

/**
 * 实验运行时配置 — Phase 2 显式 Runtime Configuration / 修订于 Phase 5-C1。
 * <p>
 * 通过 Reactor Context 在 BenchmarkRunner → Agent Orchestrator 之间透传，使 Agent 执行路径
 * 能明确知道当前 {@link ExperimentCondition}、以及（P4-1 起）Router 的合法运行时输入、
 * （Phase 5-C1 起）Router 与 Verifier 共享的 Runtime Session Context，而<b>无需</b>修改
 * OrchestrationRequest / user query / prompt / model。
 * <p>
 * <b>语义分离（Phase 5-C1）：</b>
 * <pre>
 * ExperimentRuntimeConfig
 * ├── groundTruth           —— {@code groundTruth()}：RTMP Ground Truth（仅供 Evaluator 层）
 * ├── routerContext         —— {@code routerContext()}：Router 的合法运行时输入（与 GT 严格隔离）
 * └── runtimeSessionContext —— {@code runtimeSessionContext()}：Router 与 Baseline B Verifier 共享的
 *                              运行时会话授权信息（来自 {@link RuntimeSessionContextProvider}，非 GT）
 * </pre>
 * Router 禁止通过 downcast / helper / getter 间接取得完整 {@link RtmpTestCase}；
 * {@code routerContext()} 返回的 {@link RouterContext} 不携带任何 GT 字段。
 * <p>
 * <b>Phase 5-C1 关键变更：</b>Baseline B {@link ToolSafetyVerifier} 不再读取
 * {@code groundTruth}，改为读取 {@code runtimeSessionContext}，与 Method C Router 信息对称。
 *
 * @param condition             当前实验条件（BASELINE_A / BASELINE_B / METHOD_C）
 * @param groundTruth           RTMP Ground Truth（可为 null，表示非 RTMP 场景；仅供 Evaluator 层）
 * @param routerContext         Router 合法运行时输入（可为 null）
 * @param runtimeSessionContext Router 与 Verifier 共享的运行时授权信息（可为 null）
 */
public record ExperimentRuntimeConfig(
        ExperimentCondition condition,
        RtmpTestCase groundTruth,
        RouterContext routerContext,
        RuntimeSessionContext runtimeSessionContext
) {

    /** Reactor Context 键。 */
    public static final String CONTEXT_KEY = "com.shopmind.experiment.ExperimentRuntimeConfig";

    /** 构造指定条件的运行时配置（无 RouterContext / RuntimeSessionContext）。 */
    public static ExperimentRuntimeConfig of(ExperimentCondition condition, RtmpTestCase groundTruth) {
        return new ExperimentRuntimeConfig(condition, groundTruth, null, null);
    }

    /** 构造指定条件 + RouterContext 的运行时配置。 */
    public static ExperimentRuntimeConfig of(ExperimentCondition condition, RtmpTestCase groundTruth,
                                             RouterContext routerContext) {
        return new ExperimentRuntimeConfig(condition, groundTruth, routerContext, null);
    }

    /** 返回携带指定 RouterContext 的副本。 */
    public ExperimentRuntimeConfig withRouterContext(RouterContext routerContext) {
        return new ExperimentRuntimeConfig(condition, groundTruth, routerContext, runtimeSessionContext);
    }

    /** 返回携带指定 RuntimeSessionContext 的副本。 */
    public ExperimentRuntimeConfig withRuntimeSessionContext(RuntimeSessionContext runtimeSessionContext) {
        return new ExperimentRuntimeConfig(condition, groundTruth, routerContext, runtimeSessionContext);
    }

    /** 默认配置：BASELINE_A + 无 Ground Truth + 无 RouterContext（legacy 场景保持原行为）。 */
    public static ExperimentRuntimeConfig defaults() {
        return new ExperimentRuntimeConfig(ExperimentCondition.BASELINE_A, null, null, null);
    }
}
