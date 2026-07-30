/* ============================================================
   useSSEChat — SAD.md §4.1 Chat Flow, §5.2 SSE
   Core hook: manages SSE stream lifecycle, dispatches events to chatStore.

   Phase 1: Mock SSE mode (no backend needed).
   Phase 2: switch to real POST /api/chat + GET /api/chat/{id}/stream.
   ============================================================ */

import { useCallback, useRef } from 'react';
import { useChatStore } from '../store/chatStore';
import { createMockSSEStream } from '../../../infrastructure/api/sseClient';
import type { ChatMessage, SSEEvent } from '../../../shared/types/chat';

let _msgCounter = 0;
function nextMsgId(): string {
  return `msg_${Date.now()}_${++_msgCounter}`;
}

export function useSSEChat() {
  const abortRef = useRef<AbortController | null>(null);
  const store = useChatStore();

  /** Handle a single SSE event — dispatches to the correct store action */
  const handleEvent = useCallback(
    (event: SSEEvent) => {
      switch (event.type) {
        case 'token':
          store.appendToken(event.content);
          break;
        case 'intent':
          store.setActiveIntent({
            category: event.category,
            requiresKnowledge: event.requiresKnowledge,
            requiresTools: event.requiresTools,
            confidence: event.confidence,
          });
          break;
        case 'tool_call':
          store.addToolCall({
            callId: event.callId,
            toolName: event.toolName,
            args: event.args,
          });
          break;
        case 'tool_result':
          store.updateToolResult(event.callId, {
            success: event.success,
            output: event.output,
            latencyMs: event.latencyMs,
          });
          break;
        case 'trace_ref':
          // Store traceId on the active message (happens via completeStream)
          break;
        case 'done':
          store.completeStream(
            undefined,
            event.stats.totalMs,
            event.stats.ttftMs,
            event.stats.tokens,
          );
          break;
        case 'error':
          store.setError(event.message);
          break;
      }
    },
    [store],
  );

  /** Send a user message and start an SSE stream */
  const sendMessage = useCallback(
    (query: string) => {
      if (!query.trim() || store.isStreaming) return;

      // Abort any existing stream
      if (abortRef.current) {
        abortRef.current.abort();
      }

      // Add user message
      const userMsg: ChatMessage = {
        id: nextMsgId(),
        role: 'user',
        content: query.trim(),
        timestamp: Date.now(),
      };
      store.addUserMessage(userMsg);

      // Create AI message placeholder
      const aiMsgId = nextMsgId();
      store.createAIMessage(aiMsgId);

      // Start mock SSE stream (Phase 1)
      const { abortController } = createMockSSEStream(
        query,
        handleEvent,
        () => {
          // Stream complete — nothing extra needed (done event handles it)
        },
      );
      abortRef.current = abortController;
    },
    [store, handleEvent],
  );

  /** Abort the current stream */
  const abort = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
      store.setStreaming(false);
    }
  }, [store]);

  /** Clear chat history and reset session */
  const clear = useCallback(() => {
    abort();
    store.clearMessages();
  }, [abort, store]);

  return {
    messages: store.messages,
    isStreaming: store.isStreaming,
    connectionError: store.connectionError,
    sendMessage,
    abort,
    clear,
  };
}
