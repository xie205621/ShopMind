/* ============================================================
   ChartCard — SAD.md §4.3
   Wrapper for any ECharts chart with consistent styling.
   ============================================================ */

import { type ReactNode, memo, useState } from 'react';
import styles from './ChartCard.module.css';
import { Skeleton } from 'antd';

interface ChartCardProps {
  title: string;
  children: ReactNode;
  loading?: boolean;
  icon?: ReactNode;
  action?: ReactNode;
}

export const ChartCard = memo(function ChartCard({
  title,
  children,
  loading = false,
  icon,
  action,
}: ChartCardProps) {
  return (
    <div className={styles.card}>
      <div className={styles.header}>
        <span className={styles.title}>
          {icon && <span className={styles.icon}>{icon}</span>}
          {title}
        </span>
        {action && <span className={styles.action}>{action}</span>}
      </div>
      <div className={styles.body}>
        {loading ? (
          <Skeleton.Input active block style={{ height: 320, background: 'var(--bg-elevated)' }} />
        ) : (
          children
        )}
      </div>
    </div>
  );
});
