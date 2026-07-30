# ShopMind — Frontend UI Blueprint

**Document Version**: v1.0  
**Status**: 🔒 **FROZEN — All AI-generated UI must reference this document**  
**Target Audience**: AI Code Generators, Frontend Developers  
**Purpose**: 消除 AI 在 UI 实现中的自由发挥。每个组件的高、色、圆角、间距、字体、动画全部固定。

---

## 1. Visual Style Guide（视觉风格指令）

### 1.1 参考对象（告诉 AI 长什么样）

```
参考产品        → 提取特征
─────────────────────────────────────────────────
Linear          → 左侧极窄 Icon Sidebar，暗色底板，高密度信息
Cursor          → Chat 居中，消息气泡左对齐，输入框悬浮
Vercel          → Dashboard 卡片网格，极简边框，Geist 字体感
OpenAI          → Streaming text 逐字淡入，无闪烁
Anthropic       → 引用块样式，知识片段卡片
Supabase        → 表格密度，失败用例列表
GitHub Models   → 灰色 Token Badge 样式
```

### 1.2 全局感官约束

```
✅ 允许                         ❌ 禁止
─────────────────────────────────────────────────
暗色底板 (#0b0f17 附近)         渐变五颜六色
半透明玻璃卡片 (backdrop-blur)  科幻发光边框
极细 1px 灰色描边 (#1e2530)     科技蓝 (#00d4ff, #0ea5e9)
等宽字体用于代码/数据           国产大屏炫彩风格
微妙阴影 (0 8px 32px rgba)     纯黑 (#000000)
灰度色 + 单一强调色             纯白卡片
16px 圆角卡片                   渐变按钮
                                 霓虹描边
```

---

## 2. Design Tokens（设计令牌 —— 一切颜色的唯一来源）

### 2.1 色板 (Color Palette)

```
名称              Hex          用途
─────────────────────────────────────────────────
bg-root          #090d14      页面最底层背景（最深）
bg-surface       #111620      卡片/面板背景
bg-elevated      #161c28      悬浮层（Dropdown, Modal, Tooltip）
bg-input         #0b1019      输入框背景

border-subtle    #1a2232      卡片的极细边框
border-default   #243049      输入框/分割线边框
border-strong    #334155      悬停态边框

text-primary     #e8ecf1      正文标题
text-secondary   #8896a7      辅助文字
text-tertiary    #566477      占位符/禁用态
text-inverse     #090d14      在强调色上的文字（白底黑字时用）

accent           #3b82f6      主强调色（按钮、链接、选中态）
accent-hover     #2563eb      悬停加深
accent-subtle    rgba(59,130,246,0.12)  强调色浅底（Badge, Tag 背景）

success          #22c55e      成功/通过
warning          #f59e0b      警告/部分通过
error            #ef4444      失败/错误/幻觉
info             #6366f1      Intent Badge 等

chart-blue       #3b82f6      ← 图表固定色（不是 UI 色）
chart-green      #22c55e
chart-red        #ef4444
chart-orange     #f59e0b
chart-purple     #a855f7
chart-cyan       #06b6d4
chart-gray       #64748b
```

### 2.2 字体 (Typography)

```
用途            字体                           大小    字重      行高    字间距
─────────────────────────────────────────────────────────────────────────
Page Title      Inter                        28px    600       36px    -0.02em
Section Title   Inter                        18px    600       26px    -0.01em
Card Title      Inter                        14px    600       20px    0
Body            Inter                        14px    400       22px    0
Body Small      Inter                        12px    400       18px    0
Caption         Inter                        11px    400       16px    0

Code/Data       JetBrains Mono               13px    400       20px    0
Code Small      JetBrains Mono               11px    400       16px    0

Metric Value    Inter                        32px    700       38px    -0.02em
Metric Label    Inter                        11px    500       14px    0.04em (uppercase)
```

### 2.3 间距 (Spacing Scale)

