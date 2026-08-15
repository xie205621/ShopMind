# ShopMind 企业级 AI Agent 编排平台

![Version](https://img.shields.io/badge/version-v2.3-blue)
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2_WebFlux-green)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)

**ShopMind** 是一个生产级可信 AI 智能体编排平台，为大模型驱动的业务系统提供可复用的软件工程底座，涵盖工作流编排、长短期记忆、RAG 检索、沙箱工具执行与自动化评测。本项目以电商导购助手作为首个参考实现，同时支持售前导购（sales）和财务助手（finance）等场景。

---

## Why ShopMind?

| 传统大模型应用痛点                    | ShopMind 核心解决方案                                        |
| :------------------------------------ | :----------------------------------------------------------- |
| 提示词与业务代码深度耦合，难以维护     | **Engine Isolation**：业务逻辑与大模型提示词严格隔离，版本化管理 |
| 串行请求导致首字延迟（TTFT）极高       | **Reactive Streaming**：基于 WebFlux 实现全链路异步无阻塞 |
| 黑盒运行，无法解释 Agent 为什么出错    | **Observability**：步骤级 ExecutionTrace 全链路留痕 |
| 大模型自由调用 API 导致严重越权风险    | **Trustworthy MCP**：反射沙箱 + 前置意图路由 + 安全约束 |
| 缺乏评估标准，凭感觉调优 Prompt        | **Built-in Evaluation**：自动化 A/B 测试 + 失败根因分析 |

---

## Architecture

系统采用六引擎反应式微内核架构，严格遵循领域驱动设计（DDD）：

```text
                         ┌─────────────────────────────┐
                         │   Agent Orchestrator        │
                         │  (意图路由 → 上下文装配)      │
                         ├──────┬──────┬──────┬────────┤
                         │Memory│ RAG  │Workfl│  MCP   │
                         │Engine│Engine│Engine│ Engine │
                         └──────┴──────┴──────┴────────┘
                                    │
                         ┌──────────┴──────────┐
                         │  Evaluation Engine   │
                         │ (Benchmark + Judge)  │
                         └─────────────────────┘
```

| Engine | 核心职责 | 技术栈 |
|--------|----------|--------|
| **Orchestrator** | 意图分类 → 上下文装配 → LLM 流式调用 | Reactor / WebFlux / Resilience4j |
| **Memory Engine** | 多租户滑动窗口记忆、持久化画像 | MongoDB / Embedded MongoDB (test) |
| **RAG Engine** | 向量检索 → 阈值熔断 → 知识注入 | InMemory / Qdrant (planned) |
| **Workflow Engine** | 版本化 Persona + ToolRules + Constraints | YAML DSL + SnakeYAML |
| **MCP Engine** | 反射沙箱工具执行、安全路由 | Java Reflection / ToolSpec |
| **Evaluation Engine** | Benchmark Runner + LLM-as-Judge | Reactor Flux / DeepSeek |

---

## Workflow

每个 Workflow 是独立的 YAML 文档，支持严格的 A/B 测试：**8 个版本 × 3 个领域**。

<details>
<summary><b>customer-service (4 versions)</b></summary>

| Version | Key Innovation |
|---------|---------------|
| v2.0 | Baseline: 传统单体架构 |
| v2.1 | 响应式微内核 + anti-hallucination constraint |
| v2.2 | + Step-by-step reasoning + tool usage conditions |
| v2.3 | + Chain-of-Thought + 反幻觉三段论 + source citation |

</details>

<details>
<summary><b>sales (2 versions)</b></summary>

| Version | Key Innovation |
|---------|---------------|
| v1.0 | 基础导购 persona + recommendProduct / checkInventory |
| v1.1 | + 需求澄清 + 多维度对比 + 反夸大约束 |

</details>

<details>
<summary><b>finance (2 versions)</b></summary>

| Version | Key Innovation |
|---------|---------------|
| v1.0 | 基础财务 persona + queryInvoice / checkBalance |
| v1.1 | + 身份验证前置 + 金额标准格式 + 审计追踪 |

</details>

---

## Evaluation

### 评测流水线

```
Dataset (126 Cases × 7 Scenarios)
  → BenchmarkRunnerImpl (maxConcurrency + RPM RateLimiter)
    → Agent.chat() → ExecutionTrace
    → MetricEvaluator → TestCaseResult
    → FailureAnalyzer → FailureReason (7 types)
  → Flux.reduce → ExperimentReport
```

### 两代评估方法

| 方法 | 实现 | 原理 | 适用场景 |
|------|------|------|----------|
| **Rule-Based** | `RuleBasedMetricEvaluator` | 关键词子串匹配 + 字典映射 | 单元测试 / 快速验证 |
| **LLM-as-Judge** | `LlmJudgeMetricEvaluator` | DeepSeek 作为裁判，5 维 0-100 评分 | 真实 LLM 评测 / 论文数据 |

### LLM-as-Judge 评估维度

| 维度 | 说明 | 通过阈值 |
|------|------|----------|
| Intent Match | 意图理解正确性 | >= 60 |
| Tool Selection | 工具选择正确性 | >= 60 |
| Task Success | 任务完成度 | >= 60 |
| Hallucination | 幻觉程度（0 = 无） | <= 30 |
| Knowledge Recall | 知识点覆盖率 | >= 60 |

