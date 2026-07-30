/* ============================================================
   ChatPage — SAD.md §2.1, FRONTEND_UI_BLUEPRINT.md §3.2

   Layout (top → bottom):
     ChatHeader (48px)
     Messages area (flex-1, scrollable)
       └── Empty state OR message list
     PromptInput (bottom-fixed)

   Data flow:
     useSSEChat() → chatStore → MessageBubble[]

   Interaction:
     User types → PromptInput.onSend → useSSEChat.sendMessage()
       → Mock SSE stream → token-by-token → StreamingRenderer
   ============================================================ */

import { memo, useRef, useEffect, useCallback } from 'react';
import { useSSEChat } from '../features/chat/hooks/useSSEChat';
import { MessageBubble } from '../features/chat/components/MessageBubble';
import { PromptInput } from '../features/chat/components/PromptInput';
import styles from './ChatPage.module.css';

const SUGGESTIONS = [
  { label: '退货政策', query: '这条裤子的退货政策是什么？' },
  { label: '物流查询', query: '我的快递什么时候到？' },
  { label: '面料信息', query: '这款裤子的面料是什么材质的？' },
  { label: '优惠券', query: '有什么优惠券可以用？' },
  { label: '订单状态', query: '帮我查一下订单 ORD2024001 的状态' },
  { label: '修改地址', query: '我想修改收货地址' },
];

const ChatPage = memo(function ChatPage() {
  const { messages, isStreaming, connectionError, sendMessage, clear } = useSSEChat();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleTraceClick = useCallback((traceId: string) => {
    window.open(`/trace/${traceId}`, '_blank');
  }, []);

  const isEmpty = messages.length === 0;

  return (
    <div className={styles.page}>
      {/* ── Header ── */}
      <div className={styles.header}>
        <span className={styles.headerTitle}>ShopMind</span>
        <div className={styles.headerRight}>
          {!isEmpty && (
            <button className={styles.clearBtn} onClick={clear}>
              Clear
            </button>
          )}
        </div>
      </div>

      {/* ── Error banner ── */}
      {connectionError && (
        <div className={styles.errorBanner}>
          Connection error: {connectionError}
        </div>
      )}

      {/* ── Messages / Empty state ── */}
      {isEmpty ? (
        <div className={styles.emptyState}>
          <div className={styles.heroIcon}>🧠</div>
          <h1 className={styles.heroTitle}>ShopMind Enterprise</h1>
          <p className={styles.heroSubtitle}>Trustworthy AI Agent Platform</p>

          <div className={styles.suggestions}>
            {SUGGESTIONS.map((s) => (
              <button
                key={s.label}
                className={styles.chip}
                onClick={() => sendMessage(s.query)}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className={styles.messages} ref={messagesContainerRef}>
          {messages.map((msg) => (
            <MessageBubble
              key={msg.id}
              message={msg}
              isStreaming={msg.id === messages[messages.length - 1]?.id && isStreaming}
              onTraceClick={handleTraceClick}
            />
          ))}
          <div ref={messagesEndRef} />
        </div>
      )}

      {/* ── Input ── */}
      <PromptInput
        disabled={isStreaming}
        onSend={sendMessage}
        placeholder={isEmpty ? 'Ask about orders, returns, products...' : 'Type your question...'}
      />
    </div>
  );
});

export default ChatPage;
