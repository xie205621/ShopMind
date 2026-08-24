# P2-2：Research Question Lock — 研究问题冻结

> 状态：冻结。此后不再讨论更换研究方向。
> 日期：2026-08-17
> 下一阶段：P2-3 Dataset / Annotation Design

---

## 1. 研究主线

```
Trustworthy / Reliable LLM Agents
        ↓
Safety–Utility Trade-off
        ↓
Multi-tool Agent
        ↓
Tool Visibility 是风险控制的前置决策点
        ↓
Risk-aware Tool Menu Pruning (RTMP)
        ↓
Safety / Utility / Cost 三维比较
```

**一级方向：** 大语言模型智能体的可信性与可靠性（Trustworthy / Reliable LLM Agents）。

**二级问题：** Safety–Utility Trade-off — 如何在保证安全性的同时，尽可能保留 Agent 的任务完成能力。

**三级切口：** 工具可见性（Tool Visibility）作为风险控制的前置决策点。

---

## 2. 研究问题

### 中文正式表述

> **在多工具 LLM Agent 中，能否通过在执行前联合考虑任务相关性与工具风险，对可见工具集合进行动态裁剪，从而减少高风险错误工具调用，同时保持任务完成能力并降低运行时安全干预开销？**

### 英文工作表述

> **Can risk-aware pre-execution tool-menu pruning reduce high-risk tool misuse while preserving task utility and reducing runtime safety overhead in multi-tool LLM agents?**

### 问题拆解

| 维度 | 子问题 |
|------|------|
| 安全性 | 裁剪工具菜单能否降低高风险工具误调用？ |
| 效用性 | 裁剪是否会导致合法任务无法完成（Over-refusal）？ |
| 效率 | 相比事后安全控制，前置裁剪能否减少运行时开销？ |
| 场景依赖性 | 在不同任务类型下，RTMP 的效果是否存在显著差异？ |

### 核心矛盾

```
All Tools
  → 工具选择空间太大
  → 误选 / 幻觉 / 风险暴露

简单隐藏高风险工具
  → 安全 ↑
  → 任务成功 ↓ / 过度拒答 ↑
```

因此 RTMP 真正研究的不是"怎么过滤工具"，而是：

> **"工具可见性应该如何根据风险和任务动态变化？"**

---

## 3. RTMP 定义

### 3.1 方法名称

**Risk-aware Tool Menu Pruning（RTMP）** — 风险感知工具菜单裁剪。

### 3.2 核心思想

在 Agent 真正调用工具之前，联合考虑 **Task Relevance**、**Tool Risk** 和 **Context Risk**，对可见工具集合进行动态裁剪。

### 3.3 架构

```
              User Query
                  ↓
            Intent / Context
                  ↓
       ┌────────────────────────┐
       │  Risk-aware Router     │
       │                        │
       │  Task Relevance        │
       │  Tool Risk             │
       │  Context Risk          │
       └───────────┬────────────┘
                   ↓
             Visible Tools
                   ↓
               LLM Agent
                   ↓
             Tool Execution
```

### 3.4 算法草图（非实现，仅设计）

```
Input:
    user query q
    tool set T

Step 1: infer task context C
Step 2: calculate relevance R(q, t)
Step 3: calculate tool risk K(t)
Step 4: calculate contextual risk X(q, t)
Step 5: compute visibility score V(q, t) = f(R, K, X)
Step 6: select visible tool set T'
Step 7: LLM performs tool selection over T'
```

### 3.5 风险模型（推荐规则化第一版）

**Tool Risk**（工具自身属性）：

| 维度 | 说明 |
|------|------|
| Side Effect | 是否产生副作用（只读/写入） |
| Financial Impact | 是否涉及资金流转 |
| Reversibility | 操作是否可逆 |
| Data Sensitivity | 返回数据是否敏感 |
| Permission Scope | 操作权限范围 |

**Context Risk**（当前请求风险）：

| 维度 | 说明 |
|------|------|
| Intent Confidence | 意图识别的置信度 |
| Authorization | 用户授权级别 |
| Target Scope | 操作目标范围 |
| Request Type | 请求类型（正常/攻击/模糊） |

**综合决策：**

> Tool Risk × Context Risk × Task Relevance → Visibility Score

### 3.6 关键约束：不是简单隐藏

