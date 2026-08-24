package com.shopmind.evaluation.rtmp;

/**
 * RTMP case-level evaluation result — Phase 5-B2。
 * <p>
 * 对一个已完成的 run，输出确定性、可审计的 case-level evaluation。
 * 本 record 是 {@code Raw（ExecutionTrace/ToolCallEvent）+ Ground Truth（RtmpTestCase）}
 * 经 {@link RtmpCaseEvaluator} 纯后处理得到的<b>评估结果</b>，不是 Runtime Observation。
 * <p>
 * 语义分层：
 * <ul>
 *   <li>{@code safetyIntervention} — 是否发生 Safety Intervention（verifier 拦截）；</li>
 *   <li>{@code l1GenericSafetyViolation} / {@code l2HighRiskToolMisuse} /
 *       {@code l3ActualSafetyViolation} — 三层安全指标，分别报告；</li>
 *   <li>{@code coreTaskEligible} / {@code coreTaskSuccess} — Core Task Success 的
 *       eligibility 与 success（eligibility = ANSWER_EXPECTED ∧ taskCategory != AMBIGUOUS_BOUNDARY）；</li>
 *   <li>{@code overRefusal} — 过度拒绝/错误裁剪导致正常任务无法完成。</li>
 * </ul>
 *
 * @param runId                     canonical run_id（无 RunIdentity 时为 trace runId 或 null）
 * @param condition                 实验条件（无 RunIdentity 时为 null）
 * @param caseId                    用例标识（无 RunIdentity 时回退为 Ground Truth id）
 * @param repetition                repetition 序号（无 RunIdentity 时为 0）
 * @param safetyIntervention        是否发生 Safety Intervention
 * @param l1GenericSafetyViolation  L1 通用安全违规
 * @param l2HighRiskToolMisuse      L2 高风险工具误用
 * @param l3ActualSafetyViolation   L3 实际安全违规（排除 Safety Intervention）
 * @param coreTaskEligible          是否进入 Core Task Success 主分母（30-case 协议）
 * @param coreTaskSuccess           核心任务是否成功
 * @param overRefusal               是否过度拒绝
 * @param attemptedTool             run 级摘要：最后一次非 null 的 attemptedTool（无工具调用为 null）
 * @param executedTool              run 级摘要：最后一次非 null 的 executedTool（无工具调用为 null）
 * @param verifierBlocked           run 级摘要：是否发生拦截（无任何 ToolCallEvent 为 null）
 * @param outcomeCategory           非统计性结果分类（见 {@link RtmpCaseEvaluator} 常量）
 * @param reason                    判定依据（可审计）
 */
public record RtmpCaseEvaluation(
        String runId,
        String condition,
        String caseId,
        int repetition,

        boolean safetyIntervention,

        boolean l1GenericSafetyViolation,
        boolean l2HighRiskToolMisuse,
        boolean l3ActualSafetyViolation,

        boolean coreTaskEligible,
        boolean coreTaskSuccess,

        boolean overRefusal,

        String attemptedTool,
        String executedTool,
        Boolean verifierBlocked,

        String outcomeCategory,
        String reason
) {
}
