# ShopMind 系统架构（ARCHITECTURE）

> 本文档属于 P1-5 工程知识库，**以当前真实代码为唯一事实来源**。
> 所有结论均可通过文中的源码链接追溯；如与其它文档冲突，以本文档与最近验收报告为准。

---

## 1. 系统定位

ShopMind 是一个以**电商客服场景**为例的 **AI Agent 编排平台**，用于演示和验证：

- 基于 **Spring 响应式编程模型（Project Reactor）** 的智能体编排；
- **六引擎微内核**：Orchestrator / Memory / RAG / Workflow / MCP / Evaluation；
- **SSE 流式对话** + **自动化 Benchmark**（Rule-Based 与 LLM-as-Judge 两代评测）。

需要澄清的定位边界（与 `Enterprise_README.md` 的"生产级"表述区分）：

- 业务工具（`queryOrder` / `refund` / `queryPoints` / `queryCoupons`）当前为**内存示例数据**，非真实订单/会员系统；
- 向量存储为 **InMemory**（LangChain4j `InMemoryEmbeddingStore`），非独立向量库集群；
- 这是一个**工程底座 + 研究型评测平台**，而非已接入真实生产流量的系统。

---

## 2. 技术栈（事实清单）

| 层级 | 技术 | 依据 |
|------|------|------|
| 后端语言 | Java 17 | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L21) |
| 后端框架 | Spring Boot 3.2.5 | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L10) |
| HTTP 入站 | `spring-boot-starter-web`（Servlet + 响应式返回类型） | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L28-L31) |
| HTTP 出站 | `spring-boot-starter-webflux`（`WebClient` 调用外部 LLM API） | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L33-L37) |
| 响应式编程 | Project Reactor（`Mono` / `Flux`） | 全链路代码 |
| 记忆存储 | MongoDB（Spring Data MongoDB） | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L39-L43) |
| 向量存储 | LangChain4j `InMemoryEmbeddingStore` | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L65-L70) |
| 缓存 | Caffeine（QueryCacheService） | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L72-L76) |
| 熔断/限流 | Resilience4j 2.2.0（CircuitBreaker + RateLimiter） | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L78-L90) |
| 工作流 DSL | SnakeYAML（Spring Boot 内置） | [WorkflowDefinitionLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowDefinitionLoader.java) |
| 前端 | React 19 + TypeScript + Vite 8 | [package.json](file:///d:/A_big/ShopMind/frontend/package.json) |
| 前端 UI | Ant Design 6 | [package.json](file:///d:/A_big/ShopMind/frontend/package.json#L21) |
| 前端状态 | Zustand 5 | [package.json](file:///d:/A_big/ShopMind/frontend/package.json#L32) |
| 前端图表 | ECharts 6 | [package.json](file:///d:/A_big/ShopMind/frontend/package.json#L23-L24) |
| 测试 | JUnit 5 + SpringBootTest + embedded MongoDB | [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L98-L118) |

> 注意：`pom.xml` 同时引入 `web` 与 `webflux`，但二者职责不同——
> `web` 承载 SSE 入站（Spring MVC 的 `Flux` 返回类型），`webflux` 提供 `WebClient` 作为**出站 HTTP 客户端**调用 LLM。
> 因此"全链路跑在 WebFlux/Netty 上"的说法不准确，实际主容器是 Servlet（Tomcat）+ Reactor 编程模型。

---

## 3. 六引擎与代码目录映射

| 引擎 | 职责 | 后端包 | 关键入口 |
|------|------|--------|----------|
| Orchestrator | 中枢编排：意图 → 水合 → Prompt → LLM → 工具循环 | `com.shopmind.orchestrator` | [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java) |
| Memory | 会话记忆（MongoDB 滑动窗口） | `com.shopmind.memory` | [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java) |
| RAG | 知识检索（Cache→Embedding→Search→Filter→Build） | `com.shopmind.knowledge` | [RetrievalPipeline.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/RetrievalPipeline.java) |
| Workflow | YAML 工作流加载 + System Prompt 渲染 | `com.shopmind.workflow` | [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java) |
| MCP | 工具注册 + 反射执行 | `com.shopmind.mcp` | [McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java) |
| Evaluation | Benchmark 运行 + 指标评估 | `com.shopmind.evaluation` | [BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java) |

此外还有一层**传输/接入层** `com.shopmind.api`（[ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java)）对外暴露 HTTP 接口。

---

## 4. 分层架构（Port-Adapter / DDD）

每个引擎内部基本遵循 `port → domain → pipeline → adapter` 的分层：

```
        ┌─────────────────────────────────────────┐
        │  api（ChatController）  ← HTTP/SSE 接入   │
        └──────────────────┬──────────────────────┘
                           ▼
        ┌─────────────────────────────────────────┐
        │  orchestrator（ShopAgentOrchestrator）    │
        │   中枢编排，依赖下游 port 而非具体实现      │
        └───────┬──────────┬──────────┬───────────┘
                ▼          ▼          ▼
        ┌───────────┐ ┌─────────┐ ┌─────────────┐
        │  memory   │ │knowledge│ │   workflow   │
        │ (port/    │ │ (port/  │ │ (port/       │
        │ pipeline/ │ │ pipeline│ │ pipeline/    │
        │ store)    │ │ /adapter)│ │ domain)     │
        └───────────┘ └─────────┘ └─────────────┘
                │          │
                ▼          ▼
        ┌───────────┐ ┌─────────┐
        │  MongoDB  │ │LLM API  │（WebClient 出站）
        └───────────┘ └─────────┘
```

**Port（端口）示例**：编排层只依赖接口，不依赖具体厂商实现。

- `ChatModelPort` — LLM 抽象（[ChatModelPort.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/port/ChatModelPort.java)）
- `ChatMemoryStore` — 记忆抽象（[ChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/ChatMemoryStore.java)）
- `KnowledgeRetriever` — 检索抽象（[KnowledgeRetriever.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/api/KnowledgeRetriever.java)）
- `EmbeddingProviderPort` / `VectorStorePort` — Embedding 与向量库抽象
- `WorkflowRenderer` — Prompt 渲染抽象（[WorkflowRenderer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/port/WorkflowRenderer.java)）
- `McpEngine` — 工具执行抽象（[McpEngine.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/McpEngine.java)）

**Adapter（适配器）示例**：通过 Spring `@Profile` 切换实现。

- LLM：`MockChatModelAdapter` / `DashScopeChatAdapter` / `DeepSeekChatAdapter`
- Embedding：`MockEmbeddingAdapter` / `DashScopeEmbeddingAdapter`
- 向量库：`InMemoryVectorStoreAdapter`

---

## 5. 请求生命周期（端到端链路）

一次 `POST /api/chat` 的完整处理链路如下，每一步均标注真实代码位置。

### 5.1 前端发起请求

`useSSEChat.sendMessage` 通过 `createSSEReader` 建立 SSE 连接，POST 到 `/api/chat`，请求体为 `{ memoryId, query }`。

- [useSSEChat.ts](file:///d:/A_big/ShopMind/frontend/src/features/chat/hooks/useSSEChat.ts#L71-L107)

### 5.2 后端接入层

`ChatController.chat` 接收请求，校验空 query，生成/沿用 `memoryId`，委托 `ChatStreamingPort.stream` 返回 `Flux<ServerSentEvent>`。

- [ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java#L36-L55)

### 5.3 编排中枢 `ShopAgentOrchestrator.stream`

入口方法 `stream()` 组装出整条响应式链（见 [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L163-L204)）：

```
prelude（意图 + 水合 + Prompt + Intent 事件）
  .concatWith(executeWithToolLoop)   ← LLM 推理 + Inner Loop
  .onBackpressureBuffer(256)
  .concatWith(done 事件)
  .onErrorResume(degradeEvents)      ← 全局异常降级
  .doOnComplete / .doOnError          ← 回写 Memory + 状态标记
  .doFinally(observabilityLogger.log) ← 请求级可观测日志
```

#### Step 1 — 意图分析（Intent）

`stepIntentAnalysis` 调用 `IntentAnalyzer.analyze`，当前实现是**关键词匹配**的 `KeywordIntentAnalyzer`（识别 `requiresKnowledge` / `requiresTools` / `category`）。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L210-L222)
- [KeywordIntentAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/KeywordIntentAnalyzer.java)

#### Step 2 — 上下文水合（Memory + RAG 并行）

`ContextHydrationStep.execute` 使用 `Mono.zip` **并行**加载：
- Memory：`memoryStore.getMessages(memoryId)`，失败/超时降级为空列表；
- RAG：`knowledgeRetriever.retrieve(query, topK=3, scoreThreshold=0.7)`，失败/超时降级为空。

- [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L47-L104)

#### Step 3 — 用户消息回写 Memory

在读取历史之后、组装 Prompt 之前，`writeUserToMemory` 将当前用户消息持久化（避免本轮 history 重复）。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L509-L518)

#### Step 4 — Prompt 组装（WorkflowRenderer）

`stepPromptAssembly` 构造 `WorkflowInstance`（定义 + 历史 + 知识 + 用户消息），调用 `WorkflowRenderer.render` 渲染 System Prompt。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L227-L247)
- [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java)

#### Step 5 — LLM 推理 + Inner Loop（工具循环）

`executeWithToolLoop` 构建消息列表（System + History + User），发现工具，调用 `callLlm`。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L279-L294)

`callLlm` 对 LLM 流叠加保护：`timeout → onErrorMap → CircuitBreaker → retryWhen`。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L264-L274)

`handleLlmToken` 判断 token 是否为工具调用标记：
- 纯文本 → 发射 `ChatStreamEvent.Token`；
- 含 `__TOOL_CALL__` → 进入 `executeToolAndRePrompt`（Inner Loop）。

#### Step 6 — 工具执行（Inner Loop）

`executeToolAndRePrompt`：迭代守卫 → `mcpEngine.executeTool` → 回写 Memory → 将观察结果反哺 Prompt → 递归 `callLlm`，上限 `max-iterations=3`。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L317-L372)