```
Token     px     用途
──────────────────────────────────────
space-0    0     紧凑元素（Icon+Label）
space-1    4     Badge 内边距
space-2    8     Card 内部元素间隙
space-3   12     Card padding (compact)
space-4   16     Card padding (default), Section 内部间隙
space-5   20     Card 之间的间隙
space-6   24     Section 之间的间隙
space-7   32     大区块分隔
space-8   40     Page padding horizontal
space-9   48     Hero 区上下
space-10  64     极少使用
```

### 2.4 圆角 (Border Radius)

```
Token         px     用途
──────────────────────────────────────
radius-sm      6     Badge, Tag, small Button
radius-md     10     Input, Select, Tooltip
radius-lg     14     Card (默认), Dropdown
radius-xl     20     Modal, Drawer, 大卡片（Dashboard Metric Card）
radius-full  9999   Avatar, 圆形 Button
```

### 2.5 阴影 (Shadow)

```
Token              Value                                   用途
─────────────────────────────────────────────────────────────────
shadow-card        0 2px 8px rgba(0,0,0,0.30)             默认卡片
shadow-elevated    0 8px 32px rgba(0,0,0,0.45)            悬浮层（Modal, Dropdown）
shadow-glow        0 0 20px rgba(59,130,246,0.12)         选中卡片发光（Dashboard 选中实验）
shadow-none        0 0 0                                  无阴影（输入框、分割线）
```

### 2.6 动画 (Animation)

```
Token              Duration   Easing          用途
──────────────────────────────────────────────────────────
transition-fast    120ms      ease-out        Hover 颜色变化、Badge 出现
transition-base    200ms      ease-in-out     卡片展开、Sidebar 收起
transition-slow    300ms      ease-in-out     Modal 进出、页面切换
transition-chart   500ms      ease-out        图表首次渲染动画

Streaming Cursor: 1s step-end infinite blink (█)
Skeleton: 1.5s ease-in-out infinite shimmer (bg-surface → bg-elevated → bg-surface)
```

### 2.7 图标 (Iconography)

```
来源: Lucide React (推荐) 或 @ant-design/icons

尺寸:
  16px  → Badge 内、Table Cell 内、Tooltip 内
  18px  → Button 内、Card Title 旁、Sidebar 导航
  20px  → Metric Card 右上角、空状态
  24px  → Hero 区

描边: 统一 stroke-width: 1.5
颜色: 继承父级文字颜色 (currentColor)
```

---

## 3. 全局布局骨架（Layout Wireframe）

### 3.1 主布局 —— 三栏结构（所有页面通用）

```
┌──────────────────────────────────────────────────────────────────────┐
│  ┌─────────┐ ┌────────────────────────────────────────────────────┐ │
│  │         │ │  Header (h=48px)                        ┌────────┐  │ │
│  │ Sidebar │ │  Breadcrumb / Page Title           [Theme] [Avatar] │ │
│  │         │ ├────────────────────────────────────────────────────┤ │
│  │ w=56px  │ │                                                    │ │
│  │         │ │                                                    │ │
│  │  Icon1  │ │              Page Content                          │ │
│  │  Icon2  │ │              (flex-1, overflow-auto)               │ │
│  │  Icon3  │ │                                                    │ │
│  │  Icon4  │ │                                                    │ │
│  │         │ │                                                    │ │
│  │         │ │                                                    │ │
│  │         │ │                                                    │ │
│  │         │ │                                                    │ │
│  │         │ │                                                    │ │
│  └─────────┘ └────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
   Sidebar: bg-root, border-r: 1px border-subtle, 无文字只 Icon
   Header:  bg-surface, border-b: 1px border-subtle
   Content: bg-root, p-space-6
```

**Sidebar 导航项**:
```
┌──────┐
│  💬  │  ← / (Chat)           → ChatPage
│  📊  │  ← /dashboard         → DashboardPage
│  🔍  │  ← /trace             → TracePage (当有 active trace 时)
│      │
│  ⚙️  │  ← /settings          → SettingsPage (底部固定)
└──────┘
  选中态: 左侧 2px accent 竖线 + bg-elevated
  未选中: 无背景
  Hover: bg-elevated (transition-fast)
```

### 3.2 Chat 页布局 —— 对话式（首页）

