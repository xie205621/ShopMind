/* ============================================================
   MessageBubble — SAD.md §2.1, FRONTEND_UI_BLUEPRINT.md §3.2
   Props contract: message, isStreaming, onTraceClick.
   User bubble: right-aligned, accent-subtle bg, max-w 70%,右上直角.
   AI bubble:   left-aligned, bg-surface, max-w 80%,左上直角.
   ============================================================ */

import { memo } from 'react';
import type { ChatMessage } from '../../../shared/types/chat';
import { StreamingRenderer } from './StreamingRenderer';
import { ToolCallCard } from './ToolCallCard';
import { IntentBadge } from './IntentBadge';
import styles from './MessageBubble.module.css';

interface MessageBubbleProps {
  message: ChatMessage;
  isStreaming: boolean;
  onTraceClick?: (traceId: string) => void;
}

export const MessageBubble = memo(function MessageBubble({
  message,
  isStreaming,
  onTraceClick,
}: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const isStreamingMsg = isStreaming && message.role === 'ai';

  return (
    <div className={`${styles.row} ${isUser ? styles.userRow : styles.aiRow}`}>
      {/* Avatar placeholder — SAD: w=32, radius-full, bg-subtle */}
      <div className={`${styles.avatar} ${isUser ? styles.userAvatar : styles.aiAvatar}`}>
        {isUser ? 'U' : 'AI'}
      </div>

      <div className={`${styles.bubble} ${isUser ? styles.userBubble : styles.aiBubble}`}>
        {/* Intent badge — only for AI messages */}
        {message.intent && (
          <IntentBadge intent={message.intent} />
        )}

        {/* Tool calls — only for AI messages */}
        {message.toolCalls && message.toolCalls.length > 0 && (
          <div className={styles.tools}>
            {message.toolCalls.map((tc) => (
              <ToolCallCard key={tc.callId} tool={tc} defaultExpanded={message.toolCalls!.length === 1} />
            ))}
          </div>
        )}

        {/* Message content — streaming or markdown */}
        <div className={styles.content}>
          {isUser ? (
            <span>{message.content}</span>
          ) : (
            <StreamingRenderer
              text={message.content}
              isComplete={!isStreamingMsg && !message.isStreaming}
            />
          )}
        </div>

        {/* Error display */}
        {message.error && (
          <div className={styles.error}>
            Error: {message.error}
          </div>
        )}

        {/* Footer: latency + trace link */}
        {!isUser && !message.isStreaming && message.latency && (
          <div className={styles.footer}>
            <span className={styles.latency}>
              {message.latency}ms
              {message.tokens && ` · ${message.tokens.completion} tokens`}
            </span>
            {message.traceId && onTraceClick && (
              <button
                className={styles.traceLink}
                onClick={() => onTraceClick(message.traceId!)}
              >
                Trace
              </button>
            )}
          </div>
        )}

        {/* Streaming indicator — pulsing bar */}
        {isStreamingMsg && (
          <div className={styles.streamingIndicator} />
        )}
      </div>
    </div>
  );
});
