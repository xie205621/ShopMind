/* ============================================================
   ErrorFallback — SAD.md §7
   Error recovery UI with [Retry] action.
   ============================================================ */

import styles from './ErrorFallback.module.css';

interface ErrorFallbackProps {
  error: string;
  onRetry?: () => void;
}

export function ErrorFallback({ error, onRetry }: ErrorFallbackProps) {
  return (
    <div className={styles.wrapper}>
      <div className={styles.icon}>!</div>
      <p className={styles.title}>Something went wrong</p>
      <p className={styles.message}>{error}</p>
      {onRetry && (
        <button className={styles.retryBtn} onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
