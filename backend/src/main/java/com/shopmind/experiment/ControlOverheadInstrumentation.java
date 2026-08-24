package com.shopmind.experiment;

import java.util.Set;

/**
 * Control overhead 检测门控 — Phase 5-B3。
 * <p>
 * 冻结「实验条件 → 应观测的 control 类型」映射（单一事实源）：
 * <ul>
 *   <li>{@link ExperimentCondition#BASELINE_A} — 无任何 control overhead</li>
 *   <li>{@link ExperimentCondition#BASELINE_B} — 仅 {@link ControlType#SAFETY_VERIFIER}</li>
 *   <li>{@link ExperimentCondition#METHOD_C} — 仅 {@link ControlType#RTMP_ROUTER}</li>
 * </ul>
 * <p>
 * 这是<b>旁路观测</b>的门控逻辑，不改变任何执行语义：Baseline A 的 {@code NoOpSafetyVerifier}
 * 与 Baseline A/B 的 {@code AllToolsVisibility} 虽然会被调用，但<b>不计</b>为 control invocation。
 */
public final class ControlOverheadInstrumentation {

    private ControlOverheadInstrumentation() {
    }

    /**
     * 返回指定实验条件应记录的 control 类型集合。
     *
     * @param condition 当前实验条件（null 视为无 control）
     * @return 应观测的 control 类型（BASELINE_A 为 empty）
     */
    public static Set<ControlType> controlTypesFor(ExperimentCondition condition) {
        if (condition == null) {
            return Set.of();
        }
        return switch (condition) {
            case BASELINE_A -> Set.of();
            case BASELINE_B -> Set.of(ControlType.SAFETY_VERIFIER);
            case METHOD_C -> Set.of(ControlType.RTMP_ROUTER);
        };
    }

    /** 该条件是否应记录 Safety Verifier overhead（仅 BASELINE_B）。 */
    public static boolean recordsVerifierOverhead(ExperimentCondition condition) {
        return controlTypesFor(condition).contains(ControlType.SAFETY_VERIFIER);
    }

    /** 该条件是否应记录 RTMP Router overhead（仅 METHOD_C）。 */
    public static boolean recordsRouterOverhead(ExperimentCondition condition) {
        return controlTypesFor(condition).contains(ControlType.RTMP_ROUTER);
    }
}
