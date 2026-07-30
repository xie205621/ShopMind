# 🏗️ Software Architecture Document (SAD)

**Version**: v1.2 (Phase F + Evaluation Engine)

**Status**: 🔒 **FROZEN (架构已彻底冻结)**

**Target Audience**: 核心开发人员、AI 代码生成器 (Cursor/Claude)、架构评审委员会。 **Purpose**: 定义 ShopMind 平台的全局技术栈、模块依赖拓扑、横切关注点与设计模式，作为向下拆解 Module Specification 的总纲。

## 1. 系统概览 (System Overview)

ShopMind 采用**响应式微内核架构 (Reactive Microkernel Architecture)**。系统以 Agent Orchestrator 为中央事件总线，将记忆、知识、工具抽象为可插拔的外围引擎。平台严格遵循“大模型无状态、无直接执行权”的安全底线，所有状态管理与物理操作均被隔离在具体的底层引擎中。

## 2. 技术选型栈 (Technology Selection)

- **Core Backend**: Java 17 + Spring Boot 3.2
- **Reactive Pipeline**: Spring WebFlux + Project Reactor (保障全链路非阻塞 I/O 与 SSE 推流)
- **LLM Framework**: LangChain4j (SPI 标准化解耦)
- **Data Persistence**: MongoDB (非结构化记忆快照) + MySQL 8.0 (强事务电商业务)
- **Vector Store**: InMemoryEmbeddingStore (Phase 1) -> Qdrant (Phase 2 预留)

## 3. 系统组件与引擎依赖图 (Component & Dependency Diagram)

*(注：引擎之间呈现严格的自上而下单向依赖，禁止循环依赖)*

Plaintext

```
                     [ Client (Web / Mobile) ]
                               │ (SSE Streaming)
                               ▼
                 ┌───────────────────────────┐
                 │    API Gateway & Auth     │
                 └─────────────┬─────────────┘
                               │
            ┌──────────────────▼──────────────────┐
            │   Agent Orchestrator (Core Hub)     │
            └─┬──────────────┬──────────────┬─────┘
              │              │              │
    ┌─────────▼────┐ ┌───────▼──────┐ ┌─────▼─────────┐
    │   Context    │ │  Knowledge   │ │   MCP Tool    │
    │  Management  │ │  Retrieval   │ │   Execution   │
    └──────────────┘ └──────────────┘ └───────────────┘
           │                │                │
      [MongoDB]      [Vector Store]    [Business Service]
                                             │
                                          [MySQL]

                      ══════════════════════════════
                      ▼  (离线评测层 — Phase F v2.3)
            ┌──────────────────────────────┐
            │     Evaluation Engine         │
            │  BenchmarkRunner (Evaluable)  │
            │  ├── RuleBasedEvaluator       │
            │  ├── LlmJudgeEvaluator        │
            │  └── FailureAnalyzer          │
            └──────────────────────────────┘
```

## 4. 核心请求生命周期 (Request Lifecycle & Data Flow)

一次标准的用户对话请求，在系统内部将经历严格的 **8 步处理流水线 (Pipeline)**：

1. **Request Intake**: WebFlux 接收 HTTP 请求，建立 SSE 长连接。
2. **Intent Analysis**: 预判路由（是否触发 RAG/Tool）。
3. **Context Hydration**: 并行调度 Memory 与 Knowledge 引擎获取上下文。
4. **Prompt Assembly**: 动态组装各类 Context 与 System Prompt。
5. **LLM Inference**: 将 Prompt 送入大模型进行推理。
6. **Tool Execution (Inner Loop)**: 若触发 Function Calling，由 MCP 安全拦截并执行，返回 Observation 重定向至步骤 5。
7. **Streaming Response**: 获取纯文本后 Token-by-Token 刷入 SSE 管道。
8. **State Persist**: `@Async` 异步覆写 MongoDB 会话快照，并落盘 Trace 日志。

## 5. 包结构规范 (Package Structure)

严格遵循领域驱动设计 (DDD) 与洋葱架构思想（v1.2 更新实际包结构）：

