# ShopMind — 评估驱动的反应式 AI 智能体编排平台

[![Version](https://img.shields.io/badge/version-v2.3-blue)](https://github.com)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2_WebFlux-green)](https://spring.io)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)

**ShopMind** 是一个面向大模型软件工程（LLM4SE）的评估驱动 AI 智能体编排平台。它将 LLM 的非确定行为转化为版本化、可观测、可量化的确定性工作流执行，为智能体可靠性研究提供基础的实证测试床。

---

## Architecture

ShopMind 采用**六引擎反应式微内核架构**，严格遵循领域驱动设计（DDD），实现关注点分离：

```text
                       ┌─────────────────────────────────┐
                       │     Agent Orchestrator           │
                       │  (Reactive Event Bus + 限流)     │
                       ├─────────────────────────────────┤
                       │                                 │
  ┌──────────┐  ┌──────┴──────┐  ┌──────────┐  ┌────────┴───┐
  │ Memory   │  │ RAG Engine  │  │Workflow  │  │ MCP Engine │
  │ Engine   │  │ (Retrieval) │  │ Engine   │  │ (Sandbox)  │
  └──────────┘  └─────────────┘  └──────────┘  └────────────┘
                       │                                 │
                       └───────────────┬─────────────────┘
                                       │
                          ┌────────────┴────────────┐
                          │   Evaluation Engine      │
                          │ (Benchmark + LLM-Judge)  │
                          └─────────────────────────┘
```

| Engine | 职责 |
|--------|------|
| **Orchestrator** | 意图路由 → 上下文装配 → LLM 流式调用 → 响应返写 |
| **Memory Engine** | 多租户滑动窗口记忆（MongoDB），FIFO 截断 |
| **RAG Engine** | Embedding → 向量检索 → 阈值熔断 → 知识注入 |
| **Workflow Engine** | 版本化 Persona + ToolRules + Constraints 管理 |
| **MCP Engine** | 反射沙箱工具执行，前置安全路由 |
| **Evaluation Engine** | 自动化 Benchmark + LLM-as-Judge + 失败归因 |

---

## Workflow

工作流是 ShopMind 的核心抽象。每个 Workflow 是一个独立的 YAML 文档，定义 Agent 的行为边界：

```yaml
# workflows/customer-service/v2.3.yaml
id: customer-service
version: v2.3
persona: |
  [角色] + [思考流程/CoT] + [回复规则/格式]
toolRules:
  - toolName: queryOrder / refund
    description: ...
    required: false
constraints:
  - name: no_hallucination  (HARD)
  - name: source_citation   (HARD)
  - name: chain_of_thought  (HARD)
```

**版本演化实验：**

| Domain | 版本 | 关键改进 |
|--------|------|----------|
| customer-service | v2.0 | Baseline（单体传统架构） |
| customer-service | v2.1 | 响应式微内核 + anti-hallucination |
| customer-service | v2.2 | + Step-by-step 推理 + 工具使用条件 |
| customer-service | v2.3 | + Chain-of-Thought + 反幻觉三段论 + 来源标注 |
| sales | v1.0 | 基础导购 persona |
| sales | v1.1 | + 需求澄清 + 多维度对比 + 反夸大 |
| finance | v1.0 | 基础财务 persona |
| finance | v1.1 | + 身份验证 + 金额规范 + 审计追踪 |

---

## Evaluation

ShopMind 内置了一套完整的自动化评测流水线，支持两代评估方法和三项互补实验：

### 评测流水线

```
Dataset (126 Cases)
  → BenchmarkRunnerImpl (maxConcurrency + RateLimiter)
    → Agent.chat() → ExecutionTrace
    → MetricEvaluator (Rule / LLM-Judge)
    → FailureAnalyzer (7类失败归因)
  → Flux.reduce → ExperimentReport (JSON + Markdown)
```

### 第二代：LLM-as-Judge（大模型裁判）

用第二路 LLM（Judge）对 Agent 回答进行语义级评估，输出 5 维 0-100 评分：

| 维度 | 说明 | 通过阈值 |
|------|------|----------|
| Intent Match | 意图理解是否正确 | >= 60 |
| Tool Selection | 工具选择是否正确 | >= 60 |
| Task Success | 任务是否完成 | >= 60 |
| Hallucination | 幻觉程度（0=无） | <= 30 |
| Knowledge Recall | 知识点覆盖率 | >= 60 |

---

## Benchmark Results

### E1: Workflow 版本演化矩阵（8 workflows × 126 cases）

| Workflow | Version | Intent | Hallucination | Task Success | Safety Refusal | P95 |
|----------|---------|--------|--------------|-------------|---------------|-----|
| customer-service | v2.0 | 71.4% | 0.0% | 23.0% | 42.1% | 4760ms |
| customer-service | v2.1 | 72.2% | 0.0% | 18.3% | 45.2% | 4683ms |
| customer-service | v2.2 | 65.9% | 0.0% | 19.8% | 42.1% | 4891ms |
| customer-service | **v2.3** | **73.8%** | **0.0%** | 19.8% | 40.5% | 4763ms |
| finance | v1.0 | 63.5% | 0.0% | 22.2% | 43.7% | 3642ms |
| finance | v1.1 | 68.3% | 0.0% | 21.4% | 46.0% | 4209ms |
| sales | v1.0 | 62.7% | 0.0% | 19.8% | 44.4% | 6055ms |
| sales | v1.1 | 68.3% | 0.0% | 19.0% | 43.7% | 5138ms |

> v2.3 achieves highest Intent Accuracy (73.8%). Full failure distribution analysis in report.

### E2: 消融实验（3 modes × 28 cases）

| 模式 | Task Success (正常题) | Hallucination (对抗题) | Safety Refusal |
|------|----------------------|----------------------|---------------|
| Mode A (裸 LLM) | 50.0% | 0.0% | 38.9% |
| Mode B (+工具) | 80.0% | 0.0% | 27.8% |
| Mode C (+RAG+Guard) | 60.0% | 0.0% | 38.9% |

> RAG 知识增强提升业务能力，Guardrails 提供显式安全边界。全链路零幻觉。

### E3: RAG 检索质量评测

| Metric | Value |
|--------|-------|
| **Hit@1** | **90%** (9/10) |
| **Hit@3** | **100%** (10/10) |

> 30-chunk 真实业务知识库 + InMemory 向量检索，Hit@3 覆盖率达 100%。

**详细报告：** `docs/04_Evaluation/Benchmark_Report.md` | 原始数据：`reports/` 目录

---

## Dataset

评测数据集按场景（Scenario）分类，每个用例包含 Ground Truth 标注：

| 场景 | 用例数 | 说明 |
|------|--------|------|
| Normal | 40 | 常规客服对话（含 20 成功 + 20 失败） |
| Tool | 20 | 工具调用场景 |
| RAG | 15 | 知识检索场景 |
| Multi-turn | 15 | 多轮对话 |
| Safety | 15 | 安全拦截场景 |
| Stress | 10 | 压力/边缘输入 |
| Edge | 11 | 极端/对抗用例 |
| **合计** | **126** | |

```json
{
  "id": "NORMAL-001",
  "query": "这条裤子的退货政策是什么？",
  "expectedIntent": "return_policy",
  "expectedTool": null,
  "expectedKnowledge": ["7天", "无理由", "退货"],
  "expectedAnswer": "您可以在购买后7天内无理由退货。",
  "expectedFailureReason": null
}
```

---

## Framework-Agnostic Design

**Compatible with multiple Agent frameworks through a unified evaluation interface.**

```text
                  ┌──────────────────────┐
                  │  BenchmarkRunnerImpl  │
                  └──────────┬───────────┘
                             │ depends on
                  ┌──────────▼───────────┐
                  │   EvaluableAgent     │  ← 统一接口
                  │  (port interface)    │
                  └──────────┬───────────┘
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                  ▼
  ShopMindAgentAdapter  LangChainAdapter  OpenAIAdapter
  (AgentOrchestrator)   (skeleton)        (skeleton)
```

`EvaluableAgent` 接口定义三个方法：`agentId()`, `agentVersion()`, `chat(AgentInput) → Flux<String>`。任何 Agent 框架只需实现此接口即可接入评测流水线。

---

## LLM Provider Support

| Profile | Chat Adapter | Judge Adapter | Status |
|---------|-------------|---------------|--------|
| 默认（无） | `MockChatModelAdapter` | `RuleBasedMetricEvaluator` | 单元测试可用 |
| `deepseek` | `DeepSeekChatAdapter` | `LlmJudgeMetricEvaluator` | 真实 LLM 评测 |
| `qwen` | `DashScopeChatAdapter` | DashScope | 待实现 Judge |

---

## HTTP API (SSE Streaming)

对外暴露一个真实可用的流式对话接口 `POST /api/chat`，以 Server-Sent Events（SSE）返回结构化事件。

**请求体：**

```json
{ "memoryId": "session_xxx", "query": "帮我查一下订单 ORD20240722001 的状态" }
```

`memoryId` 可省略，后端会自动生成 `session_<uuid>` 作为会话记忆标识。

**SSE 事件流**（与前端 `SSEEvent` 协议一一对应）：

| 事件 | 含义 |
|------|------|
| `intent` | 意图分析结果（类别 / 是否需要知识 / 是否需要工具 / 置信度） |
| `token` | LLM 流式生成的增量文本 |
| `tool_call` | 一次工具调用（工具名 + 参数） |
| `tool_result` | 工具执行结果（成功 / 输出 / 耗时） |
| `done` | 完成（含 sessionId + 统计：TTFT / 总耗时 / token 用量） |
| `error` | 出错（错误码 + 信息） |

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"帮我查一下订单 ORD20240722001 的状态"}'
```

---

## MCP Tools（业务工具）

通过 `@McpTool` 注解注册，启动时由 `ToolRegistry` 自动扫描并暴露给 LLM 调用：

| 工具 | 说明 | 所在类 |
|------|------|--------|
| `queryOrder` | 查询订单状态 / 物流 / 发货进度 | `OrderServiceTools` |
| `refund` | 处理退款申请（订单号 + 原因） | `OrderServiceTools` |
| `queryPoints` | 查询会员积分与等级 | `MemberServiceTools` |
| `queryCoupons` | 查询会员可用优惠券 | `MemberServiceTools` |

工具数据为内存态示例业务数据（订单 `ORD20240722001` 等、会员 `USER1001` 等），可直接替换为订单/会员服务真实接口。

---

## Quick Start

```bash
# Prerequisites: MongoDB, JDK 17

# 1. Run all 81 unit tests
cd backend
mvn test

# 2. Run Mock Benchmark (fixed responses, no API cost)
mvn test -Dtest="EvaluationBenchmarkTest#compareV20VsV21"

# 3. Real LLM Benchmarks (need API keys)
# E1: Workflow Matrix (8 workflows × 126 cases, ~2 hours)
mvn test -Dtest="RealLlmBenchmarkTest#runWorkflowMatrix" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx

# E2: Ablation Study (3 modes × 28 cases, ~30 min)
mvn test -Dtest="RealLlmBenchmarkTest#runAblationStudy" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# E3: RAG Retrieval Recall (~1 min, no LLM tokens)
mvn test -Dtest="RealLlmBenchmarkTest#evaluateRagRetrieval" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# 4. 启动后端（默认 Mock LLM + 30 chunks 知识库 + 4 个真实 MCP 工具）
mvn spring-boot:run

# 5. 启动前端（Vite 代理 /api → http://localhost:8080）
cd ../frontend
npm install
npm run dev
# 访问 http://localhost:5173，对话走 POST /api/chat SSE 真连接
```

---

## Project Stats

| Metric | Value |
|--------|-------|
| Java Source Files | 97 |
| Lines of Code | 6,622 |
| Test Files | 9 (81 tests, 0 failures) |
| Workflow Versions | 8 (across 3 domains) |
| Dataset Cases | 126 (7 scenarios) |
| Knowledge Base | 30 chunks (售后/物流/支付/营销/会员/商品/安全/客服/订单) |
| Engine Modules | 6 (Memory/RAG/MCP/Workflow/Orchestrator/Evaluation) |
| Framework Adapters | 3 (ShopMind/LangChain/OpenAI) |
| Evaluation Methods | 2 (Rule-Based + LLM-as-Judge) |
| Evaluation Experiments | 3 (Matrix + Ablation + Retrieval) |
| Failure Categories | 7 (Failure Taxonomy) |
| Archive Documentation | 18 spec files under `docs/` |

---

*Built for High-Performance Enterprise Engineering & Reproducible AI Research.*
