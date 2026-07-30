# Module: Workflow Orchestration & Observability Engine (工作流与可观测引擎)

**Version**: v2.1 (Architecture Review Fixes Applied)

**Status**: 📝 Draft -> [x] Review -> [x] Approved -> [x] Implemented -> [x] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:**
>
> This document is the single source of truth for implementing the Workflow Engine. Any AI-generated code MUST strictly follow this specification.
>
> **Architecture Review Prompt:**
>
> Review this specification from the perspective of LLM for Software Engineering (LLM4SE), Domain-Driven Design (DDD), OpenTelemetry Observability, and Reactive Thread Safety. Do not generate code until the review is passed.
>
> **v2.1 Review Fixes (2026-07-23):**
> - ✅ **Critical**: TraceRecorder 重构为响应式 TraceHandle 模式，消除 Map 内存泄漏
> - ✅ **High**: WorkflowDefinition 与 WorkflowInstance 拆分（Definition-Time vs Runtime）
> - ✅ **Medium**: 补全 ToolRule / Policy 类型定义
> - ✅ **Medium**: 明确 Reactor ContextView 传播 TraceId 方案
> - ✅ **Low**: 移除 WorkflowDefinition 中的 history 字段（Memory Engine 职责）

## 1. Overview (模块概述)

Workflow Orchestration & Observability Engine (简称 Workflow Engine) 是 ShopMind 平台的"建模定义与数据黑匣子"。它摒弃了传统的硬编码 Prompt 拼接，将企业 AI 运行过程抽象为可维护的软件工程模型。

引擎包含三大核心子模块：

1. **Workflow Definition (工作流定义)**：将角色、记忆、知识、工具、企业策略统一建模，支持版本化管理。
2. **Workflow Renderer (工作流渲染)**：将结构化的 Definition + Instance 安全渲染为最终的大模型输入。
3. **Execution Trace (执行留痕)**：无侵入记录 Agent 全生命周期推理轨迹，输出标准化的可观测指标（Metrics），为 Evaluation Engine 提供基准数据。

## 2. Research Alignment (科研对齐)

本引擎直接服务于可信 AI 与大模型软件工程（LLM4SE）的学术研究：

- **Research Questions:**
  - **RQ5**: How to model maintainable and version-controlled AI workflows in enterprise environments?
  - **RQ6**: How to achieve fine-grained explainability (Reasoning Trace) in multi-step Agent executions?
- **Contributions:**
  - **Workflow Modeling**: 提出一种解耦提示词模板与企业策略 (Policy) 的工作流定义规范。
  - **Traceability**: 构建符合 OpenTelemetry 思想的 Agent Execution Trace 体系，支持从输入到输出的完全复现与归因。

## 3. Functional Requirement (功能需求)

- **Workflow Modeling (建模与版本化)**：支持 `WorkflowDefinition` 的版本控制，使得在 Evaluation 阶段能够对 `Workflow v1` 与 `Workflow v2` 进行严谨的 A/B 测试对比。
- **Policy Management (安全策略约束)**：在工作流定义中显式注入 `Policy`（如：禁止泄露进货价、严禁绕过沙箱支付），确保模型行为符合企业底线。
- **Reasoning Trace (推理链路留痕)**：记录 Intent -> Memory -> RAG -> Planning -> Tool -> Answer 的每一步耗时、输入输出与置信度。
- **Observability Metrics (可观测度量)**：在 Trace 结束时自动聚合核心指标（Prompt Length, Retrieved Chunks, Tool Calls, TTFT, Total Tokens），供 Evaluation 引擎直接消费。

## 4. Non-functional Requirement (性能要求)

- **低开销 (Low Overhead)**：Trace 的采集、聚合与异步落盘对主干业务链路（Agent Orchestrator）的性能损耗必须 `< 5ms`（以 JMH 微基准 p99 延迟差为度量标准）。
- **响应式上下文 (Reactive Context)**：在 WebFlux 高并发环境下，`traceId` 与 `TraceHandle` 必须通过 Reactor `ContextView` 严格透传，杜绝并发串流。

## 5. Constraints (约束)

**必须实现 (MUST)：**

- [x] `WorkflowDefinition` 为不可变对象（`record`），所有状态变更通过 `WorkflowBuilder` 产生新实例。
- [x] Trace 数据入库必须使用异步非阻塞驱动（如 Reactive MongoDB）。
- [x] **v2.1**: `TraceRecorder` 采用 `TraceHandle` 响应式模式——Trace 状态完全在请求级对象内部持有，绝不使用全局 Map 累积。
- [x] **v2.1**: `WorkflowDefinition`（静态定义层）与 `WorkflowInstance`（运行时实例层）严格分离。
- [x] **v2.1**: 任何 Flux cancel / error / complete 路径都必须通过 `doFinally` 确保 `TraceHandle` 落盘。

**绝对禁止 (MUST NOT)：**

