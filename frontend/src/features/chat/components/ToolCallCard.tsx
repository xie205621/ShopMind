/* ============================================================
   ToolCallCard — SAD.md §2.1, FRONTEND_UI_BLUEPRINT.md §3.2
   bg-elevated, radius-md, collapsible.
   States: PENDING (spinner) | SUCCESS (✓) | FAILED (✗) | TIMEOUT (⏱)
   ============================================================ */

import { memo, useState } from 'react';
import type { ToolCall } from '../../../shared/types/chat';
import styles from './ToolCallCard.module.css';

interface ToolCallCardProps {
  tool: ToolCall;
  defaultExpanded?: boolean;
}

export const ToolCallCard = memo(function ToolCallCard({
  tool,
  defaultExpanded = false,
}: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);

  const hasResult = !!tool.result;
  const isSuccess = tool.result?.success;

  return (
    <div className={styles.card}>
      <button
        className={styles.header}
        onClick={() => setExpanded(!expanded)}
      >
        <span className={styles.status}>
          {!hasResult
            ? <span className={styles.spinner} />
            : isSuccess
              ? <span className={styles.successIcon}>✓</span>
              : <span className={styles.errorIcon}>✗</span>
          }
        </span>
        <span className={styles.toolName}>{tool.toolName}</span>
        {tool.result && (
          <span className={styles.latency}>{tool.result.latencyMs}ms</span>
        )}
        <span className={styles.chevron}>{expanded ? '▾' : '▸'}</span>
      </button>

      {expanded && (
        <div className={styles.body}>
          <div className={styles.section}>
            <span className={styles.label}>Arguments</span>
            <pre className={styles.code}>
              {JSON.stringify(tool.args, null, 2)}
            </pre>
          </div>

          {tool.result && (
            <div className={`${styles.section} ${isSuccess ? styles.resultSuccess : styles.resultError}`}>
              <span className={styles.label}>
                {isSuccess ? 'Result' : 'Error'}
              </span>
              <div className={styles.resultText}>{tool.result.output}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
});