```
refund = high risk → 永远隐藏  ← ❌ 错误做法

refund = high risk
  + 用户确实需要退款
  + 意图置信度高
  → 保留 refund 可见        ← ✅ 正确做法
```

三类场景的区分：

| 场景 | Tool Risk | Context | RTMP 行为 |
|------|:---:|------|------|
| 低风险工具（queryOrder） | 低 | 正常 | 保留 |
| 高风险合法任务（用户要退款） | 高 | 正常 + 高置信 | 保留 |
| 高风险恶意任务（越权退款） | 高 | 攻击 + 低置信 | 裁剪 |

---

## 4. 实验基线

### 4.1 Baseline A：All Tools

```
All Tools
   → LLM
   → Execute
```

- 所有工具直接暴露给 LLM
- 代表：**无风险控制**
- 当前 ShopMind 默认行为

### 4.2 Baseline B：All Tools + Post-hoc Safety Verifier

```
All Tools
   → LLM (Tool Call)
   → Safety Verifier
   → Execute / Block
```

- LLM 先选工具，再在边界做安全检查
- 代表：**事后风险控制**
- 插入点：`McpEngine.executeTool()` 调用之前

### 4.3 Method C：RTMP

```
All Tools
   → RTMP Pruning
   → Filtered Tools
   → LLM
   → Execute
```

- 在 LLM 选择之前裁剪工具菜单
- 代表：**前置风险控制**
- 插入点：`discoverTools()` 之后、`PromptAssembler` 之前

### 4.4 实验比较逻辑

| 基线 | 风险控制位置 | 研究问题 |
|------|:---:|------|
| A vs B | 无 vs 事后 | 安全控制是否必要？ |
| B vs C | 事后 vs 前置 | 前置裁剪是否优于事后验证？ |
| A vs C | 无 vs 前置 | 前置裁剪是否损害任务完成？ |

---

## 5. 核心指标

### 5.1 Utility（效用）

| 指标 | 定义 | 方向 |
|------|------|:---:|
| **Core Task Success** | 预期回答 + 实际正确 / 核心任务总数 | ↑ |

### 5.2 Safety（安全）

| 指标 | 定义 | 方向 |
|------|------|:---:|
| **High-risk Tool Misuse** | 高风险工具被错误调用的次数 | ↓ |
| **Safety Violation** | 应该拒答但未拒答的次数 | ↓ |
| **Failed-to-refuse** | REFUSE_EXPECTED 场景中实际未拒答的比例 | ↓ |

### 5.3 Over-conservatism（过度保守）

| 指标 | 定义 | 方向 |
|------|------|:---:|
| **Over-refusal** | 应该正常完成但因裁剪过度而拒答的次数 | ↓ |

### 5.4 Efficiency（效率）

| 指标 | 定义 | 方向 |
|------|------|:---:|
| **Tool Calls** | 工具调用总次数 | ↓ |
| **Latency** | 端到端延迟（ms） | ↓ |
| **Token / Cost** | 输入输出 Token 数及估算成本 | ↓ |

### 5.5 核心结果表

| 方法 | Task Success ↑ | Safety Violation ↓ | Over-refusal ↓ | Tool Calls ↓ | Latency ↓ |
|------|:---:|:---:|:---:|:---:|:---:|
| All Tools | | | | | |
| All + Verifier | | | | | |
| **RTMP** | | | | | |

---

## 6. 研究假设

> 不预设具体数字，写成可检验假设。

### H1：风险控制

> RTMP 能降低高风险工具误调用率（High-risk Tool Misuse ↓）。

### H2：任务效用

> RTMP 在减少风险暴露的同时，不会显著降低 Core Task Success。

### H3：过度拒答

> 如果风险裁剪过于激进，会提高 Over-refusal；合理的风险感知策略能够缓解这一问题。

### H4：效率

> 相比 All Tools + Post-hoc Verifier，RTMP 能减少不必要的工具决策和运行时安全检查开销。

### H5：场景依赖性

> RTMP 的收益在多工具、高风险、意图不明确的任务中更加明显。

---

## 7. 数据集策略

### 7.1 工具池

> **Baseline Tool Pool = 4 个生产业务工具**（非 8 个）

| 工具 | 类型 | 风险特征 |
|------|------|------|
| queryOrder | 只读查询 | 信息泄露风险 |
| refund | 写入操作 | 有副作用 + 涉及资金 + 部分不可逆 |
| queryPoints | 只读查询 | 信息泄露风险 |
| queryCoupons | 只读查询 | 信息泄露风险 |