```
┌──────────────────────────────────────────────────────────────────────┐
│ Sidebar │                        ChatPage                            │
│         │ ┌────────────────────────────────────────────────────────┐ │
│         │ │                                                        │ │
│         │ │               (空状态 / 消息列表)                       │ │
│         │ │                                                        │ │
│         │ │   ┌──────────────────────────────────────────────┐     │ │
│         │ │   │  User Message (right-aligned)                │     │ │
│         │ │   │  "我的快递什么时候到？"                       │     │ │
│         │ │   │  bg-accent-subtle, 圆角右上=0, max-w-70%     │     │ │
│         │ │   └──────────────────────────────────────────────┘     │ │
│         │ │                                                        │ │
│         │ │   ┌──────────────────────────────────────────────┐     │ │
│         │ │   │  AI Message (left-aligned)                   │     │ │
│         │ │   │                                              │     │ │
│         │ │   │  [IntentBadge: 订单查询]                      │     │ │
│         │ │   │  [ToolCallCard: queryOrder ✓ 230ms]          │     │ │
│         │ │   │                                              │     │ │
│         │ │   │  您的订单 ORD2024001 已于昨天下午发货，       │     │ │
│         │ │   │  预计明天到达。物流单号：SF1234567890 █       │     │ │
│         │ │   │                                              │     │ │
│         │ │   │  bg-surface, 圆角左上=0, max-w-80%           │     │ │
│         │ │   └──────────────────────────────────────────────┘     │ │
│         │ │                                                        │ │
│         │ ├────────────────────────────────────────────────────────┤ │
│         │ │  ┌──────────────────────────────────────────────┐     │ │
│         │ │  │  输入你想问的问题...                  [发送]  │     │ │
│         │ │  │  bg-input, radius-md, h=48px                  │     │ │
│         │ │  │  下方: "Shift+Enter 换行 | ShopMind v2.1"    │     │ │
│         │ │  └──────────────────────────────────────────────┘     │ │
│         │ └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

**ChatPage 空状态**（无历史消息时）:
```
          ┌─────────────────────────────────┐
          │                                 │
          │         🧠 (Icon 48px)          │
          │                                 │
          │    ShopMind Enterprise          │
          │    Trustworthy AI Agent         │
          │                                 │
          │  ┌──────────┐ ┌──────────┐      │
          │  │ 查订单   │ │ 退换货   │      │
          │  └──────────┘ └──────────┘      │
          │  ┌──────────┐ ┌──────────┐      │
          │  │ 商品咨询 │ │ 物流查询 │      │
          │  └──────────┘ └──────────┘      │
          │                                 │
          │  ┌──────────────────────────┐   │
          │  │ 输入消息...              │   │
          │  └──────────────────────────┘   │
          └─────────────────────────────────┘
          居中、垂直居中
          Hero Title: Page Title 规格
          Suggestion Chips: bg-elevated, radius-md, body-small
```

**MessageBubble 规范**:

| 属性 | User Bubble | AI Bubble |
|------|------------|-----------|
| 背景 | accent-subtle (`rgba(59,130,246,0.12)`) | bg-surface |
| 边框 | 无 | 1px border-subtle |
| 圆角 | 16px 16px 4px 16px (右上直角) | 16px 16px 16px 4px (左上直角) |
| 最大宽度 | 70% | 80% |
| 对齐 | 靠右 (margin-left: auto) | 靠左 |
| 字号 | Body (14px) | Body (14px) |
| 内边距 | 12px 16px | 12px 16px |
| 间距 | 上下 gap: space-5 (20px) | 同 |
| 阴影 | 无 | shadow-card |
| 动画 | 滑入: translateY(8px)→0, opacity 0→1, 200ms | 同 |

**StreamingText 规范**:
```
颜色: text-primary
光标: █ (blink animation, 1s step-end infinite)
未完成时: 消息气泡底部显示微弱 pulse 动画（bg-surface → bg-elevated → bg-surface）
完成时: 光标消失, pulse 停止
```

**ToolCallCard 规范**:
```
┌──────────────────────────────────────────────┐
│  🔧 queryOrder                        230ms  │
│  ┌──────────────────────────────────────────┐ │
│  │ { "orderId": "ORD2024001" }             │ │
│  └──────────────────────────────────────────┘ │
│  ✓ 查询成功 → 订单状态: 已发货               │
└──────────────────────────────────────────────┘
  背景: bg-elevated, radius-md, border: 1px border-subtle
  标题栏: h=32px, body-small, text-secondary
  代码块: bg-input, JetBrains Mono 13px, p-space-2, radius-sm
  结果行: body-small, text-secondary
  ToolCallCard 在消息气泡内部，与文字间用 space-3 分隔
  仅 1 个 tool call: 直接展开
  多个 tool calls: 默认折叠，显示 "3 tool calls"，点击展开
