package com.shopmind.experiment;

/**
 * 实验条件 — Phase 2 明确的三条件 Runtime Configuration 抽象。
 * <p>
 * 三条件事实定义：
 * <ul>
 *   <li>{@link #BASELINE_A} — All Tools + No Safety Verifier（4 工具全可见，Tool Call 直接执行）</li>
 *   <li>{@link #BASELINE_B} — All Tools + Post-hoc Safety Verifier（LLM 产生 Tool Call 后、
 *       工具执行前由 Verifier ALLOW/BLOCK）</li>
 *   <li>{@link #METHOD_C} — RTMP 工具菜单裁剪（{@link RtmpVisibility}）+ No Safety Verifier</li>
 * </ul>
 * <p>
 * <b>关键约束：</b>condition 是<b>显式</b> Runtime Configuration，禁止在业务方法中散落字符串比较，
 * 也禁止通过 workflowVersion / datasetVersion 猜测 condition。本枚举集中定义
 * {@code condition → (visibilityStrategy, safetyVerifier)} 的唯一映射。
 */
public enum ExperimentCondition {

    BASELINE_A(new AllToolsVisibility(), new NoOpSafetyVerifier()),
    BASELINE_B(new AllToolsVisibility(), new PostHocSafetyVerifier()),
    /** Method C：RTMP 工具菜单裁剪（RtmpVisibility）+ NoOp Verifier。 */
    METHOD_C(new RtmpVisibility(), new NoOpSafetyVerifier());

    private final ToolVisibilityStrategy visibilityStrategy;
    private final ToolSafetyVerifier safetyVerifier;

    ExperimentCondition(ToolVisibilityStrategy visibilityStrategy, ToolSafetyVerifier safetyVerifier) {
        this.visibilityStrategy = visibilityStrategy;
        this.safetyVerifier = safetyVerifier;
    }

    /** 本条件使用的工具可见性策略（A/B → All Tools，C → RtmpVisibility）。 */
    public ToolVisibilityStrategy visibilityStrategy() {
        return visibilityStrategy;
    }

    /** 本条件使用的工具安全 Verifier（A → NoOp，B → PostHoc，C → NoOp）。 */
    public ToolSafetyVerifier safetyVerifier() {
        return safetyVerifier;
    }
}
