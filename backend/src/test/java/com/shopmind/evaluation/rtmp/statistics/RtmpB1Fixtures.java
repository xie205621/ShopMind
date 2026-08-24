package com.shopmind.evaluation.rtmp.statistics;

import com.shopmind.evaluation.rtmp.RtmpCaseEvaluation;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.experiment.ControlType;
import com.shopmind.workflow.domain.ControlOverheadEvent;

import java.util.List;

/**
 * B1 统计测试共享 fixtures — 不写入正式 RTMP dataset，仅用于 B1 单元测试。
 * <p>
 * 与 B4 的 {@code RtmpB4Fixtures} 不同，这里直接构造 {@link RtmpCaseEvaluation}
 * （绕过 Evaluator），以便<b>精确控制</b> L2 / coreTaskSuccess / overRefusal 等 binary
 * outcome，以及 control latency 的 scalar observation。
 */
final class RtmpB1Fixtures {

    static final String EXP = "RTMP-EXP01";

    private RtmpB1Fixtures() {
    }

    static String runId(String condition, String caseId, int repetition) {
        return EXP + "_" + condition + "_" + caseId + "_" + repetition;
    }

    /** 构造一个可精确控制的 case-level evaluation。 */
    static RtmpCaseEvaluation evaluation(String condition, String caseId, int repetition,
                                         boolean l2, boolean coreEligible, boolean coreSuccess,
                                         boolean overRefusal) {
        return new RtmpCaseEvaluation(
                runId(condition, caseId, repetition), condition, caseId, repetition,
                false,            // safetyIntervention
                false,            // l1
                l2,               // l2
                false,            // l3
                coreEligible, coreSuccess, overRefusal,
                null, null, null, // attemptedTool / executedTool / verifierBlocked
                "SUCCESS", "fixture");
    }

    /** 直接构造 Raw record（含指定 evaluation、status、control events）。 */
    static RtmpRawRecord rawRecord(String condition, String caseId, int repetition,
                                   String expectedOutcome, String expectedToolAction,
                                   RtmpCaseEvaluation evaluation, RunStatus status,
                                   List<ControlOverheadEvent> controls) {
        return new RtmpRawRecord(
                runId(condition, caseId, repetition), EXP, condition, caseId, repetition, 0,
                "query-" + caseId, "SAFE_LOW_RISK", expectedOutcome, expectedToolAction,
                List.of(), List.of(), controls,
                evaluation, status, null, null);
    }

    static ControlOverheadEvent controlEvent(String condition, String caseId, int repetition,
                                             ControlType type, int iteration, long latency) {
        return new ControlOverheadEvent(
                runId(condition, caseId, repetition), condition, caseId, repetition,
                type, iteration, latency, null, null, null, null);
    }
}
