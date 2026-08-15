/* ============================================================
   useSSEChat — SAD.md §4.1 Chat Flow, §5.2 SSE
   Core hook: manages SSE stream lifecycle, dispatches events to chatStore.
   Connects to the real backend endpoint POST /api/chat (SSE).
   ============================================================ */

import { useCallback, useRef } from 'react';
import { useChatStore } from '../store/chatStore';
import { createSSEReader } from '../../../infrastructure/api/sseClient';
import type { ChatMessage, SSEEvent } from '../../../shared/types/chat';

let _msgCounter = 0;
function nextMsgId(): string {
  return `msg_${Date.now()}_${++_msgCounter}`;
}

export function useSSEChat() {
  const abortRef = useRef<AbortController | null>(null);
  const memoryIdRef = useRef<string>(`session_${Date.now()}`);
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

  /** Send a user message and start a real SSE stream */
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

      // Start real SSE stream (POST /api/chat)
      const { abortController, connect } = createSSEReader(
        handleEvent,
        (error) => store.setError(error.message),
        () => {
          // Stream EOF — the 'done' event already handles completion
        },
      );
      abortRef.current = abortController;

      void connect('/api/chat', {
        memoryId: memoryIdRef.current,
        query: query.trim(),
      });
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