```

**IntentBadge 规范**:
```
┌──────────────┐
│  订单查询     │
└──────────────┘
  背景: info (透明度 0.12), 文字: info
  radius-sm, h=22px, px-space-2
  body-small, font-weight: 500
  位置: AI 消息气泡最上方，与文字间 space-2
```

---

## 4. Dashboard 页布局（评测仪表盘）

### 4.1 Dashboard 首页布局

```
┌──────────────────────────────────────────────────────────────────────┐
│ Sidebar │                      DashboardPage                         │
│         │ ┌────────────────────────────────────────────────────────┐ │
│         │ │  📊 Evaluation Dashboard              [刷新] [导出]    │ │
│         │ ├────────────────────────────────────────────────────────┤ │
│         │ │                                                        │ │
│         │ │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │ │
│         │ │  │ MetricCard   │ │ MetricCard   │ │ MetricCard   │   │ │
│         │ │  │ Intent       │ │ Recall@K     │ │ Hallucination│   │ │
│         │ │  │ 82.5%        │ │ 0.775        │ │ 2.5%         │   │ │
│         │ │  │ ↓ +15pp      │ │ ↓ +0.19      │ │ ↓ -15pp      │   │ │
│         │ │  └──────────────┘ └──────────────┘ └──────────────┘   │ │
│         │ │                                                        │ │
│         │ │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   │ │
│         │ │  │ MetricCard   │ │ MetricCard   │ │ MetricCard   │   │ │
│         │ │  │ Tool Acc     │ │ Task Success │ │ TTFT         │   │ │
│         │ │  │ 85.0%        │ │ 57.5%        │ │ 480ms        │   │ │
│         │ │  └──────────────┘ └──────────────┘ └──────────────┘   │ │
│         │ │                                                        │ │
│         │ │  ┌──────────────┐ ┌──────────────┐                    │ │
│         │ │  │ MetricCard   │ │ MetricCard   │                    │ │
│         │ │  │ P95 Latency  │ │ Workflow     │                    │ │
│         │ │  │ 1210ms       │ │ 97.5%        │                    │ │
│         │ │  └──────────────┘ └──────────────┘                    │ │
│         │ │                                                        │ │
│         │ ├────────────────────────────────────────────────────────┤ │
│         │ │                                                        │ │
│         │ │  ┌─────────────────────┐ ┌───────────────────────────┐│ │
│         │ │  │                     │ │                           ││ │
│         │ │  │  MetricsRadarChart  │ │  FailurePieChart          ││ │
│         │ │  │  (八维雷达图)        │ │  (失败分布饼图)            ││ │
│         │ │  │                     │ │                           ││ │
│         │ │  │  w: 50%             │ │  w: 50%                   ││ │
│         │ │  │  h: 360px           │ │  h: 360px                 ││ │
│         │ │  └─────────────────────┘ └───────────────────────────┘│ │
│         │ │                                                        │ │
│         │ │  ┌──────────────────────────────────────────────────┐  │ │
│         │ │  │  ExperimentTable (实验列表)                       │  │ │
│         │ │  │  ID │ Version │ Date │ Passed │ Intent │ Halluc │  │ │
│         │ │  │  ───┼─────────┼──────┼────────┼────────┼────────│  │ │
│         │ │  │  002│ v2.1    │07-23 │ 23/40  │ 82.5%  │  2.5%  │  │ │
│         │ │  │  001│ v2.0    │07-22 │ 13/40  │ 67.5%  │ 17.5%  │  │ │
│         │ │  │      │         │      │        │        │        │  │ │
│         │ │  │  w: 100%                                       │  │ │
│         │ │  └──────────────────────────────────────────────────┘  │ │
│         │ └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

