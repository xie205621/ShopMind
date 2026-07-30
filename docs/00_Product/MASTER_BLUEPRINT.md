# 🧭 ShopMind: Master Project Blueprint (项目全局总蓝图)

**Document Status**: 🔒 **FROZEN (架构与定位已冻结)**

**Version**: v2.3 (Framework-Agnostic + LLM-as-Judge)

**Single Source of Truth**: 本文档是 ShopMind 项目的唯一真相源。无论后续输出企业版 README 还是科研版 README，所有关于定位、引擎职责、研究问题和演进路线的描述，均需从本文档中严格抽取，禁止发生概念分叉。

## 1. 终极定位 (Ultimate Positioning)

**ShopMind: A Research-oriented Enterprise Trustworthy AI Agent Software Engineering Platform** （面向企业业务场景的可信 AI Agent 软件工程与研究平台）

**核心叙事：** 本项目并非一个单纯的“电商聊天机器人”，而是致力于研究**大语言模型（LLM）在企业级复杂业务系统中落地的软件工程方法论**。通过对 Agent 编排、长短期上下文管理、知识增强防幻觉、安全沙箱工具调用等核心机制进行模块化建模，为构建高可用、可解释、可信赖的 AI 应用提供企业级基础设施支撑。

## 2. 研究问题 (Research Questions - RQs)

本平台的架构设计紧密围绕以下五个核心大模型软件工程（LLM4SE）研究问题展开：

- **RQ1 (Trustworthy Execution):** How can LLM Agents reliably invoke enterprise tools without unsafe execution or unauthorized data modification? (对应 MCP 引擎与沙箱安全)
- **RQ2 (Knowledge Injection):** How can enterprise private knowledge be injected into LLMs dynamically while minimizing hallucination rates? (对应 知识检索引擎)
- **RQ3 (Context Modeling):** How should long-term user profiles and short-term session memory cooperate to support complex workflow reasoning? (对应 上下文管理引擎)
- **RQ4 (Agent Evaluation):** How can Agent execution traces and tool-calling accuracy be evaluated quantitatively and automatically? Can LLM-as-Judge replace rule-based keyword matching for semantic evaluation? (对应 可信评测引擎)
- **RQ5 (Workflow Scalability):** How should enterprise AI workflows be modeled to support maintainability, observability, and scalability? How do incremental prompt engineering improvements (v2.1 → v2.2 → v2.3) affect agent reliability? (对应 工作流编排引擎)
- **RQ6 (Framework Agnostic):** How can a unified evaluation interface decouple benchmarking from specific agent framework implementations? (对应 框架无关评测 — Phase F)

## 3. 架构设计原则 (Architecture Principles)

系统的代码落地严格遵循以下软件工程与系统架构原则：

- **Provider Abstraction (提供商抽象)**：使用 Adapter 模式彻底解耦底层 LLM、Vector Store 和 Cache 提供商，避免被单一厂商锁定。
- **Reactive Pipeline (响应式管道)**：全面采用 WebFlux 非阻塞 I/O，保障长耗时大模型推理下的高并发吞吐量。
- **Engine Isolation (引擎级隔离)**：各引擎职责绝对单一，通过统一定义的 Context 接口进行数据流转，降低系统耦合度。
- **Trust-First Design (可信优先)**：所有业务状态变更（如扣款、下单）必须经过底层防重锁与参数校验，LLM 仅具备“意图提议权”，无直接执行权。
- **Evaluation-Driven (评测驱动)**：所有的 Prompt 优化与架构调整，必须以评测引擎输出的量化 Benchmark 数据为依据。

## 4. 六大核心引擎 (The 6 Core Engines)

系统底层已被永久解构为以下六个标准学术/工程引擎：

1. **🧠 Agent Orchestrator (智能体编排中心)**：
   - 职责：全局调度中枢，负责意图分析 (Intent Analysis)、内外双循环控制与流式结果下发。
2. **📂 Context Management Engine (上下文管理引擎)**：
   - 职责：负责 Session Memory (滑动窗口截断) 与 Long-term Memory (向量化偏好提取) 的多租户绝对隔离与持久化。
3. **📚 Knowledge Retrieval Engine (知识检索引擎)**：
   - 职责：负责完整 Retrieval Pipeline (Cache -> Embedding -> Vector Search -> Threshold Filter)，实现私域知识的高置信度召回。
4. **🛠️ MCP Tool Execution Engine (模型上下文与工具执行引擎)**：
   - 职责：构建沙箱级的企业工具注册中心 (Tool Registry)，处理从自然语言到强类型 Java 接口的反射调用与防范越权执行。
5. **📏 Trustworthy Evaluation Engine (可信评估引擎)**：
   - 职责：提供针对 Recall@K、Tool Accuracy、TTFT 与 Hallucination Rate 的自动化基准测试 (Benchmark) 数据生成。
6. **🔄 Workflow Orchestration Engine (工作流与可观测引擎)**：
   - 职责：提供 Builder 模式的 Prompt 动态构建，并实现 Agent Trace (执行链路留痕)，提升 AI 决策的全局可解释性。

## 5. 三级演进路线 (Three-Tier Roadmap)

- **Tier 1: Engineering Validation (工程落地验证期 - ✅ 已完成)**
  - 成果：完成六大引擎的架构封板与代码落地（97 源文件 / 6,622 行 / 19 包 / 18 接口）。
  - 成果：实装 Evaluation Engine + LLM-as-Judge + Framework-Agnostic 评测接口。
  - 成果：8 个 Workflow 版本 × 3 业务域，126 测试用例的自动化 A/B 对比实验。
- **Tier 2: Academic Potential (学术潜力展现期 - ✅ 已完成)**
  - 成果：DeepSeek 真实 LLM 驱动 Benchmark（LLM-as-Judge 5 维语义评分，8 Workflow × 126 Case 全量跑完）。
  - 成果：消融实验（3 模式 × 28 用例）量化 RAG 知识增强与 Guardrails 安全边界贡献。
  - 成果：RAG 检索质量评测（Hit@1 90%，Hit@3 100%）验证语义检索层可靠性。
  - 成果：3 种 Agent 框架适配器（ShopMind / LangChain / OpenAI SDK）统一评测接口。
  - 成果：Agent Trace 全链路留痕 + 7 类失败自动归因。
- **Tier 3: Platform Precipitation (研究平台沉淀期 - 读研攻坚阶段)**
  - 目标：利用本平台进行不同 RAG 策略的幻觉率对比实验、多 Agent 协作的安全边界测试，沉淀为高质量学术论文的实验底座。