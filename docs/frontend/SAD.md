# ShopMind Frontend — Software Design Specification

**Document Version**: v2.0  
**Status**: 🟡 **DRAFT — Awaiting Final Review**  
**Target Audience**: AI Code Generators, Frontend Developers, Architecture Reviewers  
**Purpose**: 前端组件、数据流、交互、设计系统、开发约束的**唯一可执行规范**。所有 AI 生成的前端代码必须以本文档为准。

---

## 目录

1. [Architecture Overview](#1-architecture-overview)
2. [Component Specification](#2-component-specification)
3. [Design System](#3-design-system)
4. [UI Interaction Flows](#4-ui-interaction-flows)
5. [API Data Contracts](#5-api-data-contracts)
6. [State Management](#6-state-management)
7. [Error Boundary Specification](#7-error-boundary-specification)
8. [Loading Strategy](#8-loading-strategy)
9. [Performance Requirements](#9-performance-requirements)
10. [Theme Specification](#10-theme-specification)
11. [Frontend Development Rules](#11-frontend-development-rules)

---

## 1. Architecture Overview

### 1.1 Positioning

ShopMind 前端承载三种交互模式，分别对应 3 个后端引擎：

| 模式 | UX | 后端 |
|------|-----|------|
| **对话模式** | SSE 流式对话、工具调用卡片、意图标签 | Agent Orchestrator + MCP + Memory |
| **分析模式** | Benchmark 仪表盘、版本对比、实验图表 | Evaluation Engine |
| **调试模式** | 链路追踪时间轴、Span 详情面板 | Workflow & Observability |

### 1.2 Tech Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Framework | **React 18+** / TypeScript 5.x (strict) | 类型安全、流式渲染生态成熟 |
| Build | **Vite 5** | HMR <100ms, native ESM |
| State | **Zustand** `v4` | SSE subscribe 模式，3 个独立 Store |
| UI | **Ant Design 5** | 企业 Table/Form/Tree 开箱即用 |
| Charts | **ECharts 5** | 雷达图 + 饼图 + 柱状对比（学术级） |
| SSE | Custom `useSSE` (EventSource) | reconnect + backoff + abort |
| HTTP | **axios** `v1` | interceptor 统一错误处理 |
| Router | **React Router v6** | lazy() code splitting |
| CSS | **CSS Modules + Ant Design Token** | 组件隔离 + 全局变量 |
| Test | **Vitest + RTL** | Vite-native, fast |

### 1.3 Route Design

```
/                              → ChatPage            (默认首页)
/dashboard                     → DashboardPage       (评测仪表盘)
/dashboard/experiments/:id     → ExperimentDetailPage
/dashboard/comparison          → ComparisonPage       (A/B 对比)
/trace/:traceId                → TracePage            (链路追踪)
```

### 1.4 Directory Structure

```
frontend/src/
├── main.tsx
├── App.tsx                              # ErrorBoundary > Router > MainLayout
├── pages/                               # Route-level pages
│   ├── ChatPage.tsx
│   ├── DashboardPage.tsx
│   ├── ExperimentDetailPage.tsx
│   ├── ComparisonPage.tsx
│   └── TracePage.tsx
├── features/                            # Feature domains
│   ├── chat/          components/ hooks/ store/
│   ├── evaluation/    components/ hooks/ store/
│   └── observability/ components/ hooks/ store/
├── shared/                              # Cross-domain
│   ├── components/    Layout/ PageLoader/ ErrorFallback/ EmptyState/
│   ├── hooks/         useSSE/ useDebounce/
│   ├── types/         chat.ts/ evaluation.ts/ observability.ts/
│   └── utils/         sseParser/ chartAdapter/ format/
└── infrastructure/
    ├── api/           sseClient.ts/ httpClient.ts/ endpoints.ts/
    ├── config/        env.ts/
    └── errors/        AppError.ts/
```

---

## 2. Component Specification

每个组件的 Props / State / Events / Render Strategy 全部在此定义。

### 2.1 ChatPage 组件树

```
ChatPage
├── ChatHeader                — memoryId, workflow version
├── ChatHistory               — 消息列表 (virtual scroll)
│   └── MessageBubble[]       — 每条消息 (user | ai)
│       ├── IntentBadge        — 仅 AI 消息
│       ├── ToolInvocationCard — 仅 AI 消息 (conditional)
│       ├── StreamingRenderer  — 流式文本渲染 (仅最新 AI 消息)
│       └── CitationBadge      — 知识引用 (conditional)
├── PromptInput               — 输入框 + 发送按钮
└── ContextPanel              — 右侧滑出面板: Memory/RAG/Tool 上下文
```

#### MessageBubble

```typescript
// ── Props Contract ──
interface MessageBubbleProps {
  message: ChatMessage;
  isStreaming: boolean;       // 是否正在流式更新
  onTraceClick: (traceId: string) => void;  // 点击 Trace ID 跳转
}

// ── ChatMessage Contract ──
interface ChatMessage {
  id: string;
  role: 'user' | 'ai' | 'system';
  content: string;                         // markdown body
  timestamp: number;                       // Unix ms
  latency?: number;                        // 总延迟 ms (仅 AI)
  ttft?: number;                           // TTFT ms (仅 AI)
  tokens?: { prompt: number; completion: number };
  intent?: IntentResult;                   // 仅 AI
  toolCalls?: ToolCall[];                  // 仅 AI
  traceId?: string;                        // 仅 AI
  error?: string;                          // 错误消息
}

interface IntentResult {
  category: string;                        // "order_query" | "return_policy" | ...
  requiresKnowledge: boolean;
  requiresTools: boolean;
  confidence: number;                      // 0.0 ~ 1.0
}

interface ToolCall {
  toolName: string;
  args: Record<string, unknown>;
  result?: { success: boolean; output: string };
  latencyMs: number;
}
```

```typescript
// ── Render Strategy ──
// - content 中的 Markdown: 用 react-markdown + remark-gfm
// - 代码块: 使用 rehype-highlight (暗色主题)
// - 数学公式: remark-math + rehype-katex (按需加载)
// - 链接: 渲染为 citation badge
// - 流式时: 末尾追加闪烁光标 █ (CSS animation: blink 1s step-end infinite)
```

#### StreamingRenderer

```typescript
interface StreamingRendererProps {
  text: string;               // 当前已累积的完整文本
  isComplete: boolean;         // 流是否结束
}
// Behavior:
// - isComplete=false: 逐字追加, 末尾显示 █ 光标
// - isComplete=true:  移除光标, markdown 完整渲染
// - 每次 text 更新:   React.memo + shallow compare 避免重渲染
```

#### ToolInvocationCard

```typescript
interface ToolInvocationCardProps {
  tool: ToolCall;
  defaultExpanded?: boolean;  // 单 tool 默认展开, 多 tool 折叠
}

// States:
// - PENDING:    左侧 spinner + "调用中..."
// - SUCCESS:    左侧 ✓ + toolName + latency + 展开显示 result
// - FAILED:     左侧 ✗ + toolName + error 信息 (红色描边)
// - TIMEOUT:    左侧 ⏱ + "工具执行超时"
```

#### PromptInput

```typescript
interface PromptInputProps {
  disabled: boolean;           // SSE 进行中时禁用
  onSend: (text: string) => void;
  placeholder?: string;
}

// Behavior:
// - Enter: 发送 (disabled 时无效)
// - Shift+Enter: 换行
// - maxHeight: 160px, scrollable
// - 发送后: 立刻清空 + disable
```

### 2.2 DashboardPage 组件树

```
DashboardPage
├── PageHeader               — "Evaluation Dashboard" + [Compare] [Export] 按钮
├── MetricGrid               — 8 个 MetricCard (2 rows × 4 cols)
│   └── MetricCard[]
├── ChartsRow                — 2 个 ChartCard 并排
│   ├── ChartCard (MetricsRadarChart)
│   └── ChartCard (FailurePieChart)
├── ExperimentTable          — 所有历史实验列表
└── EmptyState               — 无实验数据时显示
```

#### MetricCard

```typescript
// ── Props Contract ──
interface MetricCardProps {
  title: string;              // "Intent Accuracy", "Recall@K", ...
  value: number | string;     // "82.5%", "0.775", "480ms"
  delta?: string;             // "+15pp vs v2.0" (optional)
  trend?: 'up' | 'down' | 'stable';
  statusColor?: 'success' | 'warning' | 'error' | 'default';
  tooltip?: string;           // 悬停解释
  unit?: string;              // "%" | "ms" | "" (score)
  icon?: ReactNode;
}

// States:
// - data:    显示 value + delta
// - loading: 显示 Skeleton (w=240, h=130)
// - error:   显示 "—" + 红色边框
```

#### ExperimentTable

```typescript
// ── Column Contract ──
interface ExperimentRecord {
  experimentId: string;       // "eval-002"
  workflowVersion: string;    // "v2.1"
  generatedAt: string;        // ISO 8601
  totalCases: number;
  passedCases: number;
  metrics: {
    intentAccuracy: number;    // 0.0 ~ 1.0
    avgRecallAtK: number;
    hallucinationRate: number;
    toolAccuracy: number;
    taskSuccessRate: number;
    avgTtftMs: number;
    p95LatencyMs: number;
    workflowCompletionRate: number;
  };
}

// Columns: ID | Version | Date | Passed (X/40) | Intent | Recall | Halluc | Tool | TTFT
// Hallucination cell: 0%→green, >5%→warning, >10%→error (色条背景)
```

#### FailedCaseTable

```typescript
interface FailedCaseRecord {
  testCaseId: string;         // "TC021"
  query: string;              // "我的快递什么时候到？"
  failureType: FailureReason; // "WRONG_INTENT" | "WRONG_TOOL" | ...
  expectedIntent?: string;
  actualIntent?: string;
  expectedTool?: string;
  actualTool?: string;
  promptVersion?: string;
  traceId?: string;
  answerSnippet: string;
  diagnostics: string;
}

// Columns: Case ID | Query | Failure Type | Expected Tool | Actual Tool | Trace
// 行为: 点击行 → 展开详情 (diagnostics + answerSnippet)
```

### 2.3 ComparisonPage 组件树

```
ComparisonPage
├── ComparisonBarChart       — v2.0 vs v2.1 8 维横向柱状图
├── MetricsRadarChart        — 双版本叠加雷达图
├── ComparisonTable          — 逐维度对比表格
└── OneLineSummaryCard       — 一句话摘要
```

#### ComparisonTable

```typescript
interface ComparisonRecord {
  dimension: string;          // "Intent Accuracy"
  baselineValue: number;      // 0.675
  currentValue: number;       // 0.825
  delta: number;              // +0.150
  deltaPercent: number;       // +22.2
  direction: 'up' | 'down';
  unit: 'rate' | 'score' | 'ms' | '$';
}
// Δ 正值 → green ↑
// Δ 负值 → red ↓   (Hallucination/TTFT/P95 的负值特殊处理: 这是好的, 用 green ↓)
```

### 2.4 TracePage 组件树

```
TracePage
├── TraceHeader             — traceId, memoryId, status, totalLatency
├── MetricsPanel            — 6 个微型指标: TTFT, Total, Tokens, Chunks, Tools, Steps
├── TraceTimeline           — 垂直时间轴
│   └── TraceSpanNode[]     — 每个 pipeline step
│       └── TraceSpanDetail — 展开后显示 input/output/confidence
└── EmptyState              — 无 trace 数据时
```

#### TraceSpanNode

```typescript
interface TraceSpan {
  stepName: string;          // "INTENT_ANALYSIS", "CONTEXT_HYDRATION", ...
  latencyMs: number;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  confidence: number;
  startTime: number;
  endTime: number;
}

// Render:
// 左侧: 圆形状态点 (SUCCESS=green, FAILED=red, RUNNING=blue+pulse)
//       + 竖线连接下一个节点
// 主体: stepName + latency (右对齐) + 点击展开 detail
//       detail 区: JetBrains Mono 11px 代码块显示 input/output JSON
```

---

## 3. Design System

<!-- Note: Visual wireframes and ASCII layouts are defined in docs/01_Architecture/FRONTEND_UI_BLUEPRINT.md
     This section defines the DESIGN TOKENS only — the machine-readable values. -->

### 3.1 Core Principle

```
✅ 允许                          ❌ 禁止
────────────────────────────────────────────────
暗色底板 (#0b0f17 附近)          渐变五颜六色 + 科技蓝
半透明卡片 (backdrop-blur)       发光边框 (glow border)
1px 灰色描边 (#1e2530)          纯黑 (#000) + 纯白 (#fff) 大面积
灰度色系 + 单一 blue accent     霓虹描边 + 彩虹渐变
```

### 3.2 Color Tokens

```css
:root {
  /* ── Backgrounds ── */
  --bg-root:          #090d14;   /* 页面最底层 */
  --bg-surface:       #111620;   /* 卡片/面板 */
  --bg-elevated:      #161c28;   /* Modal/Dropdown/Tooltip */
  --bg-input:         #0b1019;   /* 输入框 */

  /* ── Borders ── */
  --border-subtle:    #1a2232;   /* 卡片描边 */
  --border-default:   #243049;   /* 输入框/分割线 */
  --border-strong:    #334155;   /* Hover 态 */

  /* ── Text ── */
  --text-primary:     #e8ecf1;   /* 正文/标题 */
  --text-secondary:   #8896a7;   /* 辅助说明 */
  --text-tertiary:    #566477;   /* Placeholder/禁用 */
  --text-inverse:     #090d14;   /* 深色文字(白色底) */

  /* ── Accent ── */
  --accent:           #3b82f6;   /* 主强调色 (blue-500) */
  --accent-hover:     #2563eb;   /* 悬停 (blue-600) */
  --accent-subtle:    rgba(59,130,246,0.12); /* 浅底 Badge */

  /* ── Semantic ── */
  --success:          #22c55e;   /* green-500 */
  --warning:          #f59e0b;   /* amber-500 */
  --error:            #ef4444;   /* red-500 */
  --info:             #6366f1;   /* indigo-500 */

  /* ── Charts (固定色, 不参与主题切换) ── */
  --chart-blue:       #3b82f6;
  --chart-green:      #22c55e;
  --chart-red:        #ef4444;
  --chart-orange:     #f59e0b;
  --chart-purple:     #a855f7;
  --chart-cyan:       #06b6d4;
  --chart-gray:       #64748b;
}
```

### 3.3 Typography

```css
:root {
  --font-sans:  'Inter', -apple-system, sans-serif;
  --font-mono:  'JetBrains Mono', 'Fira Code', monospace;

  /* Scale (line-height / letter-spacing implicit via CSS) */
  --text-page-title:   28px / 600 / 36px;   /* font-size / weight / line-height */
  --text-section-title: 18px / 600 / 26px;
  --text-card-title:    14px / 600 / 20px;
  --text-body:          14px / 400 / 22px;
  --text-body-sm:       12px / 400 / 18px;
  --text-caption:       11px / 400 / 16px;
  --text-metric-value:  32px / 700 / 38px;   /* MetricCard 主数值 */
  --text-metric-label:  11px / 500 / 14px / uppercase / 0.04em;
  --text-code:          13px / 400 / 20px;   /* JetBrains Mono */
  --text-code-sm:       11px / 400 / 16px;
}
```

### 3.4 Spacing Scale

```css
/* 统一间距: 基于 4px 网格 */
--space-0:  0;    --space-1:  4px;   --space-2:  8px;
--space-3:  12px; --space-4:  16px;  --space-5:  20px;
--space-6:  24px; --space-7:  32px;  --space-8:  40px;
--space-9:  48px; --space-10: 64px;
```

### 3.5 Border Radius

```css
--radius-sm:     6px;    /* Badge, Tag, small Button */
--radius-md:    10px;    /* Input, Select, Tooltip */
--radius-lg:    14px;    /* Card (默认) */
--radius-xl:    20px;    /* Modal, Drawer, MetricCard */
--radius-full: 9999px;   /* Avatar, 圆形 Button */
```

### 3.6 Shadow

```css
--shadow-card:       0 2px 8px rgba(0,0,0,0.30);     /* 默认卡片 */
--shadow-elevated:   0 8px 32px rgba(0,0,0,0.45);    /* Modal/Dropdown */
--shadow-glow:       0 0 20px rgba(59,130,246,0.12); /* 选中发光 */
--shadow-none:       0 0 0;                           /* 无阴影 */
```

### 3.7 Animation Tokens

```css
--transition-fast:   120ms ease-out;     /* Hover 颜色/边框 */
--transition-base:   200ms ease-in-out;  /* 卡片展开/Sidebar */
--transition-slow:   300ms ease-in-out;  /* Modal 进出/页面切换 */
--transition-chart:  500ms ease-out;     /* 图表首次渲染 */

/* ── Special Animations ── */
/* Streaming cursor:         blink 1s step-end infinite */
/* Skeleton shimmer:         1.5s ease-in-out infinite (bg-surface→elevated→surface) */
/* Metric count-up:          transition: all 600ms ease-out (数字跳动) */
/* Tool invocation card:     slideDown + fadeIn, 200ms ease-out */
/* Message bubble enter:     translateY(8px)→0, opacity 0→1, 200ms ease-out */
```

### 3.8 Iconography

```
Source: Lucide React (preferred) or @ant-design/icons
Stroke: 1.5px (统一)
Color:  inherit currentColor
Sizes:  16px (Badge/TableCell) | 18px (Button/CardTitle) | 20px (MetricCard/EmptyState) | 24px (Hero)
```

### 3.9 Component Dimension Rules

| Component | Width | Height | Radius | Padding | Shadow |
|-----------|-------|--------|--------|---------|--------|
| MetricCard | min 240px, flex-1 | ~130px | radius-xl | space-5 (20px) | shadow-card |
| ChartCard | flex-1 | ~400px | radius-xl | space-6 (24px) | shadow-card |
| MessageBubble (user) | max 70% | auto | 16px/16px/4px/16px | 12px 16px | none |
| MessageBubble (ai) | max 80% | auto | 16px/16px/16px/4px | 12px 16px | shadow-card |
| ToolCallCard | 100% | auto | radius-md | space-3 | none |
| IntentBadge | auto | 22px | radius-sm | 0 8px | none |
| Button (primary) | auto | 36px | radius-md | 0 16px | none |
| Input | auto | 40px | radius-md | 0 12px | none |
| Modal | max 600px | auto | radius-xl | space-6 | shadow-elevated |
| Sidebar | 56px | 100vh | 0 | — | border-r |
| Header | 100% | 48px | 0 | — | border-b |

### 3.10 Z-Index Scale

```css
--z-sidebar:   100;
--z-header:    200;
--z-dropdown:  300;
--z-modal:     400;
--z-tooltip:   500;
--z-toast:     600;
```

---

## 4. UI Interaction Flows

### 4.1 Chat Flow (核心链路)

```
User types message
  │
  ├─ [PromptInput.onSend(text)]
  │
  ├─ [chatStore.sendMessage(text)]
  │      │
  │      ├─ Step 1: POST /api/chat  →  { memoryId, sessionId }
  │      │
  │      ├─ Step 2: Create AbortController
  │      │
  │      ├─ Step 3: GET /api/chat/{memoryId}/stream (SSE)
  │      │      │
  │      │      ├─ event: token        → StreamingRenderer append text
  │      │      ├─ event: intent       → IntentBadge show
  │      │      ├─ event: tool_call    → ToolCallCard (PENDING)
  │      │      ├─ event: tool_result  → ToolCallCard (SUCCESS/FAILED)
  │      │      ├─ event: done         → mark complete, cursor hide
  │      │      ├─ event: error        → ErrorFallback in bubble
  │      │      └─ connection lost     → auto-reconnect 1s→2s→4s→max 16s
  │      │
  │      └─ Abort (user sends new msg) → AbortController.abort()
  │
  └─ [UI State Transitions]
       Idle → Sending (disable input) → Streaming (token-by-token) → Complete (enable input)
```

### 4.2 Dashboard Load Flow

```
User navigates to /dashboard
  │
  ├─ [DashboardPage mount]
  │      │
  │      ├─ Step 1: GET /api/evaluation/experiments
  │      │      ├─ loading:  MetricGrid → Skeleton (8 cards)
  │      │      ├─ success:  MetricGrid → render MetricCard[]
  │      │      │             ChartsRow → render Charts (progressive: radar first, pie second)
  │      │      │             ExperimentTable → render rows
  │      │      └─ error:    ErrorFallback + [Retry] button
  │      │
  │      └─ Step 2: GET /api/evaluation/experiments/latest/report
  │             (same loading/error logic)
  │
  └─ [Visibility: 所有内容一次性显示, 图表用 ECharts animation: transition-chart]
```

### 4.3 Run Benchmark Flow

```
User clicks [Run Benchmark] button
  │
  ├─ [Button → Loading spinner, disabled]
  │
  ├─ [Disable all "Run Benchmark" buttons (idempotency guard)]
  │
  ├─ [POST /api/evaluation/benchmark  →  { experimentId }]
  │      │
  │      └─ Polling: GET /api/evaluation/benchmark/{id}/status every 2s
  │             ├─ status: RUNNING   → ProgressBar (% completed)
  │             ├─ status: COMPLETED → Success Toast → refresh Dashboard
  │             └─ status: FAILED    → Error Toast + [Retry]
  │
  └─ [Toast: "Benchmark complete. 23/40 passed → View Report"]
```

### 4.4 A/B Comparison Load Flow

```
User navigates to /dashboard/comparison
  │
  ├─ [Select baseline & current from dropdowns (default: v2.0 vs v2.1)]
  │
  ├─ [Click [Compare] → Loading spinner]
  │
  ├─ [GET /api/evaluation/compare?baseline=v2.0&current=v2.1]
  │      │
  │      ├─ loading:  ComparisonBarChart → Skeleton
  │      ├─ success:  ComparisonBarChart (animate + count-up)
  │      │             ComparisonTable (render rows)
  │      │             OneLineSummaryCard (fade in)
  │      └─ error:    ErrorFallback
  │
  └─ [No data for a version → EmptyState "No experiment data for vX.X"]
```

### 4.5 Trace View Flow

```
User clicks Trace ID (from Chat message bubble or FailedCaseTable)
  │
  ├─ [navigate(/trace/{traceId})]
  │
  ├─ [TracePage mount]
  │      │
  │      ├─ GET /api/observability/traces/{traceId}
  │      │      ├─ loading:  TraceTimeline → Skeleton (5 placeholder nodes)
  │      │      ├─ success:  TraceHeader + MetricsPanel + TraceTimeline render
  │      │      │             (all spans collapsed by default, click to expand one)
  │      │      └─ error:    ErrorFallback
  │      │
  │      └─ [SSH connection for real-time updates (stretch)]
  │
  └─ [Click span node → TraceSpanDetail (Drawer from right)]
```

---

## 5. API Data Contracts

### 5.1 POST /api/chat

```typescript
// Request
interface ChatRequest {
  memoryId?: string;          // undefined = new session
  query: string;
}

// Response
interface ChatSessionResponse {
  memoryId: string;
  sessionId: string;
  createdAt: string;          // ISO 8601
}
```

### 5.2 GET /api/chat/{memoryId}/stream (SSE)

```typescript
// SSE Event Stream
type SSEEvent =
  | { type: 'token';       content: string }
  | { type: 'intent';      category: string; requiresKnowledge: boolean; requiresTools: boolean; confidence: number }
  | { type: 'tool_call';   toolName: string; args: Record<string,unknown>; callId: string }
  | { type: 'tool_result'; callId: string; success: boolean; output: string; latencyMs: number }
  | { type: 'trace_ref';   traceId: string }
  | { type: 'done';        sessionId: string; stats: { ttftMs:number; totalMs:number; tokens:{prompt:number;completion:number} } }
  | { type: 'error';       code: 'TIMEOUT'|'SAFETY_BLOCKED'|'LLM_ERROR'; message: string }
```

### 5.3 GET /api/evaluation/experiments

```json
{
  "experiments": [
    {
      "experimentId": "eval-002",
      "workflowVersion": "v2.1",
      "datasetVersion": "benchmark_v1",
      "generatedAt": "2026-07-23T21:52:09+08:00",
      "totalCases": 40,
      "passedCases": 23,
      "metrics": {
        "intentAccuracy": 0.825,
        "avgRecallAtK": 0.775,
        "hallucinationRate": 0.025,
        "toolAccuracy": 0.85,
        "taskSuccessRate": 0.575,
        "avgTtftMs": 480,
        "p95LatencyMs": 1210,
        "workflowCompletionRate": 0.975
      },
      "cost": {
        "totalPromptTokens": 0,
        "totalCompletionTokens": 825,
        "estimatedCostUsd": 0.0017,
        "pricingModel": "qwen-max"
      }
    }
  ]
}
```

### 5.4 GET /api/evaluation/experiments/:id

```json
{
  "experimentId": "eval-002",
  "workflowVersion": "v2.1",
  "datasetVersion": "benchmark_v1",
  "generatedAt": "2026-07-23T21:52:09+08:00",
  "totalCases": 40,
  "passedCases": 23,
  "metrics": { /* same as above */ },
  "cost": { /* same as above */ },
  "failureDistribution": {
    "WRONG_INTENT": 0.10,
    "WRONG_TOOL": 0.075,
    "WRONG_PARAMETER": 0.05,
    "KNOWLEDGE_MISS": 0.10,
    "HALLUCINATION": 0.025,
    "SAFETY_BLOCKED": 0.025,
    "TIMEOUT": 0.05
  },
  "failedDetails": [
    {
      "testCaseId": "TC021",
      "query": "我的快递什么时候到？",
      "reason": "WRONG_INTENT",
      "actualResponse": "如果您需要退换商品...",
      "diagnostics": "意图识别错误"
    }
  ]
}
```

### 5.5 GET /api/evaluation/compare

```
Query: ?baseline=eval-001&current=eval-002
```
```json
{
  "baselineId": "eval-001",
  "baselineLabel": "v2.0",
  "currentId": "eval-002",
  "currentLabel": "v2.1",
  "dimensions": [
    { "name":"Intent Accuracy",   "baseline":0.675, "current":0.825, "delta":+0.150, "deltaPercent":+22.2, "unit":"rate"   },
    { "name":"Avg Recall@K",      "baseline":0.583, "current":0.775, "delta":+0.192, "deltaPercent":+32.9, "unit":"score"  },
    { "name":"Hallucination Rate","baseline":0.175, "current":0.025, "delta":-0.150, "deltaPercent":-85.7, "unit":"rate"   },
    { "name":"Tool Accuracy",     "baseline":0.600, "current":0.850, "delta":+0.250, "deltaPercent":+41.7, "unit":"rate"   },
    { "name":"Task Success Rate", "baseline":0.325, "current":0.575, "delta":+0.250, "deltaPercent":+76.9, "unit":"rate"   },
    { "name":"Avg TTFT",          "baseline":713,   "current":480,   "delta":-233,   "deltaPercent":-32.7, "unit":"ms"     },
    { "name":"P95 Latency",       "baseline":5510,  "current":1210,  "delta":-4300,  "deltaPercent":-78.0, "unit":"ms"     },
    { "name":"Cost",              "baseline":0.0054,"current":0.0017,"delta":-0.0037,"deltaPercent":-68.5, "unit":"$"      }
  ]
}
```

### 5.6 GET /api/observability/traces/:traceId

```json
{
  "traceId": "t_abc123def456",
  "memoryId": "eval_v2.1_TC007",
  "workflowVersion": "v2.1",
  "status": "SUCCESS",
  "startTime": 1784814720000,
  "endTime": 1784814722300,
  "metrics": {
    "ttftMs": 480,
    "totalLatencyMs": 2300,
    "promptTokens": 1240,
    "completionTokens": 210,
    "retrievedChunksCount": 3,
    "toolCallCount": 1
  },
  "spans": [
    {
      "stepName": "INTENT_ANALYSIS",
      "latencyMs": 15,
      "status": "SUCCESS",
      "input": { "query": "我的快递什么时候到？" },
      "output": { "category": "order_query", "requiresKnowledge": false, "requiresTools": true },
      "confidence": 0.92,
      "startTime": 1784814720000,
      "endTime": 1784814720015
    }
  ]
}
```

---

## 6. State Management

### 6.1 Store Architecture

使用 **Zustand v4**，三个独立 Store（禁止合并为一个全局 Store）：

```
chatStore              evaluationStore          traceStore
─────────────────     ───────────────────     ─────────────
- sessionId            - experiments[]          - trace
- memoryId             - currentReport          - spans[]
- messages[]           - comparison             - isLoading
- isStreaming          - isLoading              - error
- abortController      - error                  - loadTrace()
- sendMessage()        - loadList()             - clear()
- clearHistory()       - loadReport()
- abortStream()        - loadComparison()
```

### 6.2 Store Rules (强制)

```
✅ 必须                            ❌ 禁止
───────────────────────────────────────────────
Zustand create() with types      useState 存 messages / experiments / trace
每个 Store 独立文件               合并为一个 global store
immer middleware (可选)           props drilling 超过 2 层
subscribe() for SSE 高频更新      setState in render
selector 精确到字段               useSelector 返回整个 store
```

### 6.3 SSE State Update Strategy

```typescript
// chatStore 中用 subscribe 模式处理高频 token 事件:
// (不是每个 token 触发 React re-render, 而是 throttle 到 60fps)

const useChatStore = create<ChatStore>((set, get) => ({
  messages: [],
  appendToken: (msgId: string, token: string) => {
    set(state => {
      const msgs = [...state.messages];
      const idx = msgs.findIndex(m => m.id === msgId);
      if (idx >= 0) {
        msgs[idx] = { ...msgs[idx], content: msgs[idx].content + token };
      }
      return { messages: msgs };
    });
  },
  // ... other actions
}));

// StreamingRenderer 使用:
// const content = useChatStore(s => s.messages.find(m => m.id === activeMsgId)?.content ?? '');
```

---

## 7. Error Boundary Specification

### 7.1 Architecture

```
React ErrorBoundary (全局, 捕获渲染崩溃)
  ├── ErrorBoundary (axios response interceptor)
  │     ├── 401 → redirect /login
  │     ├── 429 → show Toast "Rate limited, retry in {Retry-After}s"
  │     ├── 5xx → show Toast "Server error. [Retry]"
  │     └── Network Error → show Toast "Network offline. Auto-reconnect..."
  │
  ├── SSE Error Handler (useSSE)
  │     ├── connection lost  → reconnect with backoff: 1s→2s→4s→max 16s
  │     ├── 429 on SSE       → wait Retry-After, reconnect
  │     ├── 401 on SSE       → stop reconnect, redirect /login
  │     └── fatal error      → show error in message bubble, not page crash
  │
  └── Component-level Error Boundary (每个页面独立)
        ChatPage:        错误 → 消息气泡内 ErrorFallback (不影响其他消息)
        DashboardPage:   错误 → 卡片级 ErrorFallback + [Retry] (不影响其他卡片)
        TracePage:       错误 → 全页 ErrorFallback + [Go Back]
```

### 7.2 Error State Hierarchy

| Level | Scope | Recovery |
|-------|-------|----------|
| **Global** | App crash | Show fallback UI + [Reload Page] |
| **Page** | Page-level error | Show ErrorFallback + [Retry] / [Go Back] |
| **Section** | Card/Chart error | Skeleton → ErrorBadge in card + [Retry this card] |
| **Item** | Single message/tool error | Inline error text,不影响其他消息 |

### 7.3 Idempotency Guards

```
- [Run Benchmark] button:
    onClick → set disabled=true, loading=true
    POST returns → if 200: poll, if 409 (duplicate): show Toast "Already running"
    on complete → set disabled=false

- [Send Message] button:
    isStreaming=true → disabled
    abort current stream → enable → send new

- [Compare] button:
    request in-flight → disabled + loading spinner
```

---

## 8. Loading Strategy

### 8.1 Per-Component Loading Behavior

| Component | Loading State | Implementation |
|-----------|--------------|----------------|
| **MetricCard** (×8) | Skeleton card (240×130) | Ant Design Skeleton + shimmer animation |
| **ChartCard** (×4) | Skeleton chart area (100%×320) | Skeleton.Input block |
| **ExperimentTable** | Skeleton rows (5 rows) | Skeleton active paragraph |
| **FailedCaseTable** | Skeleton rows (10 rows) | Skeleton active paragraph |
| **ComparisonBarChart** | Skeleton chart area + title | Skeleton block |
| **MessageBubble (streaming)** | Streaming text + █ cursor | No spinner; text appears token-by-token |
| **MessageBubble (loading answer)** | Pulsing bar at bottom | h=4px, w=40px, accent opacity 0.5, pulse 1.5s |
| **ToolCallCard (PENDING)** | Spinner + toolName | Ant Design Spin (small), 18px |
| **TraceTimeline** | 5 placeholder span nodes | Skeleton vertical timeline |
| **Page transitions** | No global spinner | CSS Module fade-in animation 200ms |

### 8.2 Progressive Rendering Priority

```
DashboardPage:
  1. MetricGrid (8 cards)        ← 首先渲染 (核心 KPI)
  2. ChartsRow (Radar + Pie)     ← 100ms delay (图表较重)
  3. ExperimentTable             ← 200ms delay (表格数据量大)

ChatPage:
  1. ChatHistory scroll to bottom
  2. StreamingRenderer (token by token via SSE)
  3. ToolCallCard (appears when SSE tool_call event arrives)
```

### 8.3 Virtual Scroll Threshold

```
MessageBubble list:    react-virtuoso, threshold > 50 messages
ExperimentTable:       Ant Design Table pagination, pageSize=20 (no virtual needed for < 100 rows)
FailedCaseTable:       Ant Design Table pagination, pageSize=20
TraceTimeline spans:   No virtual needed (< 20 spans per trace)
```

---

## 9. Performance Requirements

### 9.1 Core Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| **FCP** (First Contentful Paint) | < 1.0s | Lighthouse |
| **LCP** (Largest Contentful Paint) | < 2.5s | Lighthouse |
| **TTI** (Time to Interactive) | < 2.0s | Lighthouse |
| **TBT** (Total Blocking Time) | < 200ms | Lighthouse |
| **CLS** (Cumulative Layout Shift) | < 0.05 | Lighthouse |
| **FPS** (during streaming) | ≥ 60 | React Profiler |
| **Bundle Size** (initial) | < 300 KB (gzip) | `vite build --report` |

### 9.2 Optimization Rules (强制执行)

```
✅ 必须执行:

1. Route Lazy Loading:
   - 每个 Page:  React.lazy(() => import('./pages/ChatPage'))
   - Suspense:   fallback=<PageLoader /> (Skeleton)

2. Component Memoization:
   - MessageBubble:   React.memo + shallow compare
   - MetricCard:      React.memo
   - ChartCard:       React.memo (ECharts 实例很重)

3. ECharts Optimization:
   - animation: false (首次加载后)
   - 使用 useRef 持有 echarts instance, 避免反复 init/dispose
   - resize 使用 debounce 200ms

4. Bundle Splitting:
   - antd:     manualChunks
   - echarts:  manualChunks
   - react-markdown + rehype: manualChunks (仅 ChatPage 需要)

5. Image Loading:
   - figures/*.png: loading="lazy" decoding="async"
   - No placeholders needed (figures < 500KB total)

6. Font Loading:
   - Inter + JetBrains Mono: font-display: swap
   - Preload via <link rel="preload" as="font" crossorigin> in index.html
```

### 9.3 Bundle Size Budget

```
Chunk            Max Size (gzip)
─────────────────────────────────
vendor (react)       45 KB
vendor (antd)        120 KB (tree-shaken)
vendor (echarts)     200 KB
app                  60 KB
─────────────────────────────────
Total Initial        300 KB
```

---

## 10. Theme Specification

### 10.1 Supported Themes

```typescript
type ThemeMode = 'light' | 'dark' | 'system';

// Default: 'dark' (matches ShopMind visual identity)
// Detect: window.matchMedia('(prefers-color-scheme: dark)') for 'system' mode
// Storage: localStorage.getItem('shopmind-theme')
```

### 10.2 Light Theme Overrides

```css
[data-theme="light"] {
  --bg-root:          #f5f7fa;
  --bg-surface:       #ffffff;
  --bg-elevated:      #f0f2f5;
  --bg-input:         #f9fafb;

  --border-subtle:    #e5e7eb;
  --border-default:   #d1d5db;
  --border-strong:    #9ca3af;

  --text-primary:     #111827;
  --text-secondary:   #4b5563;
  --text-tertiary:    #9ca3af;
  --text-inverse:     #ffffff;

  --accent:           #2563eb;
  --accent-hover:     #1d4ed8;
  --accent-subtle:    rgba(37,99,235,0.08);

  --shadow-card:      0 1px 4px rgba(0,0,0,0.08);
  --shadow-elevated:  0 4px 16px rgba(0,0,0,0.12);
  --shadow-glow:      0 0 16px rgba(37,99,235,0.10);
}
```

### 10.3 Theme Toggle Behavior

```
位置: Header 右侧 (☀️/🌙 icon)
行为:
  - Click: 切换 dark ↔ light (写 localStorage)
  - System theme 变化: 仅当 mode='system' 时自动切换
  - 首次访问: 默认 'dark'
  - Transition: 200ms ease-in-out (CSS transition on :root variables)
```

---

## 11. Frontend Development Rules

### 11.1 Coding Standards (AI & Human)

```yaml
# ── 绝对禁止 ──
forbidden:
  - useState 或 useReducer 存储 messages / experiments / trace 数据
  - props drilling 超过 2 层 (Provider > Child > Grandchild)
  - any 类型 (TypeScript strict mode, no-explicit-any)
  - 内联 style 对象 (用 CSS Modules)
  - axios 在组件内直接调用 (必须通过 store action 或 custom hook)
  - console.log 提交 (用 logger util)

# ── 必须执行 ──
required:
  - TypeScript strict: true
  - Zustand for all global state
  - Custom hooks for all data fetching logic
  - React.lazy() for all page-level imports
  - ErrorBoundary wrapping each page
  - React.memo() for all list item components
  - CSS Modules (no CSS-in-JS, no Tailwind)
  - Design tokens via CSS variables (--accent, --bg-surface, etc.)
  - All API calls through infrastructure/api/ layer
```

### 11.2 File Naming Conventions

```
Pages:          PascalCase + "Page" suffix       ChatPage.tsx
Components:     PascalCase                        MessageBubble.tsx
Hooks:          camelCase + "use" prefix          useSSEChat.ts
Stores:         camelCase + "Store" suffix        chatStore.ts
Types:          camelCase + domain name           chat.ts
Utils:          camelCase                         sseParser.ts
CSS Modules:    PascalCase + .module.css          MessageBubble.module.css
```

### 11.3 Component File Structure

```typescript
// Each component file follows this order:
// 1. imports (React → third-party → shared → types)
// 2. type/interface definitions
// 3. component function
// 4. memo() wrapper
// 5. CSS Module import (last line)
```

### 11.4 Git Commit Convention

```
feat(ChatPage): add SSE streaming with backoff reconnect
fix(MetricCard): correct delta calculation for hallucination rate
style(DesignSystem): update shadow tokens per FRONTEND_UI_BLUEPRINT
refactor(chatStore): extract SSE logic to useSSE hook
docs(SAD): add Component Specification for TraceTimeline
```

---

## Appendix A: References

| Document | Path | Purpose |
|----------|------|---------|
| **FRONTEND_UI_BLUEPRINT** | `docs/01_Architecture/FRONTEND_UI_BLUEPRINT.md` | ASCII Wireframes, Component Visual Specs |
| **Backend SAD** | `docs/01_Architecture/Software_Architecture.md` | Backend package layout, engine specs |
| **Master Blueprint** | `docs/00_Product/MASTER_BLUEPRINT.md` | Product vision, RQs, roadmap |
| **Evaluation Engine Spec** | `docs/02_Specifications/6_Evaluation_Engine.md` | Evaluation domain model reference |

## Appendix B: Quick Start for AI Code Generators

```
When generating frontend code for ShopMind, follow this checklist:

□ 1. Read FRONTEND_UI_BLUEPRINT.md §3-§6 for page wireframe
□ 2. Read this SAD.md §2 for component props contract
□ 3. Read this SAD.md §3 for Design Token values
□ 4. Read this SAD.md §4 for user interaction flow
□ 5. Read this SAD.md §5 for API request/response schema
□ 6. Read this SAD.md §11 for coding rules (Zustand, no useState, strict TS)
□ 7. Use --accent not #1677ff; use --bg-surface not #ffffff
□ 8. Every page-level component must be wrapped in React.lazy()
□ 9. Every list-item component must be wrapped in React.memo()
□ 10. No props drilling > 2 levels
```

---

**本文档是 ShopMind 前端的唯一可执行规范。所有偏离本文档的实现均视为 Bug。**
