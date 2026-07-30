/* ============================================================
   PageLoader — SAD.md §8
   Full-page skeleton displayed during React.lazy() suspense.
   ============================================================ */

import styles from './PageLoader.module.css';

export function PageLoader() {
  return (
    <div className={styles.wrapper}>
      <div className={styles.spinner} />
      <p className={styles.text}>Loading...</p>
    </div>
  );
}