### 4.2 MetricCard 规范

```
┌─────────────────────────────────┐
│  Intent Accuracy           🎯   │  ← Card Title (body-small, text-secondary) + Icon (18px, text-tertiary)
│                                 │
│  82.5%                         │  ← Metric Value (Metric Value 规格, text-primary)
│                                 │
│  ┌──────────────────────────┐  │
│  │ ██████████████████░░░░░  │  │  ← 微型进度条 (h=4px, radius-full)
│  └──────────────────────────┘  │     fill: accent, bg: border-subtle
│                                 │
│  +15.0pp vs v2.0              │  ← Delta label (body-small, success 色)
└─────────────────────────────────┘
  背景: bg-surface
  边框: 1px border-subtle
  圆角: radius-xl (20px)
  宽度: 固定 min-w=240px, flex-1 (网格自适配)
  内边距: space-5 (20px)
  阴影: shadow-card
  Hover: shadow-elevated + border-strong (transition-base)
```

### 4.3 图表卡片规范 (Chart Card)

```
┌─────────────────────────────────────────────┐
│  📊 Failure Distribution                    │  ← Card Title (Section Title 规格)
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │                                       │  │
│  │           (ECharts 实例)               │  │  ← h=320px, 内边距 space-2
│  │                                       │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
  背景: bg-surface
  边框: 1px border-subtle
  圆角: radius-xl (20px)
  内边距: space-6 (24px)
  阴影: shadow-card
  ECharts 背景色: transparent (继承卡片 bg-surface)
  ECharts 文字颜色: text-secondary
  ECharts 网格线颜色: border-subtle
```

### 4.4 ExperimentTable 规范

```
  表头: bg-elevated, h=40px, body-small, text-secondary, uppercase
  行:   h=44px, bg-surface, border-b: 1px border-subtle
  Hover: bg-elevated (transition-fast)
  选中:  border-l: 3px accent, bg-elevated

  Passed 列: 数字右对齐, 通过数 success 色, 失败数 error 色
  Hallucination 列: 0% → success, >5% → warning, >10% → error
```

### 4.5 FailedCaseTable 规范

```
  ┌──────────────────────────────────────────────────────────────┐
  │  Case ID  │ Query                  │ Failure Reason    │ ... │
  │───────────┼────────────────────────┼───────────────────┼─────│
  │  TC021    │ 我的快递什么时候到？    │ WRONG_INTENT      │ ... │
  │  TC022    │ 帮我查一下订单状态      │ WRONG_TOOL        │ ... │
  │  TC029    │ 你们公司有多少员工？    │ HALLUCINATION ⚠   │ ... │
  └──────────────────────────────────────────────────────────────┘
  同 ExperimentTable 规格
  特殊：HALLUCINATION 行 → 左侧 error 色竖条 + error 色文字
  特殊：SAFETY_BLOCKED 行 → 左侧 warning 色竖条
```

---

## 5. Comparison 页布局（A/B 版本对比）

