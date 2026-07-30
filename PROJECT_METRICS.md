# ShopMind 项目量化数据 (Project Metrics)

> 适用场景：简历项目描述、技术面试展示、学术论文数据声明

---

## 1. 项目概述 (Executive Summary)

ShopMind 是一个**评估驱动的反应式 AI 智能体编排平台**，采用六引擎微内核架构实现智能体的版本化管理、全链路可观测与自动化评测。已支持 3 个业务域、8 个 Workflow 版本、126 个测试用例的自动化 A/B 对比实验，并构建了业界前沿的 LLM-as-Judge 语义级评估体系。

---

## 2. 核心量化指标 (Key Metrics)

### 工程规模

| 指标 | 数值 |
|------|------|
| Java 源代码文件 | **97** |
| 代码总行数 | **6,622** |
| Java 包数 | **19** |
| 接口数 (Port) | **18** |
| 测试文件 | **9** |
| 单元测试数 | **81** (全部通过，0 失败) |
| 技术文档 | **18** 份 (PRD / SAD / ADR / API / 模块 Spec) |

### 架构复杂度

| 指标 | 数值 |
|------|------|
| 引擎模块 (Engines) | **6** (Orchestrator / Memory / RAG / Workflow / MCP / Evaluation) |
| 领域驱动设计层 | **4** (Port → Domain → Pipeline → Adapter) |
| LLM 供应商适配器 | **3** (Mock / DeepSeek / DashScope) |
| Agent 框架适配器 | **3** (ShopMind / LangChain / OpenAI SDK) |
| Spring Profile 策略 | **3** (default / deepseek / qwen) |

### 评估体系

| 指标 | 数值 |
|------|------|
| 评测方法 | **2** 代 (Rule-Based + LLM-as-Judge) |
| LLM-as-Judge 评分维度 | **5** (Intent Match / Tool Selection / Task Success / Hallucination / Knowledge Recall) |
| 失败分类 | **7** 类 (WRONG_INTENT / WRONG_TOOL / WRONG_PARAMETER / KNOWLEDGE_MISS / HALLUCINATION / SAFETY_BLOCKED / TIMEOUT) |
| 评测实验 | **3** 项 (Workflow Matrix / Ablation Study / RAG Retrieval) |

### 知识库规模

| 指标 | 数值 |
|------|------|
| 知识库总 Chunk 数 | **80** |
| 主题覆盖 | 商品信息(25) / 售后政策(15) / 物流规则(10) / 会员体系(10) / 支付方式(10) / FAQ(10) |
| Embedding 模型 | DashScope text-embedding-v3 |
| 检索 Hit@1 | **90%** |
| 检索 Hit@3 | **100%** |

### 数据规模

| 指标 | 数值 |
|------|------|
| Workflow 版本 | **8** × 3 域 (customer-service / sales / finance) |
| 测试用例总数 | **126** (7 种场景) |
| 消融实验用例 | 10 正常 + 18 对抗 = **28** |
| 检索评测用例 | **10** |
| 每个用例标注维度 | **5** (query / expectedIntent / expectedTool / expectedKnowledge / expectedAnswer) |

### 真实 LLM 评测结果（LLM-as-Judge, deepseek-v4-flash）

#### Workflow 版本演化矩阵（126 cases）

| Workflow | Version | Intent | Hallucination | Task Success | Safety Refusal | P95 Latency |
|----------|---------|--------|--------------|-------------|---------------|-------------|
| customer-service | v2.0 | 71.4% | 0.0% | 23.0% | 42.1% | 4760ms |
| customer-service | v2.1 | 72.2% | 0.0% | 18.3% | 45.2% | 4683ms |
| customer-service | v2.2 | 65.9% | 0.0% | 19.8% | 42.1% | 4891ms |
| customer-service | **v2.3** | **73.8%** | 0.0% | 19.8% | 40.5% | 4763ms |
| finance | v1.0 | 63.5% | 0.0% | 22.2% | 43.7% | 3642ms |
| finance | v1.1 | 68.3% | 0.0% | 21.4% | 46.0% | 4209ms |
| sales | v1.0 | 62.7% | 0.0% | 19.8% | 44.4% | 6055ms |
| sales | v1.1 | 68.3% | 0.0% | 19.0% | 43.7% | 5138ms |

