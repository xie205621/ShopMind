package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.evaluation.rtmp.ContextRisk;
import com.shopmind.evaluation.rtmp.ExpectedToolAction;
import com.shopmind.evaluation.rtmp.RtmpCaseEvaluation;
import com.shopmind.evaluation.rtmp.RtmpCaseEvaluator;
import com.shopmind.evaluation.rtmp.RtmpTaskCategory;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.ToolRiskProfile;
import com.shopmind.experiment.ControlType;
import com.shopmind.experiment.RelevanceScore;
import com.shopmind.experiment.RiskScore;
import com.shopmind.experiment.ToolDecisionCandidate;
import com.shopmind.experiment.ToolScoreResult;
import com.shopmind.workflow.domain.ControlOverheadEvent;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.PruningEvent;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;

import java.util.List;
import java.util.Map;

/**
 * B4 测试共享 fixtures — 不写入正式 RTMP dataset，仅用于 B4 单元测试。
 */
final class RtmpB4Fixtures {

    static final String EXP = "RTMP-EXP01";
    static final RtmpCaseEvaluator EVALUATOR = new RtmpCaseEvaluator();

    private RtmpB4Fixtures() {
    }

    static String runId(String condition, String caseId, int repetition) {
        return EXP + "_" + condition + "_" + caseId + "_" + repetition;
    }

    static RtmpTestCase testCase(String id, RtmpTaskCategory category, String outcome,
                                 String expectedTool, ExpectedToolAction action) {
        List<String> sequence = (action == ExpectedToolAction.CALL && expectedTool != null)
                ? List.of(expectedTool) : List.of();
        return new RtmpTestCase(
                id, "查询" + id, category, outcome, expectedTool, action,
                List.of("queryOrder", "refund", "queryPoints", "queryCoupons"),
                new ToolRiskProfile("NONE", "NONE", "N_A", "MEDIUM", "OWN_DATA"),
                new ContextRisk("HIGH", "AUTHORIZED", "OWN_RESOURCE", "NORMAL"),
                "USER", "NONE", false, null, "mock", sequence);
    }

    static ExecutionTrace trace(String condition, String caseId, int repetition,
                                List<ToolCallEvent> toolCalls,
                                List<ControlOverheadEvent> controls,
                                List<PruningEvent> prunings) {
        RunIdentity identity = new RunIdentity(EXP, condition, caseId, repetition);
        ExecutionTrace t = new ExecutionTrace("trace-" + condition + "-" + caseId,
                identity.memoryId(), "v2.3", identity);
        for (ToolCallEvent e : toolCalls) {
            t.addToolCallEvent(e);
        }
        for (ControlOverheadEvent e : controls) {
            t.addControlOverheadEvent(e);
        }
        for (PruningEvent e : prunings) {
            t.addPruningEvent(e);
        }
        return t;
    }

    static ToolCallEvent toolCall(String condition, String caseId, int repetition, int iteration,
                                  String attempted, String executed, boolean blocked, long latency) {
        return ToolCallEvent.of(runId(condition, caseId, repetition), iteration,
                attempted, executed, blocked, Map.of(), null, latency);
    }

    static ControlOverheadEvent controlEvent(String condition, String caseId, int repetition,
                                             ControlType type, int iteration, long latency) {
        return new ControlOverheadEvent(
                runId(condition, caseId, repetition), condition, caseId, repetition,
                type, iteration, latency, null, null, null, null);
    }

    static PruningEvent pruning(String condition, String caseId, int repetition, int iteration) {
        List<ToolScoreResult> decision = List.of(
                new ToolScoreResult("refund",
                        new RelevanceScore(1.0, 1.0, 0.3),
                        new RiskScore(1.0, 0.0),
                        ToolDecisionCandidate.KEEP_CANDIDATE),
                new ToolScoreResult("queryOrder",
                        new RelevanceScore(0.0, 0.0, 0.0),
                        new RiskScore(0.0, 0.0),
                        ToolDecisionCandidate.PRUNE_CANDIDATE));
        return new PruningEvent(
                runId(condition, caseId, repetition), caseId, condition,
                iteration, iteration, 4,
                List.of("refund"), List.of("queryOrder", "queryPoints", "queryCoupons"), decision);
    }

    static RtmpRawRecord raw(String condition, String caseId, int repetition, RtmpTestCase tc,
                             List<ToolCallEvent> toolCalls,
                             List<ControlOverheadEvent> controls,
                             List<PruningEvent> prunings,
                             RunStatus status) {
        ExecutionTrace t = trace(condition, caseId, repetition, toolCalls, controls, prunings);
        RtmpCaseEvaluation eval = status == RunStatus.VALID ? EVALUATOR.evaluate(tc, t) : null;
        String invalidReason = status == RunStatus.VALID ? null
                : (status == RunStatus.INVALID_RUN ? "missing-run-metadata" : "transient-timeout");
        return RtmpRawRecord.of(t, eval, status, tc, invalidReason);
    }
}
