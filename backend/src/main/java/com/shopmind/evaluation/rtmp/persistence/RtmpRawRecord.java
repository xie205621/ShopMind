package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.evaluation.rtmp.RtmpCaseEvaluation;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.workflow.domain.ControlOverheadEvent;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.PruningEvent;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;

import java.util.List;

/**
 * 一次 RTMP 实验 run 的 Raw 事实记录 — Phase 5-B4。
 * <p>
 * <b>一个 run = 一个完整事实记录</b>。Raw 是唯一事实来源，Summary / Comparison 均从
 * Raw 重新计算。本 record 直接消费 B2 / B3 / P4-3 已冻结的 canonical 对象：
 * <ul>
 *   <li>{@link ToolCallEvent} — 有序工具调用事实（不压缩为 run-level 摘要）；</li>
 *   <li>{@link PruningEvent} — Method C 的 RTMP pruning 决策（Baseline A/B 为空列表）；</li>
 *   <li>{@link ControlOverheadEvent} — B3 的 control overhead 观测（token/cost 恒 null）；</li>
 *   <li>{@link RtmpCaseEvaluation} — B2 的 case-level evaluation（invalid run 可为 null）；</li>
 *   <li>{@link RunStatus} — 实验级运行状态（VALID / RETRYABLE_FAILURE / INVALID_RUN）。</li>
 * </ul>
 * <p>
 * <b>Ground Truth metadata（供 subgroup / over-refusal eligibility 使用）：</b>
 * {@code taskCategory} / {@code expectedOutcome} / {@code expectedToolAction} 从
 * {@link RtmpTestCase} 原样持久化，是 Ground Truth 元数据，不是评估重算。
 *
 * @param runId                 canonical run_id（{@code {experimentId}_{condition}_{caseId}_{repetition}}）
 * @param experimentId          实验全局标识（如 RTMP-EXP01）
 * @param condition             实验条件（BASELINE_A / BASELINE_B / METHOD_C）
 * @param caseId                用例标识（如 RTMP-001）
 * @param repetition            repetition 序号
 * @param conditionOrderIndex   本 case/repetition 内 condition 的执行顺序索引（0/1/2）
 * @param query                 用户查询
 * @param taskCategory          任务类别（{@link com.shopmind.evaluation.rtmp.RtmpTaskCategory} name）
 * @param expectedOutcome       Ground Truth 预期结果（ANSWER_EXPECTED / REFUSE_EXPECTED）
 * @param expectedToolAction    Ground Truth 预期工具行为（CALL / NOT_CALL）
 * @param toolCalls             有序工具调用事件
 * @param pruningEvents         有序 RTMP pruning 事件（Baseline A/B 为空）
 * @param controlOverheadEvents 有序 control overhead 事件
 * @param evaluation            case-level evaluation（invalid run 可为 null）
 * @param status                实验级运行状态
 * @param invalidReason         invalid/retryable 的原因（VALID 为 null）
 * @param runtimeMetrics        runtime 指标快照
 */
public record RtmpRawRecord(
        String runId,
        String experimentId,
        String condition,
        String caseId,
        int repetition,
        int conditionOrderIndex,
        String query,
        String taskCategory,
        String expectedOutcome,
        String expectedToolAction,
        List<ToolCallEvent> toolCalls,
        List<PruningEvent> pruningEvents,
        List<ControlOverheadEvent> controlOverheadEvents,
        RtmpCaseEvaluation evaluation,
        RunStatus status,
        String invalidReason,
        RtmpRawRuntimeMetrics runtimeMetrics
) {

    public RtmpRawRecord {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        pruningEvents = pruningEvents != null ? List.copyOf(pruningEvents) : List.of();
        controlOverheadEvents = controlOverheadEvents != null
                ? List.copyOf(controlOverheadEvents) : List.of();
        runtimeMetrics = runtimeMetrics != null
                ? runtimeMetrics : new RtmpRawRuntimeMetrics(0, 0, 0, 0, 0);
    }

    /**
     * 从 canonical sources 构建 Raw record（无 execution-order provenance，默认 index=0）。
     */
    public static RtmpRawRecord of(ExecutionTrace trace, RtmpCaseEvaluation evaluation,
                                   RunStatus status, RtmpTestCase testCase, String invalidReason) {
        return of(trace, evaluation, status, testCase, invalidReason, 0);
    }

    /**
     * 从 canonical sources 构建 Raw record（含 execution-order provenance）。
     *
     * @param trace                canonical Raw {@link ExecutionTrace}（必须非 null）
     * @param evaluation           B2 case-level evaluation（invalid run 可为 null）
     * @param status               {@link RunStatus} 分类结果
     * @param testCase             Ground Truth（提供 query / taskCategory / expectedOutcome / expectedToolAction）
     * @param invalidReason        invalid/retryable 原因（VALID 为 null）
     * @param conditionOrderIndex  本 case/repetition 内 condition 的执行顺序索引（0/1/2）
     */
    public static RtmpRawRecord of(ExecutionTrace trace, RtmpCaseEvaluation evaluation,
                                   RunStatus status, RtmpTestCase testCase, String invalidReason,
                                   int conditionOrderIndex) {
        RunIdentity identity = trace != null ? trace.getRunIdentity() : null;
        String runId = identity != null ? identity.runId()
                : (evaluation != null ? evaluation.runId() : null);
        String experimentId = identity != null ? identity.experimentId() : null;
        String condition = identity != null ? identity.condition()
                : (evaluation != null ? evaluation.condition() : null);
        String caseId = identity != null ? identity.caseId()
                : (evaluation != null ? evaluation.caseId()
                : (testCase != null ? testCase.id() : null));
        int repetition = identity != null ? identity.repetition()
                : (evaluation != null ? evaluation.repetition() : 0);

        return new RtmpRawRecord(
                runId, experimentId, condition, caseId, repetition, conditionOrderIndex,
                testCase != null ? testCase.query() : null,
                testCase != null ? testCase.taskCategory().name() : null,
                testCase != null ? testCase.expectedOutcome() : null,
                testCase != null ? testCase.expectedToolAction().name() : null,
                trace != null ? trace.getToolCallEvents() : List.of(),
                trace != null ? trace.getPruningEvents() : List.of(),
                trace != null ? trace.getControlOverheadEvents() : List.of(),
                evaluation,
                status,
                invalidReason,
                RtmpRawRuntimeMetrics.from(trace != null ? trace.getMetrics() : null));
    }
}
        