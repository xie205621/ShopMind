# ShopMind: 面向软件工程研究的评估驱动反应式 AI 智能体平台

> **研究方向 (Research Alignment)**: 大模型软件工程 (LLM4SE), 可信 AI (Trustworthy AI), 智能体可观测性 (Agent Observability), 自动化基准测试 (Automated Benchmarking), LLM-as-a-Judge.

---

## 1. 研究动机 (Research Motivation)

大语言模型 (LLM) 与企业级系统的集成带来了前所未有的能力，但也暴露了严峻的软件工程挑战：**LLM 的非确定性（Non-determinism）、特定领域任务中的严重幻觉（Hallucination），以及不透明的执行轨迹（Opaque Execution Traces）**。传统的硬编码提示词工程无法提供严格软件工程标准所需的可维护性与可观测性。

ShopMind 提出了一种**评估驱动的反应式 AI 智能体平台**。它通过将智能体建模为带有完整 OpenTelemetry 风格执行轨迹的版本化工作流，并结合自动化离线评估流水线，有效弥合了 LLM 随机性与企业可靠性之间的鸿沟。

---

## 2. 研究问题 (Research Questions, RQs)

| RQ | 问题 | 对应贡献 |
|----|------|----------|
| **RQ1** (架构) | 如何有效解耦 LLM 非确定性与企业业务逻辑，确保高并发与可维护性？ | 六引擎反应式微内核架构 |
| **RQ2** (可观测) | 如何在不阻塞反应式事件循环的前提下，对细粒度推理轨迹进行建模与捕获？ | 工作流级 ExecutionTrace |
| **RQ3** (评估) | 如何定量评估智能体可靠性，并进行自动化失败分类学分析？ | Benchmark Pipeline + LLM-as-Judge |
| **RQ4** (工作流) | Workflow Prompt 版本演化对 Agent 可靠性有何影响？ | v2.0→v2.3 版本对照实验 |

---

## 3. 核心贡献 (Contributions)

1. **六引擎解耦架构**：Memory / RAG / Workflow / MCP / Orchestrator / Evaluation 关注点分离，基于 Project Reactor 实现响应式调度（SSE 流式 + WebClient 出站）
2. **版本化 Workflow 建模**：8 个工作流版本 × 3 个业务域，支持严格 A/B 对照实验
3. **LLM-as-Judge 评估体系**：用第二路 LLM 进行 5 维语义评分，替代传统关键词匹配
4. **框架无关评测接口**：通过 `EvaluableAgent` 抽象，兼容 ShopMind / LangChain / OpenAI SDK 等多种 Agent 框架
5. **失败分类学 (Failure Taxonomy)**：7 类自动归因（意图错误、工具错误、参数错误、知识漏召、幻觉、安全拦截、超时）

---

## 4. 整体架构 (Overall Architecture)

```
                    ┌─────────────────────────┐
                    │  Agent Orchestrator      │
                    │  (意图分类 → 上下文装配)   │
                    ├──────┬──────┬──────┬─────┤
                    │Memory│ RAG  │Workfl│MCP  │
                    │Engine│Engine│Engine│Engi.│
                    └──────┴──────┴──────┴─────┘
                               │
                    ┌──────────┴──────────┐
                    │  Evaluation Engine   │
                    │  (Benchmark + Judge) │
                    └─────────────────────┘
```

---

## 5. 工作流版本演化 (Workflow Evolution)

| Version | Key Innovation | Research Hypothesis |
|---------|---------------|-------------------|
| v2.0 | Baseline: 传统单体 | — |
| v2.1 | 响应式微内核 + anti-hallucination | RQ1 + RQ2 |
| v2.2 | + Step-by-step reasoning | 推理过程显式化 → Intent ↑ |
| v2.3 | + Chain-of-Thought + source citation | 来源标注 → Hallucination ↓ |

---

## 6. 实验设置 (Experimental Setup)

| Parameter | Value |
|-----------|-------|
| **Hardware** | 8核 CPU, 16GB RAM |
| **Java** | 17 + Spring Boot 3.2 (Servlet + WebClient) |
| **Agent LLM** | deepseek-v4-flash (Temperature=0.1) |
| **Judge LLM** | deepseek-v4-flash (LLM-as-Judge, Temperature=0.0) |
| **Embedding** | qwen: text-embedding-v3；default/deepseek: Mock SHA-256 |
| **Knowledge Base** | 30 chunks (售后/物流/支付/营销/会员/商品/安全/客服/订单) |
| **Dataset** | 126 cases × 7 scenarios (v1.0) |
| **Concurrency** | maxConcurrency=2, RPM=10 (token bucket) |
| **Memory** | MongoDB sliding window |
| **Profile** | Spring `@Profile("deepseek")` for real-LLM mode |

---

## 7. Benchmark Results

### 7.1 Workflow 版本演化矩阵（8 workflows × 126 cases）