```
┌──────────────────────────────────────────────────────────────────────┐
│ Sidebar │                    ComparisonPage                          │
│         │ ┌────────────────────────────────────────────────────────┐ │
│         │ │  ⚖️ v2.0 vs v2.1 Comparison                            │ │
│         │ ├────────────────────────────────────────────────────────┤ │
│         │ │                                                        │ │
│         │ │  ┌──────────────────────────────────────────────────┐  │ │
│         │ │  │              VersionComparisonBarChart           │  │ │
│         │ │  │              (横向 Before vs After 柱状图)        │  │ │
│         │ │  │              h: 480px, w: 100%                   │  │ │
│         │ │  └──────────────────────────────────────────────────┘  │ │
│         │ │                                                        │ │
│         │ │  ┌───────────────────────┐ ┌─────────────────────────┐ │ │
│         │ │  │  MetricsRadarChart    │ │  Comparison Table       │ │ │
│         │ │  │  (双版本叠加雷达)      │ │  Dimension │v2.0│v2.1│Δ │ │ │
│         │ │  │  h: 340px, w: 45%    │ │  w: 55%                │ │ │
│         │ │  └───────────────────────┘ └─────────────────────────┘ │ │
│         │ │                                                        │ │
│         │ │  ┌──────────────────────────────────────────────────┐  │ │
│         │ │  │  One-Line Summary                                │  │ │
│         │ │  │  v2.0→v2.1: Hallucination -85.7%,                │  │ │
│         │ │  │  Task Success +76.9%, P95 Latency -78.0%         │  │ │
│         │ │  └──────────────────────────────────────────────────┘  │ │
│         │ └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

Comparison Table 规范:
```
  | Dimension          | v2.0  | v2.1  | Δ      | Δ%     |
  |--------------------|-------|-------|--------|--------|
  | Intent Accuracy    | 67.5% | 82.5% | +15.0% | +22.2% |
  | Hallucination Rate | 17.5% | 2.5%  | -15.0% | -85.7% |
  | ...

  Δ 正值 → success 色文字 + ↑
  Δ 负值 → error 色文字 + ↓ (但 Hallucination/TTFT/P95 的负值是好的, 用 success 色)
```

---

## 6. Trace 页布局（链路追踪）

```
┌──────────────────────────────────────────────────────────────────────┐
│ Sidebar │                       TracePage                            │
│         │ ┌────────────────────────────────────────────────────────┐ │
│         │ │  🔍 Trace: t_abc123...          ⏱ 2.3s   ✓ SUCCESS     │ │
│         │ │  memoryId: eval_v2.1_TC007                              │ │
│         │ ├────────────────────────────────────────────────────────┤ │
│         │ │                                                        │ │
│         │ │  ┌─────────────────────────────────────────────┐      │ │
│         │ │  │  MetricsPanel (横向 6 个小指标卡)            │      │ │
│         │ │  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐      │      │ │
│         │ │  │  │TTFT  │ │Total │ │Token │ │Chunks│      │      │ │
│         │ │  │  │480ms │ │1210ms│ │ 45   │ │  3   │      │      │ │
│         │ │  │  └──────┘ └──────┘ └──────┘ └──────┘      │      │ │
│         │ │  └─────────────────────────────────────────────┘      │ │
│         │ │                                                        │ │
│         │ │  ┌─────────────────────────────────────────────┐      │ │
│         │ │  │  TraceTimeline                               │      │ │
│         │ │  │                                             │      │ │
│         │ │  │  ●── INTENT_ANALYSIS ────── 15ms ── ✓      │      │ │
│         │ │  │  │     "Intent: order_query (conf=0.92)"    │      │ │
│         │ │  │  │                                         │      │ │
│         │ │  │  ●── CONTEXT_HYDRATION ──── 120ms ── ✓     │      │ │
│         │ │  │  │     ├─ Memory: 18 msgs loaded            │      │ │
│         │ │  │  │     └─ RAG: 3 chunks (top=0.89)          │      │ │
│         │ │  │  │                                         │      │ │
│         │ │  │  ●── PROMPT_ASSEMBLY ─────── 8ms ── ✓      │      │ │
│         │ │  │  │     "Tokens: 1240"                       │      │ │
│         │ │  │  │                                         │      │ │
│         │ │  │  ●── LLM_INFERENCE ──────── 850ms ── ✓     │      │ │
│         │ │  │  │     "Tokens: in=1240 out=210"            │      │ │
│         │ │  │  │                                         │      │ │
│         │ │  │  ●── TOOL_EXECUTION ─────── 230ms ── ✓     │      │ │
│         │ │  │  │     "Tool: queryOrder(...) → SUCCESS"    │      │ │
│         │ │  │  │                                         │      │ │
│         │ │  │  ●── ANSWER_OUTPUT ──────── 0ms ── ✓       │      │ │
│         │ │  │     "Your order ORD2024001 has shipped..."  │      │ │
│         │ │  └─────────────────────────────────────────────┘      │ │
│         │ └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

