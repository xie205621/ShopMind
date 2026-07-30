/* ============================================================
   MetricCard — SAD.md §2.2, FRONTEND_UI_BLUEPRINT.md §4.2
   Props contract: title, value, delta, trend, statusColor, tooltip, unit, icon
   ============================================================ */

import { memo, type ReactNode } from 'react';
import styles from './MetricCard.module.css';

interface MetricCardProps {
  title: string;
  value: number | string;
  delta?: string;
  trend?: 'up' | 'down' | 'stable';
  statusColor?: 'success' | 'warning' | 'error' | 'default';
  tooltip?: string;
  unit?: string;
  icon?: ReactNode;
}

/** Determine the CSS class name for the delta/trend indicator */
function trendClass(trend?: string, status?: string): string {
  if (status === 'success') return styles.trendUp;
  if (status === 'error')   return styles.trendDown;
  if (status === 'warning') return styles.trendWarning;
  if (trend === 'up')       return styles.trendUp;
  if (trend === 'down')     return styles.trendDown;
  return styles.trendStable;
}

export const MetricCard = memo(function MetricCard({
  title,
  value,
  delta,
  statusColor = 'default',
  tooltip,
  unit,
  icon,
}: MetricCardProps) {
  const displayValue = unit && typeof value === 'number'
    ? `${value}${unit}`
    : String(value);

  // Compute progress bar fill (0-100)
  const progressFill = (() => {
    if (typeof value !== 'number') return 0;
    if (unit === '%' || (!unit)) return Math.min(value, 100);
    return 100; // ms / score — no meaningful progress bar
  })();

  return (
    <div className={styles.card} title={tooltip}>
      <div className={styles.header}>
        <span className={styles.title}>{title}</span>
        {icon && <span className={styles.icon}>{icon}</span>}
      </div>

      <div className={styles.value}>{displayValue}</div>

      <div className={styles.progressTrack}>
        <div
          className={styles.progressFill}
          style={{ width: `${Math.min(progressFill, 100)}%` }}
        />
      </div>

      {delta && (
        <div className={`${styles.delta} ${trendClass(undefined, statusColor)}`}>
          {delta}
        </div>
      )}
    </div>
  );
});