- [ ] 绝对禁止在 `WorkflowRenderer` 中包含任何数据库查询或 API 调用的业务逻辑（严格分离数据准备与数据渲染）。
- [ ] **v2.1**: 绝对禁止 `TraceRecorder` 内部持有 `Map<String, ExecutionTrace>` 等全局请求级状态容器。
- [ ] **v2.1**: 绝对禁止将运行时数据（`RetrievedContext`, `ChatMessage` history）放入 `WorkflowDefinition`。

## 6. Architecture & Data Model (架构与数据模型)

### 6.1 DDD 分层模型（v2.1 核心修复）

严格区分 **Definition-Time** 与 **Runtime** 两个 DDD 概念：

```
┌─────────────────────────────────────────────────────────┐
│  Definition-Time (部署时配置，存储于 DB/YAML/Git)        │
│                                                         │
│  WorkflowDefinition (record)                            │
│    ├── id: String                                       │
│    ├── version: String        (e.g., "v1.2.0")          │
│    ├── persona: String        (角色 System Prompt)      │
│    ├── toolRules: List<ToolRule>                        │
│    └── constraints: List<Policy>  (安全合规约束)          │
│                                                         │
│  WorkflowBuilder (interface)                             │
│    └── build step-by-step → WorkflowDefinition           │
└─────────────────────────────────────────────────────────┘
                            │
                            │  + runtime data
                            ▼
┌─────────────────────────────────────────────────────────┐
│  Runtime Instance (每次请求动态构建，随后销毁)            │
│                                                         │
│  WorkflowInstance (record)                              │
│    ├── definition: WorkflowDefinition  (关联定义版本)    │
│    ├── history: List<ChatMessage>     (Memory Engine)   │
│    ├── knowledge: RetrievedContext    (RAG Engine)      │
│    └── currentUserMessage: String                       │
│                                                         │
│  → 传入 WorkflowRenderer.render()                       │
└─────────────────────────────────────────────────────────┘
```

### 6.2 ToolRule & Policy 类型定义（v2.1 新增）

```java
record ToolRule(
    String toolName,       // 对应 @McpTool.name()
    String description,    // 给 LLM 看的使用说明
    boolean required       // 是否必须（如支付场景的 confirmPayment）
) {}

record Policy(
    String name,           // 策略名称，如 "禁止泄露进货价"
    String content,        // 注入 System Prompt 的具体约束文本
    PolicyLevel level      // HARD / SOFT
) {}

enum PolicyLevel {
    HARD,    // 必须在输出层阻断（如沙箱支付校验）
    SOFT     // 仅作为 Prompt 约束注入
}
```

### 6.3 Execution Trace 领域模型

符合 OpenTelemetry 思想的执行追踪与指标聚合模型。**v2.1 采用请求级 TraceHandle 持有状态，无全局 Map。**

```java
class ExecutionTrace {
    private String traceId;          // UUID
    private String memoryId;         // 会话 ID
    private String workflowVersion;  // 关联的工作流版本
    private ObservabilityMetrics metrics;
    private List<TraceSpan> spans;
    private Instant startTime;       // v2.1 新增
    private Instant endTime;         // v2.1 新增
    private ExecutionStatus status;  // v2.1 新增
}

class ObservabilityMetrics {
    private long totalLatencyMs;          // 端到端延迟
    private long ttftMs;                  // 首字延迟
    private int promptTokens;             // 输入 Token
    private int completionTokens;         // 输出 Token
    private int retrievedChunksCount;     // 知识命中块数
    private int toolCallCount;            // 工具调用次数
}

class TraceSpan {
    private String stepName;            // "INTENT_ANALYSIS", "KNOWLEDGE_RETRIEVAL" 等
    private long latencyMs;             // 步骤耗时
    private Map<String, Object> input;  // 结构化输入
    private Map<String, Object> output; // 结构化输出
    private double confidence;          // 步骤置信度或分数
    private Instant timestamp;          // v2.1 新增
}
```

## 7. API Design (内部接口)

### 7.1 Workflow Definition Layer

```java
public interface WorkflowBuilder {
    WorkflowBuilder id(String id);
    WorkflowBuilder version(String version);
    WorkflowBuilder persona(String persona);
    WorkflowBuilder toolRules(List<ToolRule> rules);
    WorkflowBuilder addToolRule(ToolRule rule);
    WorkflowBuilder addPolicy(Policy policy);
    WorkflowBuilder constraints(List<Policy> constraints);
    WorkflowDefinition build();
}
```

### 7.2 Workflow Rendering Layer

```java
public interface WorkflowRenderer {
    /**
     * 将 WorkflowInstance 渲染为 LLM 可直接消费的 Prompt 字符串。
     * 纯函数，无副作用，禁止包含 DB/API 调用。
     */
    String render(WorkflowInstance instance);
}
```

### 7.3 Observability Layer（v2.1 重构）

