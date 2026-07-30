/* IntentBadge — SAD.md §2.1 */

import { memo } from 'react';
import type { IntentResult } from '../../../shared/types/chat';
import styles from './IntentBadge.module.css';

const INTENT_LABELS: Record<string, string> = {
  return_policy: 'Return Policy',
  order_query: 'Order Query',
  product_info: 'Product Info',
  general_chat: 'General Chat',
  refund: 'Refund',
  logistics: 'Logistics',
};

interface IntentBadgeProps {
  intent: IntentResult;
}

export const IntentBadge = memo(function IntentBadge({ intent }: IntentBadgeProps) {
  const label = INTENT_LABELS[intent.category] ?? intent.category;

  return (
    <div className={styles.badge} title={`Confidence: ${(intent.confidence * 100).toFixed(0)}%`}>
      <span className={styles.dot} />
      {label}
    </div>
  );
});
