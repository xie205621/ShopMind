/* ============================================================
   Data loading hook — SAD.md §4.2, §5
   Phase 1: loads from static JSON files (src/data/)
   Phase 2: switches to GET /api/evaluation/experiments/:id
   ============================================================ */

import { useEffect, useCallback } from 'react';
import { useEvaluationStore } from '../store/evaluationStore';
import type { ExperimentReport, ExperimentListItem } from '../../../shared/types/evaluation';

// Phase 1: static import
import v21Data from '../../../data/benchmark_v2.1.json';
import v20Data from '../../../data/benchmark_v2.0.json';

/** Build a list item from a full experiment report */
function toListItem(report: ExperimentReport): ExperimentListItem {
  return {
    experimentId: report.experimentId,
    workflowVersion: report.workflowVersion,
    datasetVersion: report.datasetVersion,
    generatedAt: report.generatedAt,
    totalCases: report.totalCases,
    passedCases: report.passedCases,
    metrics: report.metrics,
    cost: report.cost,
  };
}

/** Phase 1: load the latest experiment into the store */
export function useExperimentReport() {
  const {
    setCurrentReport,
    setReportLoading,
    setReportError,
    setExperiments,
    setExperimentsLoading,
  } = useEvaluationStore();

  const load = useCallback(() => {
    setReportLoading(true);
    setExperimentsLoading(true);

    try {
      // Simulate async load (production: axios GET)
      const report = v21Data as unknown as ExperimentReport;
      setCurrentReport(report);

      const list = [
        toListItem(report),
        toListItem(v20Data as unknown as ExperimentReport),
      ];
      setExperiments(list);
    } catch (err) {
      setReportError(err instanceof Error ? err.message : 'Failed to load experiment data');
    } finally {
      setReportLoading(false);
      setExperimentsLoading(false);
    }
  }, [setCurrentReport, setReportLoading, setReportError, setExperiments, setExperimentsLoading]);

  useEffect(() => {
    load();
  }, [load]);

  return { reload: load };
}

/** Phase 1: load comparison data from two JSON files */
export function useComparison() {
  const { setComparison, setComparisonLoading, setComparisonError } = useEvaluationStore();

  const load = useCallback(() => {
    setComparisonLoading(true);
    try {
      const baseline = v20Data as unknown as ExperimentReport;
      const current = v21Data as unknown as ExperimentReport;

      const dimensions = [
        {
          name: 'Intent Accuracy',
          baseline: baseline.metrics.intentAccuracy,
          current: current.metrics.intentAccuracy,
          delta: current.metrics.intentAccuracy - baseline.metrics.intentAccuracy,
          deltaPercent: baseline.metrics.intentAccuracy > 0
            ? ((current.metrics.intentAccuracy - baseline.metrics.intentAccuracy) / baseline.metrics.intentAccuracy) * 100
            : 0,
          unit: 'rate' as const,
        },
        {
          name: 'Avg Recall@K',
          baseline: baseline.metrics.avgRecallAtK,
          current: current.metrics.avgRecallAtK,
          delta: current.metrics.avgRecallAtK - baseline.metrics.avgRecallAtK,
          deltaPercent: baseline.metrics.avgRecallAtK > 0
            ? ((current.metrics.avgRecallAtK - baseline.metrics.avgRecallAtK) / baseline.metrics.avgRecallAtK) * 100
            : 0,
          unit: 'score' as const,
        },
        {
          name: 'Hallucination Rate',
          baseline: baseline.metrics.hallucinationRate,
          current: current.metrics.hallucinationRate,
          delta: current.metrics.hallucinationRate - baseline.metrics.hallucinationRate,
          deltaPercent: baseline.metrics.hallucinationRate > 0
            ? ((current.metrics.hallucinationRate - baseline.metrics.hallucinationRate) / baseline.metrics.hallucinationRate) * 100
            : 0,
          unit: 'rate' as const,
        },
        {
          name: 'Tool Accuracy',
          baseline: baseline.metrics.toolAccuracy,
          current: current.metrics.toolAccuracy,
          delta: current.metrics.toolAccuracy - baseline.metrics.toolAccuracy,
          deltaPercent: baseline.metrics.toolAccuracy > 0
            ? ((current.metrics.toolAccuracy - baseline.metrics.toolAccuracy) / baseline.metrics.toolAccuracy) * 100
            : 0,
          unit: 'rate' as const,
        },
        {
          name: 'Task Success Rate',
          baseline: baseline.metrics.taskSuccessRate,
          current: current.metrics.taskSuccessRate,
          delta: current.metrics.taskSuccessRate - baseline.metrics.taskSuccessRate,
          deltaPercent: baseline.metrics.taskSuccessRate > 0
            ? ((current.metrics.taskSuccessRate - baseline.metrics.taskSuccessRate) / baseline.metrics.taskSuccessRate) * 100
            : 0,
          unit: 'rate' as const,
        },
        {
          name: 'Avg TTFT',
          baseline: baseline.metrics.avgTtftMs,
          current: current.metrics.avgTtftMs,
          delta: current.metrics.avgTtftMs - baseline.metrics.avgTtftMs,
          deltaPercent: baseline.metrics.avgTtftMs > 0
            ? ((current.metrics.avgTtftMs - baseline.metrics.avgTtftMs) / baseline.metrics.avgTtftMs) * 100
            : 0,
          unit: 'ms' as const,
        },
        {
          name: 'P95 Latency',
          baseline: baseline.metrics.p95LatencyMs,
          current: current.metrics.p95LatencyMs,
          delta: current.metrics.p95LatencyMs - baseline.metrics.p95LatencyMs,
          deltaPercent: baseline.metrics.p95LatencyMs > 0
            ? ((current.metrics.p95LatencyMs - baseline.metrics.p95LatencyMs) / baseline.metrics.p95LatencyMs) * 100
            : 0,
          unit: 'ms' as const,
        },
        {
          name: 'Workflow Completion',
          baseline: baseline.metrics.workflowCompletionRate,
          current: current.metrics.workflowCompletionRate,
          delta: current.metrics.workflowCompletionRate - baseline.metrics.workflowCompletionRate,
          deltaPercent: baseline.metrics.workflowCompletionRate > 0
            ? ((current.metrics.workflowCompletionRate - baseline.metrics.workflowCompletionRate) / baseline.metrics.workflowCompletionRate) * 100
            : 0,
          unit: 'rate' as const,
        },
      ];

      setComparison({
        baselineId: baseline.experimentId,
        baselineLabel: `v${baseline.workflowVersion}`,
        currentId: current.experimentId,
        currentLabel: `v${current.workflowVersion}`,
        dimensions,
      });
    } catch (err) {
      setComparisonError(err instanceof Error ? err.message : 'Failed to load comparison');
    } finally {
      setComparisonLoading(false);
    }
  }, [setComparison, setComparisonLoading, setComparisonError]);

  return { load };
}
