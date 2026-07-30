/* ============================================================
   FailurePieChart — SAD.md §2.2, FRONTEND_UI_BLUEPRINT.md §4.3
   Failure distribution pie chart with 7 root cause categories.
   ============================================================ */

import { memo, useMemo } from 'react';
// 1. 先把它作为一个 Module 导入
import ReactEChartsCoreModule from 'echarts-for-react/lib/core';

// 2. 兼容性剥离：如果 Vite 把它包装成了 { default: Component }，我们就取 .default；否则直接用它本身
const ReactEChartsCore = (ReactEChartsCoreModule as any).default || ReactEChartsCoreModule;
import * as echarts from 'echarts/core';
import { PieChart } from 'echarts/charts';
import { TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { FailureDistribution } from '../../../shared/types/evaluation';

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer]);

/** Color map matching generate_figures.py chart colors */
const FAILURE_PALETTE: Record<string, string> = {
  WRONG_INTENT:    '#6366f1',
  WRONG_TOOL:      '#3b82f6',
  WRONG_PARAMETER: '#06b6d4',
  KNOWLEDGE_MISS:  '#a855f7',
  HALLUCINATION:   '#ef4444',
  SAFETY_BLOCKED:  '#f59e0b',
  TIMEOUT:         '#64748b',
};

const LABEL_MAP: Record<string, string> = {
  WRONG_INTENT:    'Intent Error',
  WRONG_TOOL:      'Tool Error',
  WRONG_PARAMETER: 'Param Error',
  KNOWLEDGE_MISS:  'Knowledge Miss',
  HALLUCINATION:   'Hallucination',
  SAFETY_BLOCKED:  'Safety Blocked',
  TIMEOUT:         'Timeout',
};

interface FailurePieChartProps {
  distribution: FailureDistribution;
}

export const FailurePieChart = memo(function FailurePieChart({
  distribution,
}: FailurePieChartProps) {
  const option = useMemo(() => {
    const entries = Object.entries(distribution)
      .filter(([, v]) => v > 0)
      .sort((a, b) => b[1] - a[1]);

    const data = entries.map(([key, value]) => ({
      name: LABEL_MAP[key] ?? key,
      value: Math.round(value * 1000) / 10, // percentage with 1 decimal
      itemStyle: { color: FAILURE_PALETTE[key] ?? '#64748b' },
    }));

    return {
      series: [
        {
          type: 'pie',
          radius: ['45%', '75%'],
          center: ['50%', '55%'],
          avoidLabelOverlap: false,
          itemStyle: { borderColor: '#111620', borderWidth: 2, borderRadius: 2 },
          label: {
            show: true,
            position: 'outside',
            color: '#8896a7',
            fontSize: 11,
            fontFamily: 'Inter, sans-serif',
            formatter: '{b}\n{d}%',
          },
          labelLine: { lineStyle: { color: '#243049' } },
          data,
        },
      ],
      tooltip: {
        backgroundColor: '#161c28',
        borderColor: '#243049',
        textStyle: { color: '#e8ecf1', fontSize: 12 },
        formatter: '{b}: {d}% ({c}%)',
      },
    };
  }, [distribution]);

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