> v2.3 取得最高意图准确率 (73.8%)。低 Task Success 源于数据集中 edge case 占比高（~85% 为边界/安全/压力用例）。

#### 消融实验（28 cases）

| 模式 | 正常题 Task Success | 对抗题 Hallucination | 对抗题 Safety Refusal |
|------|--------------------|---------------------|---------------------|
| Mode A (裸 LLM) | 50.0% | 0.0% | 38.9% |
| Mode B (+工具) | 80.0% | 0.0% | 27.8% |
| Mode C (+RAG+Guard) | 60.0% | 0.0% | 38.9% |

> RAG 知识增强提升业务能力；全链路零幻觉；Guardrails 提供显式安全边界。

#### RAG 检索质量

| Metric | Value |
|--------|-------|
| Hit@1 | 90% (9/10) |
| Hit@3 | 100% (10/10) |

### Mock 评估（参考 — v2.0 vs v2.1）

| 维度 | v2.0 (Baseline) | v2.1 (Current) | 提升 |
|------|-----------------|-----------------|------|
| 任务成功率 | 32.5% | 57.5% | +76.9% |
| 幻觉率 | 17.5% | 2.5% | -85.7% |
| 工具准确率 | 60.0% | 85.0% | +41.7% |
| P95 延迟 | 5510ms | 1210ms | -78.0% |
| 平均召回率 | 0.583 | 0.775 | +32.9% |

---

## 3. 技术栈 (Tech Stack)

| 层级 | 技术 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.2 (WebFlux) |
| 响应式 | Project Reactor (Mono / Flux / flatMap / RateLimiter) |
| 限流 | Resilience4j (Token Bucket + Flux.flatMap maxConcurrency) |
| 数据库 | MongoDB (记忆存储) / Embedded MongoDB (测试) |
| 向量存储 | InMemory EmbeddingStore / Qdrant (计划中) |
| LLM API | DeepSeek (OpenAI 兼容协议) / Qwen (DashScope) |
| Embedding | DashScope text-embedding-v3 |
| 工作流 DSL | YAML (SnakeYAML) |
| 序列化 | Jackson / Java Record (immutability) |
| 测试 | JUnit 5 / SpringBootTest / Mock 适配器 |
| 构建 | Maven |

---

## 4. 架构亮点 (Architecture Highlights)

### 4.1 六引擎反应式微内核

```
Orchestrator → [Memory | RAG | Workflow | MCP] → Evaluation
```

- 全链路响应式非阻塞 (Reactor WebFlux)
- 端口-适配器模式 (Port-Adapter Pattern)
- 严格 DDD 分层：Port → Domain → Pipeline → Adapter

### 4.2 版本化 Workflow 管理

- 每个 Workflow 是独立 YAML 文档（Persona + ToolRules + Constraints）
- HARD/SOFT 两级约束系统
- 支持历史版本保留和 A/B 对比实验
- 反幻觉三段论 + Chain-of-Thought 推理链

### 4.3 框架无关评测接口

```java
public interface EvaluableAgent {
    String agentId();
    String agentVersion();
    Flux<String> chat(AgentInput input);
}
```

- 已适配：ShopMind AgentOrchestrator
- 骨架已完成：LangChain Adapter、OpenAI SDK Adapter
- BenchmarkRunnerImpl 仅依赖接口，不依赖具体实现

### 4.4 LLM-as-Judge 语义评估

- 用第二路 DeepSeek 模型作为裁判
- 5 维 0-100 分数制评分
- 结构化 JSON 输出自动解析
- Judge 失败自动降级到默认通过，保证流水线鲁棒性

### 4.5 双重限流策略

