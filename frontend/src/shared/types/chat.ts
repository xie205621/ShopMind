/* ============================================================
   Chat domain types — SAD.md §2.1, §5.1-5.2
   Must match backend SSE event schema exactly.
   ============================================================ */

// ── Chat Session ──
export interface ChatSession {
  memoryId: string;
  sessionId: string;
  createdAt: string;
}

// ── Chat Message ──
export interface ChatMessage {
  id: string;
  role: 'user' | 'ai' | 'system';
  content: string;
  timestamp: number;
  latency?: number;
  ttft?: number;
  tokens?: { prompt: number; completion: number };
  intent?: IntentResult;
  toolCalls?: ToolCall[];
  traceId?: string;
  error?: string;
  isStreaming?: boolean;
}

// ── Intent Result ──
export interface IntentResult {
  category: string;
  requiresKnowledge: boolean;
  requiresTools: boolean;
  confidence: number;
}

// ── Tool Call ──
export interface ToolCall {
  callId: string;
  toolName: string;
  args: Record<string, unknown>;
  result?: ToolCallResult;
}

export interface ToolCallResult {
  success: boolean;
  output: string;
  latencyMs: number;
}

// ── SSE Events (matching backend text/event-stream) ──
export type SSEEvent =
  | { type: 'token';       content: string }
  | { type: 'intent';      category: string; requiresKnowledge: boolean; requiresTools: boolean; confidence: number }
  | { type: 'tool_call';   toolName: string; args: Record<string, unknown>; callId: string }
  | { type: 'tool_result'; callId: string; success: boolean; output: string; latencyMs: number }
  | { type: 'trace_ref';   traceId: string }
  | { type: 'done';        sessionId: string; stats: { ttftMs: number; totalMs: number; tokens: { prompt: number; completion: number } } }
  | { type: 'error';       code: 'TIMEOUT' | 'SAFETY_BLOCKED' | 'LLM_ERROR' | 'NETWORK'; message: string }

// ── SSE Connection State ──
export type SSEConnectionStatus = 'idle' | 'connecting' | 'streaming' | 'complete' | 'error';

// ── SSE Stream State ──
export interface SSEStreamState {
  status: SSEConnectionStatus;
  activeMessageId: string | null;
  abortController: AbortController | null;
  retryCount: number;
  lastError: string | null;
}

// ── Chat Request ──
export interface ChatRequest {
  memoryId?: string;
  query: string;
}

// ── Suggestion Chip ──
export interface SuggestionChip {
  label: string;
  query: string;
}