```
com.shopmind                    ← 根包
├── orchestrator                ← Agent 调度与核心 Pipeline
│   ├── domain                  (OrchestrationRequest, ExecutionStatus)
│   ├── port                    (AgentOrchestrator, ChatModelAdapter)
│   ├── pipeline                (意图分析 / 上下文装配 / 流式调用)
│   └── adapter                 (MockChatModelAdapter, DeepSeekChatAdapter, DashScopeChatAdapter)
├── memory                      ← 上下文记忆引擎
├── knowledge                   ← RAG 知识检索引擎
├── workflow                    ← 工作流引擎 (Definition / Renderer / TraceRecorder)
│   ├── domain                  (WorkflowDefinition, ExecutionTrace, TraceSpan)
│   ├── port                    (WorkflowDefinitionLoader, WorkflowRenderer, TraceRecorder)
│   └── pipeline                (WorkflowRendererImpl, InMemoryTraceRecorder)
├── evaluation                  ← 可信评估引擎 (Phase F v2.3)
│   ├── domain                  (AgentInput, BenchmarkConfig, EvaluationDataset, TestCase, …)
│   ├── port                    (EvaluableAgent, BenchmarkRunner, MetricEvaluator, FailureAnalyzer)
│   ├── pipeline                (BenchmarkRunnerImpl, RuleBasedMetricEvaluator, LlmJudgeMetricEvaluator)
│   ├── adapter                 (ShopMindAgentAdapter, LangChainAgentAdapter, OpenAIAdapter)
│   └── config                  (EvaluationConfig)
└── infrastructure              ← 全局横切关注点与基础设施适配
```

## 6. 横切关注点 (Cross-cutting Concerns)

本架构将非功能性需求（NFRs）统一剥离至 Infrastructure 层，禁止污染核心引擎逻辑：

- **Security & Authentication (鉴权)**：在 Gateway 层统一提取 JWT Token，转化为 `UserId` 向下层传递，各 Engine 无需关心加解密逻辑。
- **Global Exception Handling (全局异常处理)**：通过 `@ControllerAdvice` 统一捕获 `LowSimilarityException` 等业务异常，并将其转换为友好的流式（SSE）降级回复，确保 C 端体验不中断。
- **Observability & Logging (全链路可观测性)**：在 WebFlux 响应式上下文中利用 MDC (Mapped Diagnostic Context) 传递 `traceId` 与 `memoryId`，实现从 API 到 MongoDB/MySQL 调用的全链路 Agent Trace 日志聚合。
- **Caching Strategy (缓存策略)**：引入 Query Cache 层，针对高频重复且不具时效性的 RAG 提问，直接从 Redis (或 Caffeine) 拦截返回，旁路大模型推理以节省 API 成本。

## 7. 核心设计模式应用 (Design Patterns in Practice)

系统的可扩展性与可维护性深度依赖于以下 GoF 设计模式的落地：

- **Adapter Pattern (适配器模式)**：应用于 `ChatModelPort` 与 `VectorStorePort`。系统内部只依赖接口定义，将 OpenAI/DashScope 和 InMemory/Qdrant 的差异性封装在具体的 Adapter 实现中，实现对修改封闭（开闭原则）。`EvaluableAgent` + `ShopMindAgentAdapter` / `LangChainAgentAdapter` / `OpenAIAdapter` 也是此模式的典型应用（Phase F v2.3）。
- **Strategy Pattern (策略模式)**：应用于 RAG 引擎中的 `ChunkingStrategy`，支持基于段落（Paragraph）、按字符定长（Fixed-size）等多种切片算法的运行时无缝切换。
- **Builder Pattern (建造者模式)**：应用于 `PromptAssembler`。大模型 System Prompt 的构建极为复杂，利用 Builder 链式调用（如 `.withRules().withMemory().withRAG().build()`）屏蔽拼装细节。
- **Facade Pattern (外观模式)**：`AgentOrchestrator` 作为整个系统的 Facade，向 API 层屏蔽了底层多个 Engine 并行调用的复杂时序关系。
- **Repository Pattern (仓储模式)**：在 Context Management 引擎中，利用 `ChatSessionRepository` 屏蔽 MongoDB 底层繁琐的 `MongoTemplate` 和 `upsert` 操作细节。