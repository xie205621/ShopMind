package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.experiment.ControlType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RTMP Raw 数据完整性校验器 — Phase 5-B4。
 * <p>
 * 验证一组 {@link RtmpRawRecord} 的 identity 唯一性、condition/event 一致性、
 * run_id 格式一致性。返回结构化校验结果（错误列表 + 是否通过），不静默删除任何记录。
 */
public final class RtmpExperimentValidator {

    /** 冻结的合法 condition 集合（§5.2）。 */
    public static final Set<String> ALLOWED_CONDITIONS =
            Set.of("BASELINE_A", "BASELINE_B", "METHOD_C");

    /**
     * 校验结果。
     *
     * @param valid  是否通过校验（errors 为空）
     * @param errors 错误信息（按发现顺序）
     */
    public record Result(boolean valid, List<String> errors) {
    }

    private RtmpExperimentValidator() {
    }

    public static Result validate(List<RtmpRawRecord> records) {
        List<String> errors = new ArrayList<>();
        Set<String> seenRunIds = new HashSet<>();
        if (records == null) {
            return new Result(true, List.of());
        }
        for (RtmpRawRecord r : records) {
            if (r == null) {
                errors.add("null raw record present");
                continue;
            }
            validateIdentity(r, seenRunIds, errors);
            validateConditionEventConsistency(r, errors);
        }
        return new Result(errors.isEmpty(), List.copyOf(errors));
    }

    private static void validateIdentity(RtmpRawRecord r, Set<String> seenRunIds, List<String> errors) {
        if (r.runId() == null || r.runId().isBlank()) {
            errors.add(label(r) + ": missing runId");
        } else if (!seenRunIds.add(r.runId())) {
            errors.add(label(r) + ": duplicate runId '" + r.runId() + "'");
        }
        if (r.condition() == null || !ALLOWED_CONDITIONS.contains(r.condition())) {
            errors.add(label(r) + ": invalid condition '" + r.condition() + "'");
        }
        if (r.caseId() == null || r.caseId().isBlank()) {
            errors.add(label(r) + ": missing caseId");
        }
        if (r.repetition() < 1) {
            errors.add(label(r) + ": invalid repetition '" + r.repetition() + "'");
        }
        // run_id 格式一致性（experimentId 存在时）：runId == experimentId_condition_caseId_repetition
        if (r.experimentId() != null && r.condition() != null && r.caseId() != null && r.repetition() >= 1) {
            String expected = r.experimentId() + "_" + r.condition() + "_" + r.caseId() + "_" + r.repetition();
            if (!expected.equals(r.runId())) {
                errors.add(label(r) + ": runId mismatch, expected '" + expected + "' got '" + r.runId() + "'");
            }
        }
    }

    /**
     * condition → event 一致性（§24）：
     * <ul>
     *   <li>A → 无任何 control event，无 pruning event；</li>
     *   <li>B → 仅 SAFETY_VERIFIER control event，无 router event，无 pruning event；</li>
     *   <li>C → 仅 RTMP_ROUTER control event，无 verifier event。</li>
     * </ul>
     */
    private static void validateConditionEventConsistency(RtmpRawRecord r, List<String> errors) {
        String c = r.condition();
        if (c == null) {
            return;
        }
        boolean hasVerifier = r.controlOverheadEvents().stream()
                .anyMatch(e -> e.controlType() == ControlType.SAFETY_VERIFIER);
        boolean hasRouter = r.controlOverheadEvents().stream()
                .anyMatch(e -> e.controlType() == ControlType.RTMP_ROUTER);
        boolean hasPruning = !r.pruningEvents().isEmpty();

        switch (c) {
            case "BASELINE_A" -> {
                if (hasVerifier || hasRouter) {
                    errors.add(label(r) + ": Baseline A must have no control events");
                }
                if (hasPruning) {
                    errors.add(label(r) + ": Baseline A must have no pruning events");
                }
            }
            case "BASELINE_B" -> {
                if (hasRouter) {
                    errors.add(label(r) + ": Baseline B must have no router events");
                }
                if (hasPruning) {
                    errors.add(label(r) + ": Baseline B must have no pruning events");
                }
            }
            case "METHOD_C" -> {
                if (hasVerifier) {
                    errors.add(label(r) + ": Method C must have no verifier events");
                }
            }
            default -> {
                // invalid condition 已由 identity 校验覆盖
            }
        }
    }

    private static String label(RtmpRawRecord r) {
        return "run[" + r.runId() + "]";
    }
}
