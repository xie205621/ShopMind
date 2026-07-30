/* ============================================================
   Chat Zustand Store — SAD.md §6.1
   Manages: messages[], isStreaming, session info.
   SSE token updates use subscribe pattern to avoid 60fps re-render.
   ============================================================ */

import { create } from 'zustand';
import type { ChatMessage, ChatSession } from '../../../shared/types/chat';

interface ChatState {
  // ── Session ──
  session: ChatSession | null;
  memoryId: string | null;

  // ── Messages ──
  messages: ChatMessage[];
  activeMessageId: string | null;  // The AI message currently being streamed

  // ── Connection ──
  isStreaming: boolean;
  connectionError: string | null;

  // ── Actions ──
  setSession: (session: ChatSession) => void;
  addUserMessage: (msg: ChatMessage) => void;
  createAIMessage: (id: string) => void;
  appendToken: (token: string) => void;
  setActiveIntent: (intent: ChatMessage['intent']) => void;
  addToolCall: (call: NonNullable<ChatMessage['toolCalls']>[number]) => void;
  updateToolResult: (callId: string, result: NonNullable<ChatMessage['toolCalls']>[number]['result']) => void;
  completeStream: (traceId?: string, latency?: number, ttft?: number, tokens?: { prompt: number; completion: number }) => void;
  setError: (error: string) => void;
  setStreaming: (streaming: boolean) => void;
  setConnectionError: (error: string | null) => void;
  clearMessages: () => void;
  resetSession: () => void;
}

const emptySession = {
  session: null as ChatSession | null,
  memoryId: null as string | null,
  messages: [] as ChatMessage[],
  activeMessageId: null as string | null,
  isStreaming: false,
  connectionError: null as string | null,
};

export const useChatStore = create<ChatState>((set, get) => ({
  ...emptySession,

  setSession: (session) =>
    set({ session, memoryId: session.memoryId }),

  addUserMessage: (msg) =>
    set((s) => ({ messages: [...s.messages, msg] })),

  createAIMessage: (id) => {
    const aiMsg: ChatMessage = {
      id,
      role: 'ai',
      content: '',
      timestamp: Date.now(),
      isStreaming: true,
    };
    set((s) => ({
      messages: [...s.messages, aiMsg],
      activeMessageId: id,
      isStreaming: true,
      connectionError: null,
    }));
  },

  appendToken: (token) => {
    // Direct mutation via set — Zustand batches with React 18
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0) {
        msgs[idx] = { ...msgs[idx], content: msgs[idx].content + token };
      }
      return { messages: msgs };
    });
  },

  setActiveIntent: (intent) => {
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0) {
        msgs[idx] = { ...msgs[idx], intent };
      }
      return { messages: msgs };
    });
  },

  addToolCall: (call) => {
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0) {
        const existing = msgs[idx].toolCalls ?? [];
        msgs[idx] = { ...msgs[idx], toolCalls: [...existing, call] };
      }
      return { messages: msgs };
    });
  },

  updateToolResult: (callId, result) => {
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0 && msgs[idx].toolCalls) {
        const updated = msgs[idx].toolCalls!.map((tc) =>
          tc.callId === callId ? { ...tc, result } : tc,
        );
        msgs[idx] = { ...msgs[idx], toolCalls: updated };
      }
      return { messages: msgs };
    });
  },

  completeStream: (traceId, latency, ttft, tokens) => {
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0) {
        msgs[idx] = {
          ...msgs[idx],
          isStreaming: false,
          traceId: traceId ?? msgs[idx].traceId,
          latency: latency ?? msgs[idx].latency,
          ttft: ttft ?? msgs[idx].ttft,
          tokens: tokens ?? msgs[idx].tokens,
        };
      }
      return {
        messages: msgs,
        activeMessageId: null,
        isStreaming: false,
      };
    });
  },

  setError: (error) => {
    set((s) => {
      const msgs = [...s.messages];
      const idx = msgs.findIndex((m) => m.id === s.activeMessageId);
      if (idx >= 0) {
        msgs[idx] = { ...msgs[idx], isStreaming: false, error };
      }
      return {
        messages: msgs,
        activeMessageId: null,
        isStreaming: false,
        connectionError: error,
      };
    });
  },

  setStreaming: (streaming) => set({ isStreaming: streaming }),
  setConnectionError: (error) => set({ connectionError: error }),
  clearMessages: () => set({ messages: [], activeMessageId: null }),
  resetSession: () => set(emptySession),
}));
