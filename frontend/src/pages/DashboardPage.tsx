/* ============================================================
   DashboardPage — SAD.md §2.2, FRONTEND_UI_BLUEPRINT.md §4.1

   Layout (top → bottom):
     PageHeader
     MetricGrid (8 MetricCards, 2 rows × 4 cols)
     ChartsRow (MetricsRadarChart | FailurePieChart)
     ExperimentTable (full width)

   Data flow:
     useExperimentReport() → evaluationStore → selectors

   Loading strategy (SAD.md §8):
     1. MetricGrid: Skeleton (8 placeholders)
     2. ChartsRow: Skeleton (2 chart placeholders)
     3. ExperimentTable: Skeleton rows

   Error handling (SAD.md §7):
     - Section-level: individual cards show error badge
     - Full page error: ErrorFallback with [Retry]
   ============================================================ */

import { memo, useMemo } from 'react';
import { Button, Skeleton } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEvaluationStore } from '../features/evaluation/store/evaluationStore';
import { useExperimentReport } from '../features/evaluation/hooks/useExperimentReport';
import { MetricCard } from '../features/evaluation/components/MetricCard';
import { ChartCard } from '../features/evaluation/components/ChartCard';
import { MetricsRadarChart } from '../features/evaluation/components/MetricsRadarChart';
import { FailurePieChart } from '../features/evaluation/components/FailurePieChart';
import { ExperimentTable } from '../features/evaluation/components/ExperimentTable';
import { ErrorFallback } from '../shared/components/ErrorFallback';
import { EmptyState } from '../shared/components/EmptyState';
import {
  METRIC_CARD_CONFIGS,
  getMetricStatus,
} from '../features/evaluation/components/metricCardConfig';
import styles from './DashboardPage.module.css';

const DashboardPage = memo(function DashboardPage() {
  const { reload } = useExperimentReport();

  const report = useEvaluationStore((s) => s.currentReport);
  const reportLoading = useEvaluationStore((s) => s.reportLoading);
  const reportError = useEvaluationStore((s) => s.reportError);
  const experiments = useEvaluationStore((s) => s.experiments);
  const experimentsLoading = useEvaluationStore((s) => s.experimentsLoading);

  // ── Full-page error state ──
  if (reportError && !report) {
    return (
      <div className={styles.page}>
        <ErrorFallback error={reportError} onRetry={reload} />
      </div>
    );
  }

  // ── Empty state ──
  if (!reportLoading && !report) {
    return (
      <div className={styles.page}>
        <EmptyState
          title="No experiment data"
          description="Run a benchmark to see results here."
          action={<Button type="default" onClick={reload}>Load Data</Button>}
        />
      </div>
    );
  }

  return (
    <div className={styles.page}>
      {/* ── Page Header ── */}
      <div className={styles.header}>
        <div className={styles.headerLeft}>
          <h1 className={styles.pageTitle}>Evaluation Dashboard</h1>
        </div>
        <Button
          icon={<ReloadOutlined />}
          onClick={reload}
          loading={reportLoading}
        >
          Refresh
        </Button>
      </div>

      {/* ── 1. MetricGrid: 8 MetricCards ── */}
      <div className={styles.metricGrid}>
        {reportLoading
          ? Array.from({ length: 8 }).map((_, i) => (
              <Skeleton.Input
                key={i}
                active
                block
                style={{
                  height: 130,
                  minWidth: 240,
                  background: 'var(--bg-elevated)',
                  borderRadius: 'var(--radius-xl)',
                }}
              />
            ))
          : report && METRIC_CARD_CONFIGS.map((config) => {
              const value = config.getValue(report.metrics);
              const status = getMetricStatus(
                typeof value === 'number' ? value : 0,
                config.lowerIsBetter,
              );

              return (
                <MetricCard
                  key={config.key}
                  title={config.title}
                  value={
                    config.format === 'percent'
                      ? Number(value.toFixed(1))
                      : config.format === 'ms'
                        ? Math.round(value)
                        : value
                  }
                  unit={config.unit}
                  icon={<span>{config.icon}</span>}
                  statusColor={status}
                  tooltip={`${config.title} for experiment ${report.experimentId}`}
                />
              );
            })}
      </div>

      {/* ── 2. ChartsRow: Radar + Pie ── */}
      <div className={styles.chartsRow}>
        <ChartCard
          title="Metrics Radar"
          icon={<span>&#x1F4CA;</span>}
          loading={reportLoading}
        >
          {report && <MetricsRadarChart metrics={report.metrics} />}
        </ChartCard>

        <ChartCard
          title="Failure Distribution"
          icon={<span>&#x1F4CA;</span>}
          loading={reportLoading}
        >
          {report && <FailurePieChart distribution={report.failureDistribution} />}
        </ChartCard>
      </div>

      {/* ── 3. ExperimentTable ── */}
      <div className={styles.tableSection}>
        <div className={styles.tableHeader}>
          <h2 className={styles.tableTitle}>Experiment History</h2>
        </div>
        <div className={styles.tableCard}>
          <ExperimentTable
            data={experiments}
            loading={experimentsLoading}
          />
        </div>
      </div>
    </div>
  );
});

export default DashboardPage;
