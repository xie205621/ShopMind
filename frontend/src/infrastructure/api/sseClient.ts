/* ============================================================
   SSE Client — SAD.md §5.2, §4.1
   fetch-based SSE reader (works in all modern browsers):
   - supports POST with JSON body
   - AbortController integration
   - typed event parsing
   ============================================================ */

import type { SSEEvent } from '../../shared/types/chat';

export type SSEEventCallback = (event: SSEEvent) => void;
export type SSEErrorCallback = (error: Error) => void;
export type SSEDoneCallback = () => void;

/**
 * Create a fetch-based SSE reader.
 * Uses ReadableStream to parse SSE events line-by-line.
 *
 * Why not EventSource?
 * - EventSource doesn't support POST
 * - EventSource doesn't support custom headers
 * - EventSource auto-reconnects without control
 */
export function createSSEReader(
  onEvent: SSEEventCallback,
  onError: SSEErrorCallback,
  onDone: SSEDoneCallback,
): { abortController: AbortController; connect: (url: string, body?: unknown) => Promise<void> } {
  const abortController = new AbortController();

  async function connect(url: string, body?: unknown): Promise<void> {
    try {
      const isPost = body !== undefined;
      const response = await fetch(url, {
        method: isPost ? 'POST' : 'GET',
        signal: abortController.signal,
        headers: isPost
          ? { Accept: 'text/event-stream', 'Content-Type': 'application/json' }
          : { Accept: 'text/event-stream' },
        body: isPost ? JSON.stringify(body) : undefined,
      });

      if (!response.ok) {
        throw new Error(`SSE connection failed: HTTP ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('No readable stream available');
      }

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          onDone();
          break;
        }

        buffer += decoder.decode(value, { stream: true });

        // Parse complete SSE lines from buffer
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? ''; // Last incomplete line stays in buffer

        for (const rawLine of lines) {
          const line = rawLine.trim();
          if (!line.startsWith('data:')) continue;

          const jsonStr = line.slice(5).trim();
          if (!jsonStr) continue;

          try {
            const event = JSON.parse(jsonStr) as SSEEvent;
            onEvent(event);
          } catch {
            // Non-JSON data line — ignore
          }
        }
      }
    } catch (err) {
      if (abortController.signal.aborted) {
        return; // User-initiated abort, not an error
      }
      onError(err instanceof Error ? err : new Error(String(err)));
    }
  }

  return { abortController, connect };
}