### 失败分类学 (7 Types)

`WRONG_INTENT` · `WRONG_TOOL` · `WRONG_PARAMETER` · `KNOWLEDGE_MISS` · `HALLUCINATION` · `SAFETY_BLOCKED` · `TIMEOUT`

---

## Benchmark Results

### Workflow 版本演化矩阵（8 workflows × 126 cases, LLM-as-Judge）

| Workflow | Version | Intent | Hallucination | Task Success | Safety Refusal | P95 |
|----------|---------|--------|--------------|-------------|---------------|-----|
| customer-service | v2.0 | 71.4% | 0.0% | 23.0% | 42.1% | 4760ms |
| customer-service | v2.1 | 72.2% | 0.0% | 18.3% | 45.2% | 4683ms |
| customer-service | v2.3 | **73.8%** | **0.0%** | 19.8% | 40.5% | 4763ms |
| finance | v1.1 | 68.3% | 0.0% | 21.4% | 46.0% | 4209ms |
| sales | v1.1 | 68.3% | 0.0% | 19.0% | 43.7% | 5138ms |

### 消融实验：RAG & Guardrails 贡献

| 模式 | Task Success (正常题) | Hallucination (对抗题) | Safety Refusal |
|------|----------------------|----------------------|---------------|
| Mode A (裸 LLM) | 50.0% | 0.0% | 38.9% |
| Mode B (+工具) | 80.0% | 0.0% | 27.8% |
| Mode C (+RAG+Guard) | 60.0% | 0.0% | 38.9% |

### RAG 检索质量

| Metric | Value |
|--------|-------|
| Hit@1 | 90% (9/10) |
| Hit@3 | 100% (10/10) |

> 完整报告：[docs/04_Evaluation/Benchmark_Report.md](docs/04_Evaluation/Benchmark_Report.md)

---

## Dataset

| Scenario | Cases | Category |
|----------|-------|----------|
| Normal | 40 | 常规客服对话 |
| Tool | 20 | 工具调用 |
| RAG | 15 | 知识检索 |
| Multi-turn | 15 | 多轮对话 |
| Safety | 15 | 安全拦截 |
| Stress | 10 | 压力测试 |
| Edge | 11 | 边缘用例 |
| **Total** | **126** | |

---

## Framework-Agnostic

**Compatible with multiple Agent frameworks through a unified evaluation interface.**

```text
BenchmarkRunnerImpl
       │
       ▼
  EvaluableAgent (port)
       │
  ┌────┼────┬───────────────┐
  ▼    ▼    ▼               ▼
ShopMind LangChain OpenAI  (extensible)
Adapter  Adapter  Adapter
```

---

## Quick Start

```bash
# 1. 运行全部 81 个单元测试
cd backend && mvn test

# 2. Mock Benchmark（无 API 费用）
mvn test -Dtest="EvaluationBenchmarkTest#compareV20VsV21"

# 3. 真实 LLM Benchmark（需 API Key）
# E1: Workflow Matrix
mvn test -Dtest="RealLlmBenchmarkTest#runWorkflowMatrix" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx

# E2: Ablation Study
mvn test -Dtest="RealLlmBenchmarkTest#runAblationStudy" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# E3: RAG Retrieval Evaluation
mvn test -Dtest="RealLlmBenchmarkTest#evaluateRagRetrieval" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# 4. 启动后端（默认 Mock LLM + 30 chunks 知识库 + 4 个真实 MCP 工具）
mvn spring-boot:run
# 对话接口：POST http://localhost:8080/api/chat（SSE）

# 5. 启动前端（Vite 代理 /api → http://localhost:8080）
cd ../frontend
npm install
npm run dev
# 访问 http://localhost:5173
```

---

## Project Stats

| Metric | Value |
|--------|-------|
| Java Source Files | 97 |
| Lines of Code | 6,622 |
| Test Files | 9 (81 tests) |
| Workflow Versions | 8 (3 domains) |
| Dataset Cases | 126 (7 scenarios) |
| Knowledge Base | 30 chunks |
| Engine Modules | 6 |
| Framework Adapters | 3 |
| Evaluation Methods | 2 |
| Evaluation Experiments | 3 (Matrix + Ablation + Retrieval) |
| Failure Categories | 7 |

---

## LLM Provider Support

| Profile | Chat | Embedding | Judge |
|---------|------|-----------|-------|
| 默认 | Mock | Mock | Rule-Based |
| `deepseek` | DeepSeek (chat/reasoner) | Mock | LLM-as-Judge (DeepSeek) |
| `qwen` | Qwen (DashScope) | text-embedding-v3 | (WIP) |

---

## HTTP API & Real Tools

对话通过真实 SSE 接口 `POST /api/chat` 暴露，前端经 Vite 代理 `/api → http://localhost:8080` 直接连接，无 Mock 层。

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"帮我查一下订单 ORD20240722001 的状态"}'
```

内置 4 个 `@McpTool` 业务工具（启动时由 `ToolRegistry` 自动扫描注册）：`queryOrder`、`refund`、`queryPoints`、`queryCoupons`。工具数据为内存态示例业务数据，可替换为订单/会员服务真实接口。

---

*Built for High-Performance Enterprise Engineering & Reproducible AI Research.*
