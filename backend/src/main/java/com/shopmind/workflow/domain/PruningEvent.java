package com.shopmind.workflow.domain;

import com.shopmind.experiment.ToolScoreResult;

import java.util.List;

/**
 * 一次 RTMP Router pruning 决策的 runtime observation — Phase 4 (P4-3)。
 * <p>
 * 记录 Router 在<b>某一次 LLM 迭代</b>对工具菜单的裁剪事实，与 {@link ToolCallEvent}
 * 一样属于 Runtime Observation（不是 Evaluation Result）。一次 run 可包含多个有序
 * PruningEvent（每次 LLM 迭代产生一个），禁止用 run-level 单值字段覆盖历史事实。
 * <p>
 * {@code pruningDecision} 直接复用 P4-2.1 冻结的 {@link ToolScoreResult}，不引入第二套
 * scoring DTO。Baseline A/B 使用 {@code AllToolsVisibility}（不做裁剪），故不产生
 * PruningEvent（pruningDecision 为空时无需记录）。
 *
 * @param runId           关联的 canonical run_id（{@link RunIdentity#runId()}，legacy 可为 null）
 * @param caseId          关联的 RTMP caseId（无 RunIdentity 时为 null）
 * @param condition       实验条件名（BASELINE_A / BASELINE_B / METHOD_C）
 * @param routerCallIndex 本次 Router 调用序号（从 1 开始）
 * @param iteration       所属 LLM 工具循环迭代号（第一次 LLM 调用为 1）
 * @param inputToolCount  裁剪前的工具总数
 * @param visibleTools    裁剪后可见工具名（有序）
 * @param prunedTools     被裁剪工具名（有序）
 * @param pruningDecision 每个工具的评分决策（canonical source）
 */
public record PruningEvent(
        String runId,
        String caseId,
        String condition,
        int routerCallIndex,
        int iteration,
        int inputToolCount,
        List<String> visibleTools,
        List<String> prunedTools,
        List<ToolScoreResult> pruningDecision
) {

    public PruningEvent {
        visibleTools = visibleTools != null ? List.copyOf(visibleTools) : List.of();
        prunedTools = prunedTools != null ? List.copyOf(prunedTools) : List.of();
        pruningDecision = pruningDecision != null ? List.copyOf(pruningDecision) : List.of();
    }
}
