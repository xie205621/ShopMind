/* ============================================================
   MetricsRadarChart — SAD.md §2.2, FRONTEND_UI_BLUEPRINT.md §4.3
   8-dimensional radar chart via ECharts.
   Supports dual-version overlay for comparison mode.
   ============================================================ */

import { memo, useMemo } from 'react';
// 1. 先把它作为一个 Module 导入
import ReactEChartsCoreModule from 'echarts-for-react/lib/core';

// 2. 兼容性剥离：如果 Vite 把它包装成了 { default: Component }，我们就取 .default；否则直接用它本身
const ReactEChartsCore = (ReactEChartsCoreModule as any).default || ReactEChartsCoreModule;
import * as echarts from 'echarts/core';
import { RadarChart } from 'echarts/charts';
import {
  TitleComponent, TooltipComponent, LegendComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { MetricSummary } from '../../../shared/types/evaluation';

echarts.use([RadarChart, TitleComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface MetricsRadarChartProps {
  metrics: MetricSummary;
  comparisonMetrics?: MetricSummary;
  comparisonLabel?: string;
}

const DIMENSIONS = [
  { name: 'Intent Accuracy',    key: 'intentAccuracy' as const,       max: 1 },
  { name: 'Recall@K',           key: 'avgRecallAtK' as const,         max: 1 },
  { name: 'Hallucination',      key: 'hallucinationRate' as const,    max: 1, invert: true },
  { name: 'Tool Accuracy',      key: 'toolAccuracy' as const,         max: 1 },
  { name: 'Task Success',       key: 'taskSuccessRate' as const,      max: 1 },
  { name: 'TTFT (fast)',        key: 'avgTtftMs' as const,            max: 2000, invert: true },
  { name: 'P95 (fast)',         key: 'p95LatencyMs' as const,          max: 6000, invert: true },
  { name: 'Workflow Complete',  key: 'workflowCompletionRate' as const, max: 1 },
];

/** Normalize a metric to 0-100 scale, inverting if lower is better */
function normalize(key: string, value: number, max: number, invert: boolean): number {
  const raw = value / max;
  const clamped = Math.min(Math.max(raw, 0), 1);
  return invert ? (1 - clamped) * 100 : clamped * 100;
}

export const MetricsRadarChart = memo(function MetricsRadarChart({
  metrics,
  comparisonMetrics,
  comparisonLabel = 'baseline',
}: MetricsRadarChartProps) {
  const option = useMemo(() => {
    const series: Array<Record<string, unknown>> = [
      {
        type: 'radar',
        data: [
          {
            value: DIMENSIONS.map(d => normalize(d.key, metrics[d.key], d.max, !!d.invert)),
            name: 'This experiment',
          },
        ],
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: '#3b82f6', width: 2 },
        areaStyle: { color: 'rgba(59,130,246,0.08)' },
        itemStyle: { color: '#3b82f6' },
      },
    ];

    if (comparisonMetrics) {
      series.push({
        type: 'radar',
        data: [
          {
            value: DIMENSIONS.map(d => normalize(d.key, comparisonMetrics[d.key], d.max, !!d.invert)),
            name: comparisonLabel,
          },
        ],
        symbol: 'diamond',
        symbolSize: 6,
        lineStyle: { color: '#64748b', width: 2, type: 'dashed' as const },
        areaStyle: { color: 'rgba(100,116,139,0.06)' },
        itemStyle: { color: '#64748b' },
      });
    }

    return {
      radar: {
        indicator: DIMENSIONS.map(d => ({ name: d.name, max: 100 })),
        center: ['50%', '50%'],
        radius: '65%',
        axisName: { color: '#8896a7', fontSize: 11, fontFamily: 'Inter, sans-serif' },
        splitArea: {
          areaStyle: { color: ['rgba(26,34,50,0.3)', 'rgba(26,34,50,0.5)'] },
        },
        splitLine: { lineStyle: { color: '#1a2232' } },
        axisLine: { lineStyle: { color: '#1a2232' } },
      },
      series,
      tooltip: {
        backgroundColor: '#161c28',
        borderColor: '#243049',
        textStyle: { color: '#e8ecf1', fontSize: 12 },
      },
      legend: {
        bottom: 0,
        textStyle: { color: '#8896a7', fontSize: 12 },
        data: comparisonMetrics ? ['This experiment', comparisonLabel] : ['This experiment'],
      },
    };
  }, [metrics, comparisonMetrics, comparisonLabel]);

  return (
    <ReactEChartsCore
      echarts={echarts}
      option={option}
      style={{ height: 340 }}
      notMerge
      lazyUpdate
    />
  );
});
