# Module: Agent Orchestrator (智能体编排与调度引擎)

**Version**: v1.2

**Status**: 📝 Draft -> [x] Review -> [x] Approved -> [x] Implemented -> [ ] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:**
>
> This document is the single source of truth for implementing the Agent Orchestrator. Any AI-generated code MUST strictly follow this specification.
>
> **Architecture Review Prompt:**
>
> Review this specification from the perspective of SOLID, DDD, Clean Architecture, Thread Safety, High Availability, and Extensibility. Do not generate code until the review is passed.

## 1. Overview (模块概述)

Agent Orchestrator 是整个 AI Platform 的“中枢神经与总调度室”。它不直接存储数据，也不直接执行业务逻辑，而是作为统一的入口，协调 Memory Engine（记忆）、Knowledge Engine（知识）和 MCP Engine（工具）。它负责组装上下文、向大模型发起推理请求、处理大模型的 Function Calling 指令，并将最终结果以流式（Streaming）形式返回给用户。

## 2. Business Requirement (业务需求)

- **复杂意图处理**：用户可能在同一句话中既问规则又下指令（例如：“这手机能退吗？能退的话帮我下单”），系统需能准确拆解并执行。
- **极致交互体验**：C 端电商用户对延迟极度敏感，系统必须实现“毫秒级打字机”回复体验，绝不能让用户处于“白屏干等”状态。

## 3. Functional Requirement (功能需求)

- **意图拦截与分发 (Intent Analysis)**：在调用大模型前，预判是否需要提取记忆、检索知识或调用工具。
- **Prompt 动态组装 (Prompt Engineering)**：根据当前用户的状态，动态拼接 System Prompt、RAG 召回片段和历史记忆。
- **多轮工具调度 (Inner Loop)**：当大模型决定调用工具时，接管参数转发给 MCP Engine，并将业务执行结果（Observation）反哺大模型重新思考，直至得出最终文本。
- **流式响应管道 (Streaming Pipeline)**：底层基于 SSE 协议，将大模型生成的 Token 实时透传给前端。

## 4. Non-functional Requirement (性能要求)

- **高并发非阻塞**：严禁使用传统的 BIO（阻塞 IO），必须基于 Project Reactor（WebFlux）构建全异步响应式链路。
- **TTFT (首字延迟)**：收到用户请求到前端渲染出第一个字的耗时目标 `< 800ms`。

## 5. Responsibility (职责)

- **调度者**：按严格的生命周期顺序调用其他引擎（意图分析 -> 读记忆 -> 查知识 -> 调模型 -> 执工具）。
- **防护网**：限制大模型无限调用工具（防死循环），提供系统级的兜底降级策略。

## 6. Constraints (约束)

**必须实现 (MUST)：**

- [x] 必须使用 `Flux<String>` (响应式流) 作为核心返回类型，打通从 LLM 到网关的数据管道。
- [x] 必须遵守依赖倒置原则（DIP），通过 `ChatModelPort` 屏蔽底层大模型厂商实现。
- [x] 必须设置 `Max Iterations`（最大推理轮次，默认 3 次），防止工具调用死循环。
- [x] **线程安全**（架构评审新增）：`ShopAgentOrchestrator` 和所有 `PipelineStep` 均为 `@Component` 单例，禁止将 `OrchestrationContext` 作为实例字段。状态仅在方法参数/返回值中传递。
- [x] **Inner Loop 记忆回写**（架构评审新增）：Tool 调用结束后，必须将 ToolCall + Observation 写回 `ChatMemoryStore`，否则 LLM 在下一轮推理中不知道上一轮的工具执行结果。
- [x] **TTFT 优化**（架构评审新增）：`ContextHydrationStep` 必须使用 `Mono.zip()` 并行加载 Memory 和 RAG 数据，禁止串行阻塞首字渲染。
- [x] **背压保护**（架构评审新增）：`Flux<String>` 必须配置 `onBackpressureBuffer(256, BufferOverflowStrategy.DROP_OLDEST)`，防止慢客户端导致 OOM。
- [x] **熔断降级**（架构评审新增）：对 LLM Provider 调用必须使用 `@CircuitBreaker(name = "llmProvider")`，返回优雅的流式降级提示。

**绝对禁止 (MUST NOT)：**

- [ ] 绝对禁止在 Orchestrator 中编写任何具体的电商业务逻辑（如查库存 SQL）。
- [ ] 绝对禁止使用 `Thread.sleep()` 或同步阻塞式的 LLM 客户端 API。
- [ ] **绝对禁止** `OrchestrationContext` 或 `ExecutionState` 作为任何 `@Component` 单例的实例字段。

## 7. Workflow & Sequence Diagram (流转与时序图)

采用带有意图分析前置的**内外双循环 (Inner/Outer Loop)** 架构：

Plaintext

```
[Outer Loop]
User 
 │ 
 │─ 1. User Query
 ▼
[ Intent Analysis ] ──▶ (判断是否触发 RAG / 工具域)
 │ 
 ▼
[ Memory Engine ] ────▶ (装载历史 Session)
 │ 
 ▼
[ Knowledge Engine ] ─▶ (召回 RetrievedContext)
 │ 
 ▼
[ Prompt Assembly ] ──▶ (拼接上下文与系统约束)
 │ 
 ▼
[ LLM 推理 ] ◀─────────────────────────────────────────┐
 │                                                     │
 ├─ (分支A: 纯文本回复) ──▶ (直接输出 Token 流)        │
 │                                                     │
 └─ (分支B: 触发 ToolCall)                             │
       │                                               │
   [Inner Loop]                                        │
       ▼                                               │
   [ MCP Engine (Tool Executor) ]                      │
       │                                               │
       ▼                                               │
   [ Business Service (执行业务) ]                     │
       │                                               │
       ▼                                               │
   [ Observation (返回执行结果) ] ─────────────────────┘
```