> ⚠️ `searchProduct`、`confirmPayment`、`mockQueryOrder`、`slowTask` 为测试/开发工具，不纳入 RTMP 正式实验工具池。

### 7.2 现有 126 条 Benchmark

**用途：** Existing Benchmark / Baseline / 回归测试。

- 41 条含 `expectedTool`
- 15 条 `SAFETY_BLOCKED`
- 覆盖 7 个场景（NORMAL / TOOL / SAFETY / RAG / EDGE / STRESS / MULTI_TURN）

**局限性：** 缺乏系统性的高风险误调用案例，无法单独支撑 RTMP 全部实验。

### 7.3 新增 RTMP Safety–Utility Set

**规模：** 30～60 条，精心设计。

**分类：**

| 类别 | 说明 | 示例 |
|------|------|------|
| Safe Low-risk | 低风险工具 + 正常请求 | 查询订单状态 |
| Safe High-risk | 高风险工具 + 合法请求 | 用户正常申请退款 |
| High-risk but legitimate | 高风险工具 + 合法但需谨慎 | 大额退款 |
| Unsafe high-risk request | 高风险工具 + 恶意请求 | 越权退款他人订单 |
| Tool distractor | 存在干扰工具的场景 | 问积分但 refund 也在菜单中 |
| Multi-tool | 需要多工具串联 | 先查订单再退款 |
| Ambiguous intent | 意图模糊 + 高风险工具 | "帮我把那个处理一下" |
| Over-refusal boundary | 测试过度裁剪的边界 | 裁剪后合法任务能否完成 |

---

## 8. 控制变量

> 所有实验组必须保持以下参数一致（复用 P2-0.5C 单一事实源）。

| 变量 | 约束 |
|------|------|
| Model | 统一（DeepSeek） |
| Temperature | 统一（BenchmarkConfig） |
| TopP | 统一（BenchmarkConfig） |
| MaxTokens | 统一（BenchmarkConfig） |
| Seed | 统一（BenchmarkConfig） |
| System Prompt | 统一（除工具列表外） |
| Workflow | 统一（ShopAgentOrchestrator） |
| RAG / Memory | 统一 |
| Dataset | 同一套测试集 |

---

## 9. 预期贡献

### Contribution 1

> **提出一个将工具相关性与工具风险联合考虑的 Agent 工具可见性机制（RTMP）。**

### Contribution 2

> **建立面向 Safety–Utility Trade-off 的工具调用实验协议，包括专用测试集设计、多基线比较和三维指标评估体系。**

### Contribution 3

> **通过 All Tools / Post-hoc Verifier / RTMP 的系统比较，分析前置风险控制对安全、任务完成率和运行成本的影响，为 Agent 工具决策空间设计提供实证依据。**

---

## 10. 项目介绍（对外表述）

> 我构建了一个可运行、可评测的 LLM Agent 实验平台 **ShopMind**，目前主要关注多工具 Agent 的可信执行问题。具体研究工具可见性对 Safety–Utility Trade-off 的影响，并设计 **风险感知的 Tool Menu Pruning（RTMP）** 机制，在执行前降低高风险工具误调用，同时尽量保持任务完成能力。

---

## 11. 冻结声明

从此刻开始：

- ✅ **研究方向冻结**：Trustworthy LLM Agent → Safety–Utility Trade-off → RTMP
- ✅ **不再讨论更换研究方向**（如 RAG、Tool Selection、可信大模型等）
- ✅ **后续允许调整**：参数、风险模型、pruning strategy、threshold、baseline 实现细节
- ✅ **不允许**：更换核心研究问题、更换方法名称、更换实验平台

---

## 12. 后续路线

```
P2-2 Research Question Lock       ← 当前（本文档）
        ↓
P2-3 Dataset / Annotation Design
        ↓
P2-4 Baseline Design
        ↓
P2-5 RTMP Method Design
        ↓
P2-6 Offline / Mock Validation
        ↓
P3 正式真实 LLM 实验
        ↓
P3-1 Ablation
P3-2 Error Analysis
P3-3 Statistical Analysis
        ↓
P4 论文 / 保研 / 秋招包装
```

---

*文档创建时间：2026-08-17*
*P2-2 状态：🔒 研究问题已冻结*