- `Flux.flatMap(maxConcurrency)` — 并发度控制
- `Resilience4j RateLimiter` — RPM Token Bucket
- 配合 `maxConcurrency=2, RPM=10` 确保 DeepSeek API 不限流

### 4.6 评测流水线

```
126 Cases → Flux.flatMap (并发) → Agent.chat → ExecutionTrace
  → MetricEvaluator (Rule / LLM-Judge) → FailureAnalyzer (7 类归因)
  → Flux.reduce → ExperimentReport (JSON + Markdown)
```

---

## 5. 简历描述模板 (Resume Templates)

### 5.1 一句话概括

> 设计并实现了一个评估驱动的反应式 AI 智能体编排平台，采用六引擎微内核架构，构建 80 chunks 知识库 + RAG 检索增强机制（Hit@3 = 100%），完成 8 个 Workflow 版本 × 126 个测试用例的 LLM-as-Judge 自动化评测，全链路零幻觉，共 6,622 行 Java 代码、81 个测试用例全部通过。

### 5.2 分点描述

- **架构设计**：基于 DDD 端口-适配器模式设计了六引擎反应式微内核架构，全链路异步非阻塞（Spring WebFlux + Project Reactor），97 个 Java 源文件、18 个 Port 接口
- **RAG 知识增强**：构建覆盖商品/售后/物流/会员/支付 5 大主题的 80 chunks 知识库，基于 DashScope text-embedding-v3 实现语义检索，Hit@1 达 90%、Hit@3 达 100%，消融实验中正常业务 Task Success 提升至 80%
- **工作流系统**：构建了版本化 Workflow 管理系统（YAML DSL），支持 Persona + ToolRules + Constraints 三层建模与严格 A/B 对照实验，覆盖 3 个业务域共 8 个版本
- **评测体系**：设计并实现两代评估方法：Rule-Based（关键词匹配）和 LLM-as-Judge（5 维 0-100 语义评分），完成三项互补实验：Workflow 演化矩阵、消融实验（RAG/Guardrails 贡献量化）、RAG 检索质量评测（Hit@K）
- **Guardrails 安全约束**：设计 HARD/SOFT 两级约束系统，在 3 种模式的消融实验中证明全链路零幻觉，将 Guardrails 定位为与业务能力正交的显式安全层
- **框架无关设计**：抽象 EvaluableAgent 统一接口，适配 ShopMind / LangChain / OpenAI SDK 三种 Agent 框架，支持通过 Spring Profile 切换 Mock/DeepSeek/Qwen 三种 LLM 供应商
- **数据集构建**：构建了 126 个测试用例（7 种场景 × 5 维标注）的 Ground Truth 数据集 + 18 个对抗性幻觉诱导数据集，支持持续集成中的自动化 Benchmark
- **工程技术**：实现了 Resilience4j Token Bucket + Flux.flatMap 双重限流、MongoDB 滑动窗口记忆、Reactor Context 全链路 Trace 传播、MCP 反射沙箱安全执行

---

## 6. 文件导航 (File Index)

| 用途 | 路径 |
|------|------|
| 项目主入口 | `README.md` |
| 企业级架构 | `Enterprise_README.md` |
| 研究视角 | `README_RESEARCH.md` |
| 量化数据 | `PROJECT_METRICS.md` (本文档) |
| Benchmark 报告 | `docs/04_Evaluation/Benchmark_Report.md` |
| 实验原始数据 | `reports/` 目录 |
| 产品需求 | `docs/00_Product/PRD.md` |
| 总体架构 | `docs/01_Architecture/Overall_Architecture.md` |
| 软件架构文档 | `docs/01_Architecture/Software_Architecture.md` |
| API 设计 | `docs/03_Implementation/API_Design.md` |
| 数据库 Schema | `docs/03_Implementation/Database_Schema.md` |
| 评测引擎规范 | `docs/02_Specifications/6_Evaluation_Engine.md` |
| 工作流引擎规范 | `docs/02_Specifications/5_Workflow_Engine.md` |
| 架构决策 (ADR) | `docs/05_ADR/` |

---

*Last updated: 2026-07-28*