**TraceTimeline 规范**:
```
  垂直时间轴, 左侧 timeline line (2px border-subtle)
  每个节点:
    ● 圆点: w=12px, h=12px
      SUCCESS → success 色
      FAILED  → error 色
      RUNNING → accent 色 (pulse animation)

    Step Name:   body 规格, text-primary
    Latency:     body-small, text-tertiary (右对齐)
    Detail 区:   默认折叠, 点击展开
      背景: bg-elevated
      圆角: radius-md
      内边距: space-3
      字体: JetBrains Mono 11px, text-secondary
      展开/折叠: transition-base, max-height 动画

  节点之间间距: space-4 (16px)
```

**TraceSpanDetail (展开后)**:
```
  ┌─────────────────────────────────────────────┐
  │  Input:                                     │
  │  ┌─────────────────────────────────────────┐│
  │  │ { "query": "我的快递什么时候到？" }      ││
  │  └─────────────────────────────────────────┘│
  │                                             │
  │  Output:                                    │
  │  ┌─────────────────────────────────────────┐│
  │  │ { "answer": "您的订单 ORD2024001..." }   ││
  │  └─────────────────────────────────────────┘│
  │                                             │
  │  Confidence: 0.92   Status: SUCCESS         │
  └─────────────────────────────────────────────┘
  Code block: bg-input, JetBrains Mono 11px
```

---

## 7. 通用组件规范

### 7.1 Button

```
  Primary:
    bg: accent, color: white
    h=36px, px=space-4 (16px), radius=radius-md (10px)
    font: body-small, weight: 500
    Hover: accent-hover (transition-fast)
    Disabled: opacity 0.4

  Secondary:
    bg: transparent, border: 1px border-default, color: text-primary
    Hover: bg-elevated, border-strong (transition-fast)

  Ghost:
    bg: transparent, color: text-secondary
    Hover: bg-elevated, color: text-primary (transition-fast)

  Icon Button:
    w=h=32px, radius-full
    bg: transparent
    Hover: bg-elevated
```

### 7.2 Input

```
  bg: bg-input
  border: 1px border-default
  color: text-primary
  h=40px (default) / 48px (Chat)
  px=space-3 (12px)
  radius=radius-md (10px)

  Focus: border-accent, shadow-glow (transition-fast)
  Placeholder: text-tertiary, body 规格
  Error: border-error
```

### 7.3 Modal

```
  bg: bg-elevated
  border: 1px border-subtle
  radius: radius-xl (20px)
  shadow: shadow-elevated
  max-w: 600px
  Header: h=56px, border-b: 1px border-subtle, Section Title
  Body: p-space-6
  Footer: border-t: 1px border-subtle, p-space-4, flex justify-end
  Backdrop: rgba(0,0,0,0.6), backdrop-blur(4px)
  Animation: fadeIn + scale(0.96→1), 200ms ease-out
```

### 7.4 Drawer (右侧滑出面板)

```
  bg: bg-surface
  border-l: 1px border-subtle
  shadow: shadow-elevated
  w: 480px
  Animation: slideInRight, 300ms ease-out
  (用于 TraceSpan 详情、设置面板)
```

### 7.5 Badge / Tag

```
  Default:
    bg: bg-elevated, color: text-secondary
    h=22px, px=space-2 (8px), radius=radius-sm (6px)
    font: caption, weight: 500

  状态变体:
    Success:  bg=success (0.12 opacity), color=success
    Warning:  bg=warning (0.12 opacity), color=warning
    Error:    bg=error (0.12 opacity), color=error
    Info:     bg=info (0.12 opacity), color=info

  Intent Badge 用 Info
  工具 Badge 用 accent
  幻觉 Badge 用 Error
```

### 7.6 Loading / Skeleton

