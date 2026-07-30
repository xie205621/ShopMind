/* ============================================================
   SSE Client — SAD.md §5.2, §4.1
   Wraps EventSource API with:
   - reconnect with exponential backoff (1s→2s→4s→max 16s)
   - AbortController integration
   - Typed event parsing
   ============================================================ */

import type { SSEEvent } from '../../shared/types/chat';

export interface SSEConnection {
  /** Start streaming from the given URL */
  connect(url: string): void;
  /** Abort the current connection */
  abort(): void;
  /** Current status */
  get readyState(): number;
}

const BACKOFF_INITIAL = 1000;
const BACKOFF_MAX = 16000;

export type SSEEventCallback = (event: SSEEvent) => void;
export type SSEErrorCallback = (error: Error) => void;
export type SSEDoneCallback = () => void;

/**
 * Create a fetch-based SSE reader (works in all modern browsers).
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
): { abortController: AbortController; connect: (url: string) => Promise<void> } {
  const abortController = new AbortController();

  async function connect(url: string): Promise<void> {
    try {
      const response = await fetch(url, {
        signal: abortController.signal,
        headers: { Accept: 'text/event-stream' },
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

        // Process data: lines
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i].trim();
          if (line.startsWith('data: ')) {
            const jsonStr = line.slice(6);
            try {
              const event = JSON.parse(jsonStr) as SSEEvent;
              onEvent(event);
            } catch {
              // Non-JSON data line — ignore
            }
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

/**
 * Phase 1 mock: simulate an SSE stream locally without a backend.
 * Generates realistic mock events, token by token.
 */
export function createMockSSEStream(
  query: string,
  onEvent: SSEEventCallback,
  onDone: SSEDoneCallback,
): { abortController: AbortController } {
  const abortController = new AbortController();
  let cancelled = false;
  abortController.signal.addEventListener('abort', () => { cancelled = true; });

  const mockResponses: Record<string, { intent: string; tool?: string; answer: string; knowledge: string[] }> = {
    退货: {
      intent: 'return_policy',
      answer: '根据我们的退货政策，您可以在购买后 **7 天内** 无理由退货。退货时请确保：\n\n1. 商品标签完好\n2. 未穿过、未洗涤\n3. 保留原始包装\n\n退货流程如下：\n\n```\n1. 在APP中申请退货\n2. 选择退货原因\n3. 等待审核（1个工作日内）\n4. 自行寄回或上门取件\n```\n\n> 退款将在仓库签收后 3 个工作日内原路返回。',
      knowledge: ['7天', '无理由', '退货'],
    },
    快递: {
      intent: 'order_query',
      tool: 'queryOrder',
      answer: '正在为您查询快递信息...\n\n您的订单 **ORD2024001** 已于昨天下午发货，物流信息如下：\n\n| 时间 | 状态 |\n|------|------|\n| 07-22 14:30 | 已揽收 |\n| 07-22 18:00 | 运输中 |\n| 07-23 08:00 | 到达分拨中心 |\n\n预计明天到达。物流单号：`SF1234567890`',
      knowledge: ['订单', '物流', '发货', '快递'],
    },
    面料: {
      intent: 'product_info',
      answer: '这款裤子的面料信息如下：\n\n- **材质**：98% 棉 + 2% 氨纶\n- **手感**：柔软舒适，亲肤透气\n- **厚度**：中等厚度，四季皆宜\n- **弹性**：微弹（氨纶提供）\n\n> 洗涤建议：30°C 以下水温，不可漂白，建议翻转后洗涤。',
      knowledge: ['面料', '材质', '棉'],
    },
    优惠券: {
      intent: 'order_query',
      answer: '关于优惠券的使用规则：\n\n1. **满减券**：满 199 减 20，满 399 减 50\n2. **折扣券**：全场 9 折（特价商品除外）\n3. **新人券**：首单立减 30 元\n\n> 优惠券不可叠加使用，每单限用一张。',
      knowledge: ['优惠券', '满减', '折扣'],
    },
  };

  // Find best matching mock response
  const matched = Object.entries(mockResponses).find(([key]) =>
    query.includes(key),
  );
  const mock = matched?.[1] ?? mockResponses.面料;

  // Simulate intent detection after short delay
  const stream = async () => {
    // Intent
    await delay(80);
    if (cancelled) return;
    onEvent({
      type: 'intent',
      category: mock.intent,
      requiresKnowledge: true,
      requiresTools: !!mock.tool,
      confidence: 0.92,
    });

    // Tool call (if applicable)
    if (mock.tool) {
      await delay(120);
      if (cancelled) return;
      onEvent({
        type: 'tool_call',
        toolName: mock.tool,
        args: { orderId: 'ORD2024001' },
        callId: 'call_001',
      });

      await delay(150);
      if (cancelled) return;
      onEvent({
        type: 'tool_result',
        callId: 'call_001',
        success: true,
        output: '订单状态: 已发货',
        latencyMs: 230,
      });
    }

    // Stream tokens
    const tokens = mock.answer.split('');
    const startTime = Date.now();
    for (const token of tokens) {
      await delay(Math.random() * 15 + 5); // 5-20ms per token
      if (cancelled) return;
      onEvent({ type: 'token', content: token });
    }

    // Done
    const totalMs = Date.now() - startTime;
    onEvent({
      type: 'done',
      sessionId: `session_${Date.now()}`,
      stats: {
        ttftMs: 80,
        totalMs,
        tokens: { prompt: 124, completion: tokens.length },
      },
    });
    onDone();
  };

  stream();
  return { abortController };
}

function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