## 8. Data Model (数据模型)

**OrchestrationRequest (不可变输入)** — 用户发起对话时的携带数据，全链路只读传递。

```java
record OrchestrationRequest(
    String memoryId,    // 租户会话唯一标识
    String userMessage  // 用户当前轮输入的自然语言
) {}
```

**OrchestrationContext (会话上下文状态机)** — 在响应式管道中传递当前会话的流转状态，杜绝基本类型参数满天飞。

```java
class OrchestrationContext {
    // === 不可变部分（来自 request）===
    String memoryId;           // 租户会话ID
    String userMessage;        // 当前提问
    
    // === 可变部分（各 Step 逐步填充）===
    List<ChatMessage> history;       // Memory Engine 恢复的对话历史
    RetrievedContext knowledge;      // Knowledge Engine 召回的知识片段
    String assembledPrompt;          // PromptAssembler 组装的完整 Prompt
    
    // === 状态监控 ===
    ExecutionState state;            // 执行追踪
}

class ExecutionState {
    ExecutionStep currentStep;       // 枚举替代 String（评审建议）
    int toolCallCount;               // 已调用工具次数（防死循环）
    int retryCount;                  // 失败重试次数
    ExecutionStatus status;          // 枚举替代 String（评审建议）
}

enum ExecutionStatus {
    RUNNING, SUCCESS, FAILED, DEGRADED
}

enum ExecutionStep {
    INTENT_ANALYSIS, MEMORY_LOADING, KNOWLEDGE_RETRIEVAL,
    PROMPT_ASSEMBLY, LLM_INFERENCE, TOOL_EXECUTION, COMPLETE
}
```

## 9. API Design (内部接口)

```java
public interface AgentOrchestrator {
    /**
     * 核心对话入口：接收不可变请求对象，返回响应式 Token 流。
     * 以 §8 定义的 OrchestrationRequest record 替代基本类型参数满天飞，
     * 确保接口向后兼容扩展（新增字段无需改接口签名）。
     *
     * @param request 不可变编排请求（memoryId + userMessage）
     * @return 响应式 Token 文本流 (用于 SSE)
     */
    Flux<String> chat(OrchestrationRequest request);
}
```

## 10. Class Design (核心类设计)

AI Coding 生成时，需采用以下管道化组件划分，防止单类过载：

- **领域模型 (`domain` 包)**：`OrchestrationRequest` (record), `OrchestrationContext`, `ExecutionState`, `ExecutionStatus` (enum), `ExecutionStep` (enum)。
- **防腐接口 (`port` 包)**：
  - **`ChatModelPort` (Interface)**：解耦大模型厂商（OpenAI / DashScope），暴露 `Flux<String> stream(...)`。
  - **`PipelineStep` (Interface)**：`Mono<OrchestrationContext> execute(OrchestrationContext ctx)` — 评审新增，支持 OCP 开闭原则插件式扩展。
  - **`IntentAnalyzer` (Interface)**：`Mono<IntentResult> analyze(String userMessage)` — 评审新增，避免黑盒实现。
- **管线组件 (`pipeline` 包)**：
  - **`ContextHydrationStep` (Component)**：**Mono.zip(Memory, RAG)** 并行加载，压缩 TTFT 延迟。
  - **`PromptAssembler` (Component)**：将 System Prompt、Memory、Knowledge 拼接为 LLM 可消费格式。
  - **`ToolIterationGuard` (Component)**：拦截器，检查 `ExecutionState.toolCallCount` 是否超过 `maxIterations`。
- **`ShopAgentOrchestrator` (Impl)**：入口类，实现 `AgentOrchestrator` 接口，串联 Pipeline，输出 `Flux<String>`。

## 11. Exception Handling (异常处理)

- **`PromptAssemblyException`**：记忆提取为空或 RAG 上下文组装失败时抛出。**降级策略**：终止 LLM 请求，返回友好提示“上下文加载失败，请重试”。
- **`MaxIterationExceededException`**：大模型陷入工具调用死循环。**降级策略**：终止循环，截断并向前端输出：“系统当前遇到一些复杂情况，已为您转接人工客服。”
- **`LlmProviderTimeoutException`**：云端大模型 API 响应超时。**降级策略**：返回优雅的流式错误提示。

## 12. Test Plan (测试计划)

- **Inner Loop 死循环阻断测试**：Mock 一个永远返回无效参数的 LLM，断言系统在 `ExecutionState.toolCallCount` 达到 3 次后被强制阻断，并正常返回降级文本。
- **管道组装断言**：测试 `OrchestrationPipeline` 是否严格按照 `Intent -> Memory -> RAG -> Prompt -> LLM` 的顺序执行。

## 13. Evaluation (评测指标)

- **Intent Accuracy**：意图识别准确率（目标 `> 95%`）。
- **Tool Success Rate**：工具调用的端到端成功率（不只是模型生成准确，还包括业务执行成功）。
- **TTFT (Time To First Token)**：首字响应延迟稳定性。

## 14. Future Evolution (演进路线)

- **Prompt 构建器重构**：将当前的 `PromptAssembler` 升级为 Builder Pattern 设计（拆分为 `SystemPromptBuilder`, `ContextInjector`, `PromptRenderer`），以应对未来极度复杂的 Prompt 组装逻辑。
- **Multi-Agent Router**：从单一 Orchestrator 升级为多智能体路由分发机制。