```
  Spinner: 24px ring, border: 2px border-subtle, border-top: accent
           animation: spin 0.8s linear infinite

  Skeleton Card:
    bg: bg-elevated
    radius: radius-xl
    animation: shimmer (bg-surface → bg-elevated → bg-surface, 1.5s infinite)

  Streaming 指示器:
    消息气泡底部, h=4px, w=40px, radius-full
    bg: accent, opacity: 0.5
    animation: pulse 1.5s ease-in-out infinite
```

### 7.7 Empty State

```
  ┌──────────────────────────────┐
  │                              │
  │          (Icon 48px)         │  ← text-tertiary
  │                              │
  │        No experiments        │  ← Section Title, text-secondary
  │   Run a benchmark to see     │  ← Body Small, text-tertiary
  │       results here.          │
  │                              │
  │       [Run Benchmark]        │  ← Secondary Button
  │                              │
  └──────────────────────────────┘
  居中, p-space-8
```

---

## 8. 页面间的导航与过渡

### 8.1 Sidebar 导航行为

```
  Chat ←→ Dashboard: 页面切换 (无动画, 即刻渲染)
  Dashboard → Experiment Detail: 从 ExperimentTable 点击行进入
  Dashboard → Comparison: 从 Dashboard 顶部 "Compare v2.0 vs v2.1" 按钮进入
  Trace: 从 Chat 页面的消息气泡中点击 Trace ID 进入
```

### 8.2 面包屑 (Breadcrumb)

```
  Dashboard > Experiment Detail   →  / dashboard / experiments / eval-002
  Dashboard > Comparison          →  / dashboard / comparison
  Trace                           →  / trace / t_abc123

  样式: body-small, text-tertiary, 分隔符 → text-tertiary
  当前页: text-secondary
```

---

## 9. 响应式断点 (备忘)

```
  ≥ 1440px: 完整三栏布局, Sidebar 56px + Content
  1024-1439px: 同上，Dashboard 卡片 3 列
  768-1023px: Sidebar 收起为 48px, 卡片 2 列
  < 768px: 无 Sidebar, 底部 TabBar, 卡片 1 列
           Chat: 全屏对话
           Dashboard: 指标卡片 2 列, 图表 1 列
```

---

## 10. 文件清单与优先级

本 Blueprint 覆盖的组件，按实施顺序:

| 文件 | 包含组件 |
|------|---------|
| `DesignTokens.css` | 所有 CSS 变量 (§2.1-2.7) |
| `MainLayout.tsx` | Sidebar + Header + Outlet (§3.1) |
| `ChatPage.tsx` | ChatPanel (§3.2) |
| `MessageBubble.tsx` | 用户/AI 气泡 (§3.2) |
| `StreamingText.tsx` | 流式渲染 (§3.2) |
| `ToolCallCard.tsx` | 工具调用卡片 (§3.2) |
| `IntentBadge.tsx` | 意图标签 (§3.2) |
| `ChatInput.tsx` | 输入框 (§3.2) |
| `MetricCard.tsx` | 指标卡片 (§4.2) |
| `DashboardPage.tsx` | 仪表盘首页 (§4.1) |
| `ExperimentTable.tsx` | 实验列表 (§4.4) |
| `FailedCaseTable.tsx` | 失败用例表 (§4.5) |
| `ComparisonPage.tsx` | A/B 对比页 (§5) |
| `TracePage.tsx` | 链路追踪页 (§6) |
| `TraceTimeline.tsx` | 追踪时间轴 (§6) |
| `TraceSpanDetail.tsx` | Span 详情面板 (§6) |
| `GenericButton.tsx` | Button 变体 (§7.1) |
| `GenericInput.tsx` | Input (§7.2) |
| `GenericModal.tsx` | Modal (§7.3) |
| `GenericDrawer.tsx` | Drawer (§7.4) |
| `GenericBadge.tsx` | Badge/Tag (§7.5) |
| `Skeleton.tsx` | 骨架屏 (§7.6) |
| `EmptyState.tsx` | 空状态 (§7.7) |

---

**本文档是所有 AI 前端代码生成的唯一视觉标准。任何偏离本文档的样式都属于 Bug。**