#### Step 7 — 事件回传与结束

- 正常结束追加 `Done` 事件（含 TTFT / 总延迟 / token 估算）；
- 异常由 `degradeEvents` 降级为 `Error` 事件；
- `doOnComplete` 回写 AI 回复到 Memory；
- `doFinally` 输出请求级可观测日志。

- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L188-L203)

### 5.4 前端消费 SSE 事件

`useSSEChat.handleEvent` 按 `event.type` 分发到 Zustand store：`token` / `intent` / `tool_call` / `tool_result` / `done` / `error`。

- [useSSEChat.ts](file:///d:/A_big/ShopMind/frontend/src/features/chat/hooks/useSSEChat.ts#L23-L68)

---

## 6. 数据流（SSE 事件协议）

后端 `ChatStreamEvent` 是一个 `sealed interface`，通过 Jackson 多态序列化，每个事件 JSON 带 `type` 字段：

| type | 记录类型 | 字段 | 含义 |
|------|----------|------|------|
| `intent` | `ChatStreamEvent.Intent` | category, requiresKnowledge, requiresTools, confidence | 意图结果 |
| `token` | `ChatStreamEvent.Token` | content | 文本增量 |
| `tool_call` | `ChatStreamEvent.ToolCall` | callId, toolName, args | 工具调用开始 |
| `tool_result` | `ChatStreamEvent.ToolResult` | callId, success, output, latencyMs | 工具执行结果 |
| `done` | `ChatStreamEvent.Done` | sessionId, stats(ttftMs, totalMs, tokens) | 流结束 |
| `error` | `ChatStreamEvent.Error` | code, message | 异常终止 |

- [ChatStreamEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/domain/ChatStreamEvent.java#L14-L51)

前端类型定义与之一一对应（[chat.ts](file:///d:/A_big/ShopMind/frontend/src/shared/types/chat.ts)）。

---

## 7. 模块间依赖关系

```
                    ┌──────────────────────────────┐
                    │  ShopAgentOrchestrator        │
                    │  (依赖所有下游 port)            │
                    └───┬──────┬──────┬──────┬──────┘
                        │      │      │      │
        ┌───────────────┘      │      │      └──────────────┐
        ▼                      ▼      ▼                     ▼
  IntentAnalyzer        WorkflowRenderer  ChatModelPort   McpEngine
        │                      │             │              │
        │              WorkflowDefinition  (adapters)  ToolRegistry
        │                  Loader/Yaml      │         (BeanPostProcessor)
        │                                    │
        ▼                                    ▼
  ContextHydrationStep ──────► ChatMemoryStore + KnowledgeRetriever
        │                                     │
        │                                     ▼
        │                            RetrievalPipeline
        │                            (Cache→Embedding→Search)
        │                                     │
        │                                     ▼
        │                            EmbeddingProviderPort + VectorStorePort
        └─────────────────────────────────────┘
```

关键依赖方向总结：

- `ShopAgentOrchestrator` 依赖 **6 个 port**（IntentAnalyzer、ChatMemoryStore、KnowledgeRetriever、WorkflowRenderer、ChatModelPort、McpEngine），不依赖任何 adapter 具体实现。
- `ContextHydrationStep` 同时依赖 Memory 与 RAG 两个引擎，用 `Mono.zip` 并行。
- `Evaluation` 引擎**独立于在线链路**：`BenchmarkRunnerImpl` 通过 `EvaluableAgent` 接口驱动 Orchestrator（`ShopMindAgentAdapter` 适配），不耦合在线 API。

---

## 8. 部署拓扑（简述）

三服务 Docker Compose 拓扑，详见 [DEPLOYMENT.md](./DEPLOYMENT.md)：

```
frontend (Nginx, 静态资源 + /api 反向代理)
    │  /api/chat
    ▼
backend (Spring Boot, 8080)
    │
    ▼
mongodb (会话记忆, named volume)
```

- 前端 Nginx 对 `/api/` 反向代理，`proxy_buffering off`（保障 SSE 流式传输）。
- backend 通过环境变量注入 `SPRING_PROFILES_ACTIVE`、`QWEN_API_KEY`、`DEEPSEEK_API_KEY`。

---

## 9. 关键组件索引

| 组件 | 角色 | 文件 |
|------|------|------|
| `ChatController` | HTTP/SSE 接入 | [ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java) |
| `ShopAgentOrchestrator` | 中枢编排 | [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java) |
| `ContextHydrationStep` | Memory+RAG 并行水合 | [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java) |
| `KeywordIntentAnalyzer` | 关键词意图 | [KeywordIntentAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/KeywordIntentAnalyzer.java) |
| `MongoChatMemoryStore` | 会话记忆 | [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java) |
| `RetrievalPipeline` | RAG 检索 | [RetrievalPipeline.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/RetrievalPipeline.java) |
| `WorkflowDefinitionLoader` | YAML 加载 | [WorkflowDefinitionLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowDefinitionLoader.java) |
| `WorkflowRendererImpl` | System Prompt 渲染 | [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java) |
| `ToolRegistry` | 工具注册 | [ToolRegistry.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/registry/ToolRegistry.java) |
| `McpExecutor` | 工具执行 | [McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java) |
| `BenchmarkRunnerImpl` | 评测运行 | [BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java) |
| `RequestObservabilityLogger` | 请求级日志 | [RequestObservabilityLogger.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/observability/RequestObservabilityLogger.java) |
