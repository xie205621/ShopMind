/* ============================================================
   Metric Cards Configuration — SAD.md §2.2 MetricGrid
   Defines the 8 standard metric cards for the dashboard.
   ============================================================ */

import type { MetricSummary, CostSummary, MetricCardConfig } from '../../../shared/types/evaluation';

/** Standard 8 metrics displayed in the MetricGrid */
export const METRIC_CARD_CONFIGS: MetricCardConfig[] = [
  {
    key: 'intentAccuracy',
    title: 'Intent Accuracy',
    unit: '%',
    icon: '\uD83C\uDFAF',
    format: 'percent',
    lowerIsBetter: false,
    getValue: (m: MetricSummary) => m.intentAccuracy * 100,
  },
  {
    key: 'avgRecallAtK',
    title: 'Avg Recall@K',
    unit: '',
    icon: '\uD83D\uDCC8',
    format: 'score',
    lowerIsBetter: false,
    getValue: (m: MetricSummary) => m.avgRecallAtK,
  },
  {
    key: 'hallucinationRate',
    title: 'Hallucination',
    unit: '%',
    icon: '\u26A0\uFE0F',
    format: 'percent',
    lowerIsBetter: true,
    getValue: (m: MetricSummary) => m.hallucinationRate * 100,
  },
  {
    key: 'toolAccuracy',
    title: 'Tool Accuracy',
    unit: '%',
    icon: '\uD83D\uDD27',
    format: 'percent',
    lowerIsBetter: false,
    getValue: (m: MetricSummary) => m.toolAccuracy * 100,
  },
  {
    key: 'taskSuccessRate',
    title: 'Task Success',
    unit: '%',
    icon: '\u2714\uFE0F',
    format: 'percent',
    lowerIsBetter: false,
    getValue: (m: MetricSummary) => m.taskSuccessRate * 100,
  },
  {
    key: 'avgTtftMs',
    title: 'Avg TTFT',
    unit: 'ms',
    icon: '\u26A1',
    format: 'ms',
    lowerIsBetter: true,
    getValue: (m: MetricSummary) => m.avgTtftMs,
  },
  {
    key: 'p95LatencyMs',
    title: 'P95 Latency',
    unit: 'ms',
    icon: '\uD83D\uDCCA',
    format: 'ms',
    lowerIsBetter: true,
    getValue: (m: MetricSummary) => m.p95LatencyMs,
  },
  {
    key: 'workflowCompletionRate',
    title: 'Workflow Complete',
    unit: '%',
    icon: '\uD83D\uDD17',
    format: 'percent',
    lowerIsBetter: false,
    getValue: (m: MetricSummary) => m.workflowCompletionRate * 100,
  },
];

/** Get status color for a metric card based on its value and direction */
export function getMetricStatus(
  value: number,
  lowerIsBetter: boolean,
): 'success' | 'warning' | 'error' | 'default' {
  if (lowerIsBetter) {
    if (value === 0) return 'success';
    if (value < 5) return 'success';
    if (value < 15) return 'warning';
    return 'error';
  }
  if (value >= 90) return 'success';
  if (value >= 70) return 'default';
  if (value >= 50) return 'warning';
  return 'error';
}

/** Generate delta text comparing current value to previous */
export function getDeltaText(
  title: string,
  current: number,
  previous: number | undefined,
  unit: string,
): { text: string; status: 'success' | 'error' | 'warning' | 'default' } | null {
  if (previous === undefined) return null;
  const diff = current - previous;
  const sign = diff >= 0 ? '+' : '';
  const formattedUnit = unit === '%' ? 'pp' : unit;
  const text = `${sign}${diff.toFixed(1)}${formattedUnit} vs previous`;

  const isImprovement = (key: string) => {
    // For these metrics, lower is better
    return !['Hallucination', 'Avg TTFT', 'P95 Latency'].includes(key);
  };

  if (isImprovement(title)) {
    const status = diff >= 0 ? 'success' : 'error';
    return { text, status };
  }
  const status = diff <= 0 ? 'success' : 'error';
  return { text, status };
}