```java
public interface TraceRecorder {

    /**
     * 创建一个请求级 TraceHandle。
     * TraceHandle 是每次请求 new 出来的独立对象，所有 Trace 状态在其内部自包含。
     * 不存在跨请求共享的 Map，彻底消除 WebFlux 并发下的内存泄漏风险。
     *
     * @param memoryId        会话 ID
     * @param workflowVersion 关联的工作流版本
     * @return 新创建的 TraceHandle
     */
    TraceHandle createTrace(String memoryId, String workflowVersion);

    /**
     * 异步落盘，返回 Mono<Void> 支持 Reactive MongoDB 写入。
     * 调用方应在 Flux/Mono 的 doFinally 中调用此方法，确保 cancel/error/complete 全路径覆盖。
     */
    Mono<Void> save(TraceHandle trace);

    /**
     * 请求级 Trace 句柄——响应式安全。
     * <p>
     * 每个请求 new 一个实例，通过 Reactor ContextView 在操作符链中透传。
     * 不依赖任何外部共享状态，即使 Flux 被 cancel 也不会泄漏。
     */
    interface TraceHandle {

        /** 添加一个执行步骤 Span */
        void addSpan(String stepName, long latencyMs,
                     Map<String, Object> input,
                     Map<String, Object> output,
                     double confidence);

        /** 设置聚合指标 */
        void setMetrics(ObservabilityMetrics metrics);

        /** 获取当前聚合指标（用于逐步累加 token 数等） */
        ObservabilityMetrics getMetrics();

        /** 获取 TraceId */
        String getTraceId();

        /** 获取已记录的 Span 列表（用于最终落盘前的序列化） */
        List<TraceSpan> getSpans();

        /** 标记 Trace 结束 */
        void markComplete(ExecutionStatus status);
    }
}
```

## 8. Exception Handling (异常处理)

- **`WorkflowRenderException`**：工作流定义中缺少必填字段（如 Persona）或模板渲染引擎故障。**降级策略**：终止 LLM 请求，返回系统异常提示。
- **`TraceSaveException`**：MongoDB 异步写入失败。**降级策略**：捕获异常，转储到本地 Error 日志，**绝对禁止阻断用户的正常对话流**。

## 9. Integration with Agent Orchestrator (v2.1 新增)

`TraceHandle` 在 `ShopAgentOrchestrator.chat()` 中的集成方式：

```java
// 入口：创建 TraceHandle 并通过 Reactor Context 透传
public Flux<String> chat(OrchestrationRequest request) {
    OrchestrationContext ctx = new OrchestrationContext(request.memoryId(), request.userMessage());
    TraceHandle trace = traceRecorder.createTrace(request.memoryId(), workflowVersion);

    return Mono.just(ctx)
            .flatMap(this::stepIntentAnalysis)
            // ... pipeline steps ...
            .flatMapMany(this::executeWithToolLoop)
            .onBackpressureBuffer(...)
            .onErrorResume(this::degradeFlux)
            // 全路径落盘保证
            .doFinally(signalType -> {
                trace.markComplete(mapSignalToStatus(signalType));
                traceRecorder.save(trace).subscribe();
            });
}
```

**在 PipelineStep 中使用 `TraceHandle.wrapStep()` 记录步骤耗时**（可选但推荐）：

```java
// ContextHydrationStep 示例
return Mono.zip(memoryMono, ragMono)
    .elapsed()  // Reactor 内置耗时测量
    .doOnNext(tuple -> {
        long elapsed = tuple.getT1();
        OrchestrationContext result = tuple.getT2();
        trace.addSpan("MEMORY_LOADING", elapsed, input, output, confidence);
        return result;
    })
    .map(Tuple2::getT2);
```

## 10. Implementation Status (v2.3)

| 组件 | 实现 | 状态 |
|------|------|------|
| WorkflowDefinition (YAML DSL) | `WorkflowDefinitionLoader` + `WorkflowRegistry` | ✅ 已完成 — 8 个版本 × 3 个域 |
| WorkflowRenderer | `WorkflowRendererImpl` | ✅ 已完成 |
| TraceRecorder (TraceHandle) | `InMemoryTraceRecorder` | ✅ 已完成 |
| WorkflowBuilder | 通过 YAML 文件加载，无需 Builder | ✅ 替代方案 |

**YAML DSL 示例:**

```yaml
# workflows/customer-service/v2.3.yaml
id: customer-service
version: v2.3
persona: |
  [角色定义 + 思考流程 + 回复规则]
toolRules:
  - toolName: queryOrder
    description: 查询订单状态
    required: false
constraints:
  - name: no_hallucination
    content: 禁止编造不存在的商品信息
    level: HARD
```

## 11. Future Evolution (演进路线)

- **Workflow DSL (领域特定语言)**：未来支持使用 YAML 或 JSON 配置工作流，将代码逻辑转变为配置驱动（Configuration-driven）。
- **Visual Workflow Editor**：提供前端低代码拖拽面板，无需编写 Java 代码即可重新编排 Agent、Memory 与 Tool 的交互拓扑，沉淀为顶级学术论文的实验基座。
- **Trace Sampling**：在高并发生产环境下，按比例采样 Trace（如 10%），进一步降低可观测性开销。
