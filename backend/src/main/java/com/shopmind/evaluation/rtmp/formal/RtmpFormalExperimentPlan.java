package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.workflow.domain.RunIdentity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical formal execution plan — Phase 5-E1（B1）。
 * <p>
 * 生成 42 cases × 3 conditions × repetition{1,2,3} = 378 个 canonical experimental units，
 * 并在任何 Real LLM 调用之前做完整预执行校验。
 * <p>
 * 每个 unit 携带 canonical run identity（{@code runId == memoryId}）与 execution-order
 * provenance（{@code conditionOrderIndex}）。
 */
public final class RtmpFormalExperimentPlan {

    /** 冻结的正式实验单位总数：42 × 3 × 3。 */
    public static final int EXPECTED_UNITS = 378;

    public static final int EXPECTED_CASES = 42;
    public static final int EXPECTED_CONDITIONS = 3;
    public static final int EXPECTED_REPETITIONS = 3;

    /** 一个 canonical experimental unit。 */
    public record Unit(
            String caseId,
            String condition,
            int repetition,
            String runId,
            String memoryId,
            int conditionOrderIndex
    ) {
    }

    /** 完整 canonical plan。 */
    public record Plan(String experimentId, List<Unit> units) {
    }

    /** 预执行校验结果。 */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            int plannedUnits,
            int uniqueRunIds,
            int uniqueMemoryIds,
            int caseCoverage,
            int conditionCoverage,
            int repetitionCoverage
    ) {
    }

    private RtmpFormalExperimentPlan() {
    }

    /**
     * 生成 deterministic canonical plan（不执行任何 Agent / LLM）。
     */
    public static Plan build(String experimentId, RtmpEvaluationDataset dataset) {
        List<Unit> units = new ArrayList<>();
        for (RtmpTestCase testCase : dataset.cases()) {
            for (int repetition : RtmpConditionOrder.REPETITIONS) {
                List<ExperimentCondition> order = RtmpConditionOrder.orderFor(repetition);
                for (int index = 0; index < order.size(); index++) {
                    ExperimentCondition condition = order.get(index);
                    RunIdentity identity = new RunIdentity(
                            experimentId, condition.name(), testCase.id(), repetition);
                    units.add(new Unit(testCase.id(), condition.name(), repetition,
                            identity.runId(), identity.memoryId(), index));
                }
            }
        }
        return new Plan(experimentId, List.copyOf(units));
    }

    /**
     * 完整预执行校验（§五）：378 units、runId/memoryId 唯一、case/condition/repetition
     * 全覆盖、每个 case × repetition 恰好 3 个 condition、order 符合 frozen rotation、
     * 不允许重复 runId / 重复 canonical unit。
     */
    public static ValidationResult validate(Plan plan) {
        List<String> errors = new ArrayList<>();
        Set<String> runIds = new HashSet<>();
        Set<String> memoryIds = new HashSet<>();
        Set<String> canonicalUnits = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        Set<String> conditions = new HashSet<>();
        Set<Integer> repetitions = new HashSet<>();

        for (Unit u : plan.units()) {
            if (!runIds.add(u.runId())) {
                errors.add("duplicate runId: " + u.runId());
            }
            if (!memoryIds.add(u.memoryId())) {
                errors.add("duplicate memoryId: " + u.memoryId());
            }
            if (!canonicalUnits.add(u.caseId() + "#" + u.repetition() + "#" + u.condition())) {
                errors.add("duplicate canonical unit: " + u.caseId() + "#" + u.repetition()
                        + "#" + u.condition());
            }
            if (u.conditionOrderIndex() < 0 || u.conditionOrderIndex() > 2) {
                errors.add("conditionOrderIndex out of range: " + u);
            } else {
                int expected = RtmpConditionOrder.conditionOrderIndex(
                        u.repetition(), ExperimentCondition.valueOf(u.condition()));
                if (u.conditionOrderIndex() != expected) {
                    errors.add("conditionOrderIndex mismatch: " + u.caseId() + "#"
                            + u.repetition() + "#" + u.condition() + " got "
                            + u.conditionOrderIndex() + " expected " + expected);
                }
            }
            caseIds.add(u.caseId());
            conditions.add(u.condition());
            repetitions.add(u.repetition());
        }

        // 每个 case × repetition 恰好 3 个 condition
        Set<String> caseRepetitions = new HashSet<>();
        for (Unit u : plan.units()) {
            caseRepetitions.add(u.caseId() + "#" + u.repetition());
        }
        for (String cr : caseRepetitions) {
            long count = plan.units().stream()
                    .filter(u -> (u.caseId() + "#" + u.repetition()).equals(cr))
                    .count();
            if (count != EXPECTED_CONDITIONS) {
                errors.add("case×repetition " + cr + " has " + count + " conditions (expected "
                        + EXPECTED_CONDITIONS + ")");
            }
        }

        if (plan.units().size() != EXPECTED_UNITS) {
            errors.add("planned units = " + plan.units().size() + " (expected " + EXPECTED_UNITS + ")");
        }

        return new ValidationResult(
                errors.isEmpty(),
                List.copyOf(errors),
                plan.units().size(),
                runIds.size(),
                memoryIds.size(),
                caseIds.size(),
                conditions.size(),
                repetitions.size());
    }
}
