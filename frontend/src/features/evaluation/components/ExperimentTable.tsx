/* ============================================================
   ExperimentTable — SAD.md §2.2, §4.4
   Displays all experiment runs with sortable columns.
   ============================================================ */

import { memo, useMemo } from 'react';
import { Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ExperimentListItem } from '../../../shared/types/evaluation';
import styles from './ExperimentTable.module.css';

interface ExperimentTableProps {
  data: ExperimentListItem[];
  loading?: boolean;
  onRowClick?: (experimentId: string) => void;
}

function formatPercent(v: number): string {
  return `${(v * 100).toFixed(1)}%`;
}
function formatMs(v: number): string {
  return `${v}ms`;
}

function hallucinationTag(rate: number) {
  if (rate === 0) return <Tag color="green">0%</Tag>;
  if (rate < 0.05) return <Tag color="orange">{formatPercent(rate)}</Tag>;
  return <Tag color="red">{formatPercent(rate)}</Tag>;
}

function passedTag(passed: number, total: number) {
  const rate = passed / total;
  return (
    <span className={rate >= 0.5 ? styles.passedGood : styles.passedBad}>
      {passed}/{total}
    </span>
  );
}

export const ExperimentTable = memo(function ExperimentTable({
  data,
  loading = false,
  onRowClick,
}: ExperimentTableProps) {
  const columns: ColumnsType<ExperimentListItem> = useMemo(() => [
    {
      title: 'ID',
      dataIndex: 'experimentId',
      key: 'id',
      width: 100,
      render: (id: string) => (
        <span className={styles.mono}>{id}</span>
      ),
    },
    {
      title: 'Version',
      dataIndex: 'workflowVersion',
      key: 'version',
      width: 80,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: 'Date',
      dataIndex: 'generatedAt',
      key: 'date',
      width: 100,
      render: (d: string) => new Date(d).toLocaleDateString(),
    },
    {
      title: 'Passed',
      key: 'passed',
      width: 80,
      render: (_, r) => passedTag(r.passedCases, r.totalCases),
    },
    {
      title: 'Intent',
      key: 'intent',
      width: 90,
      render: (_, r) => formatPercent(r.metrics.intentAccuracy),
    },
    {
      title: 'Recall',
      key: 'recall',
      width: 80,
      render: (_, r) => r.metrics.avgRecallAtK.toFixed(3),
    },
    {
      title: 'Halluc',
      key: 'halluc',
      width: 100,
      render: (_, r) => hallucinationTag(r.metrics.hallucinationRate),
    },
    {
      title: 'Tool Acc',
      key: 'tool',
      width: 90,
      render: (_, r) => formatPercent(r.metrics.toolAccuracy),
    },
    {
      title: 'TTFT',
      key: 'ttft',
      width: 80,
      render: (_, r) => formatMs(r.metrics.avgTtftMs),
    },
    {
      title: 'P95',
      key: 'p95',
      width: 90,
      render: (_, r) => formatMs(r.metrics.p95LatencyMs),
    },
    {
      title: 'Success',
      key: 'success',
      width: 90,
      render: (_, r) => formatPercent(r.metrics.taskSuccessRate),
    },
    {
      title: 'Cost',
      key: 'cost',
      width: 80,
      render: (_, r) => `$${r.cost.estimatedCostUsd.toFixed(4)}`,
    },
  ], []);

  return (
    <Table<ExperimentListItem>
      className={styles.table}
      columns={columns}
      dataSource={data}
      rowKey="experimentId"
      loading={loading}
      pagination={false}
      size="small"
      onRow={(record) => ({
        onClick: () => onRowClick?.(record.experimentId),
        style: { cursor: onRowClick ? 'pointer' : 'default' },
      })}
      scroll={{ x: 1100 }}
    />
  );
});
