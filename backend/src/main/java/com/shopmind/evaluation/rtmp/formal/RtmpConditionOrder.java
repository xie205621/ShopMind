package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.experiment.ExperimentCondition;

import java.util.List;

/**
 * Deterministic balanced condition order — Phase 5-E1（B4）。
 * <p>
 * 最终执行层事实源（§三）：
 * <ul>
 *   <li>Repetition 1: {@code A → B → C}</li>
 *   <li>Repetition 2: {@code B → C → A}</li>
 *   <li>Repetition 3: {@code C → A → B}</li>
 * </ul>
 * 其中 {@code A = BASELINE_A}，{@code B = BASELINE_B}，{@code C = METHOD_C}。
 * <p>
 * <b>确定性约束（冻结）：</b>不引入随机化、不使用随机 seed、不根据实时结果动态调整顺序。
 * 同一 case × repetition 内的 condition order 严格遵循上述 rotation，不得并行打乱。
 */
public final class RtmpConditionOrder {

    /** 冻结的 repetition 集合。 */
    public static final List<Integer> REPETITIONS = List.of(1, 2, 3);

    public static final List<ExperimentCondition> REP1 = List.of(
            ExperimentCondition.BASELINE_A,
            ExperimentCondition.BASELINE_B,
            ExperimentCondition.METHOD_C);

    public static final List<ExperimentCondition> REP2 = List.of(
            ExperimentCondition.BASELINE_B,
            ExperimentCondition.METHOD_C,
            ExperimentCondition.BASELINE_A);

    public static final List<ExperimentCondition> REP3 = List.of(
            ExperimentCondition.METHOD_C,
            ExperimentCondition.BASELINE_A,
            ExperimentCondition.BASELINE_B);

    private RtmpConditionOrder() {
    }

    /**
     * 返回给定 repetition 的 frozen condition 执行顺序。
     *
     * @param repetition repetition 序号（1/2/3）
     * @return 该 repetition 下 A/B/C 的有序执行序列
     */
    public static List<ExperimentCondition> orderFor(int repetition) {
        return switch (repetition) {
            case 1 -> REP1;
            case 2 -> REP2;
            case 3 -> REP3;
            default -> throw new IllegalArgumentException("Unsupported repetition: " + repetition);
        };
    }

    /**
     * 返回给定 repetition 下 condition 的执行顺序索引（0/1/2）。
     *
     * @param repetition repetition 序号（1/2/3）
     * @param condition  实验条件
     * @return 0 = 第一个执行，1 = 第二个，2 = 第三个
     */
    public static int conditionOrderIndex(int repetition, ExperimentCondition condition) {
        int index = orderFor(repetition).indexOf(condition);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Condition " + condition + " not in repetition " + repetition);
        }
        return index;
    }
}