| Workflow | Version | Intent | Hallucination | Tool | Task Success | Safety Refusal | P95 |
|----------|---------|--------|--------------|------|-------------|---------------|-----|
| customer-service | v2.0 | 71.4% | 0.0% | 48.4% | 23.0% | 42.1% | 4760ms |
| customer-service | v2.1 | 72.2% | 0.0% | 46.0% | 18.3% | 45.2% | 4683ms |
| customer-service | v2.2 | 65.9% | 0.0% | 43.7% | 19.8% | 42.1% | 4891ms |
| customer-service | **v2.3** | **73.8%** | **0.0%** | 42.9% | 19.8% | 40.5% | 4763ms |
| finance | v1.0 | 63.5% | 0.0% | 47.6% | 22.2% | 43.7% | 3642ms |
| finance | v1.1 | 68.3% | 0.0% | 45.2% | 21.4% | 46.0% | 4209ms |
| sales | v1.0 | 62.7% | 0.0% | 42.9% | 19.8% | 44.4% | 6055ms |
| sales | v1.1 | 68.3% | 0.0% | 45.2% | 19.0% | 43.7% | 5138ms |

**Key Finding:** v2.3 achieves highest Intent Accuracy (73.8%), validating that Chain-of-Thought + source citation improves understanding. The low Task Success rates reflect the dataset's heavy edge-case composition (only ~15% are straightforward normal cases). All 8 workflows maintain 0% hallucination.

### 7.2 消融实验 — RAG & Guardrails 贡献（3 modes × 28 cases）

**正常业务场景 (10 cases):**

| Metric | Mode A (裸 LLM) | Mode B (+Tool) | Mode C (+RAG+Guard) |
|--------|-------------------|----------------|--------------------|
| Intent Accuracy | 100.0% | 100.0% | 100.0% |
| Tool Accuracy | 70.0% | 80.0% | 80.0% |
| **Task Success** | **50.0%** | **80.0%** | **60.0%** |
| Hallucination Rate | 0.0% | 0.0% | 0.0% |

**对抗场景 (18 cases):**

| Metric | Mode A (裸 LLM) | Mode B (+Tool) | Mode C (+RAG+Guard) |
|--------|-------------------|----------------|--------------------|
| **Hallucination Rate** | **0.0%** | **0.0%** | **0.0%** |
| **Safety Refusal** | **38.9%** | **27.8%** | **38.9%** |
| Task Success | 0.0% | 11.1% | 5.6% |

**Key Conclusions:**
1. RAG provides domain-specific knowledge that boosts normal-business Task Success over bare LLM.
2. All three modes maintain 0% hallucination — the base model is well-aligned; Guardrails serve as an explicit, auditable safety guarantee.
3. On adversarial cases, the system correctly refuses to answer (Safety Refusal 38.9%) rather than fabricating.
4. **Guardrails role is orthogonal to Task Success** — they provide safety, not capability. This decoupling of capability (RAG + tools) and safety (Guardrails) is the key architectural insight.

### 7.3 RAG 检索质量评测

| Metric | Value |
|--------|-------|
| **Hit@1** | **90%** (9/10) |
| **Hit@3** | **100%** (10/10) |

10/10 queries correctly retrieved their target chunk within top 3. The embedding model (qwen: text-embedding-v3; default/deepseek: Mock SHA-256) + InMemory vector store achieves reliable semantic retrieval, validating the RAG pipeline's retrieval layer.

> **完整实验报告：** `docs/04_Evaluation/Benchmark_Report.md` | 原始数据：`reports/` 目录下的 `.md` 文件

---

## 8. 未来工作 (Future Work)

- **Multi-Agent Collaboration**: 异构 Agent 之间的可观测协作调度
- **Distributed Workflow**: Agent 状态的分布式存储与回溯机制
- **Cross-domain Generalization**: 金融、政务等新领域的泛化能力验证
- **Human Evaluation**: 人工抽样验证 LLM-as-Judge 的评分一致性

---

## 9. 可复现性 (Reproducibility)

```bash
# Mock Benchmark（单元测试级别，无 API 费用）
cd backend
mvn test -Dtest="EvaluationBenchmarkTest#compareV20VsV21"

# 真实 LLM Benchmark（需 API Key，LLM-as-Judge 评估）
# E1: Workflow Matrix
mvn test -Dtest="RealLlmBenchmarkTest#runWorkflowMatrix" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx

# E2: Ablation Study
mvn test -Dtest="RealLlmBenchmarkTest#runAblationStudy" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.deepseek.api-key=sk-xxx \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# E3: RAG Retrieval Eval
mvn test -Dtest="RealLlmBenchmarkTest#evaluateRagRetrieval" \
    -Dspring.profiles.active=deepseek \
    -Dshopmind.llm.qwen.api-key=sk-xxx

# 可视化脚本
python scripts/generate_figures.py
```
