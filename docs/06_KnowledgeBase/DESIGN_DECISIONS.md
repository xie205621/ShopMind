# ShopMind 设计决策记录（DESIGN_DECISIONS）

> 本文档属于 P1-5 工程知识库，记录主要技术决策及其**可追溯的设计动机**。
>
> 事实准则：
> - 动机只采自**当前代码注释**、`docs/02_Specifications/*` 规范、验收报告；
> - 无法从上述来源确定的内容，统一标注 **「待人工确认」**，不凭空补写；
> - 若"设计意图"与"当前代码实现"存在偏差，一并如实记录。

---

## 决策 1：使用 Project Reactor（响应式编程）与 Spring WebFlux

**决策内容**：全链路采用 `Mono` / `Flux` 响应式类型，`ChatController` 返回 `Flux<ServerSentEvent>`，`ChatModelPort` 返回 `Flux<String>`。

**代码证据**：
- [ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java#L36-L37) 返回 `Flux<ServerSentEvent<ChatStreamEvent>>`
- [ChatModelPort.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/port/ChatModelPort.java#L28) 返回 `Flux<String>`

**设计动机（来源）**：
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L33-L34)：「高并发非阻塞，严禁使用传统 BIO，必须基于 Project Reactor（WebFlux）构建全异步响应式链路」；
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L22)：「毫秒级打字机回复体验，不让用户白屏干等」。

**实现与意图的差异（需注意）**：
- `pom.xml` 同时引入 `spring-boot-starter-web`（SSE 入站）与 `spring-boot-starter-webflux`（`WebClient` 出站调用 LLM），见 [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L27-L37)。
- 因此运行时主容器是 **Servlet（Tomcat）+ Spring MVC 的响应式返回类型**，`webflux` 主要用于 `WebClient` 出站 HTTP，而非"整个应用跑在 Netty/WebFlux 运行时"。严格意义上这是"Reactor 编程模型 + WebClient"，不是纯 WebFlux 运行时。

---

## 决策 2：Adapter Pattern（Port-Adapter / 依赖倒置）

**决策内容**：编排层只依赖 Port 接口，具体实现通过 Adapter 注入；LLM 与 Embedding 通过 `@Profile` 切换实现。

**代码证据**：
- `ChatModelPort`（端口）→ `MockChatModelAdapter` / `DashScopeChatAdapter` / `DeepSeekChatAdapter`（适配器）
- `EmbeddingProviderPort` → `MockEmbeddingAdapter` / `DashScopeEmbeddingAdapter`
- `VectorStorePort` → `InMemoryVectorStoreAdapter`

**设计动机（来源）**：
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L46)：「必须遵守 DIP，通过 ChatModelPort 屏蔽底层大模型厂商实现」；
- [3_RAG_Engine.md](file:///d:/A_big/ShopMind/docs/02_Specifications/3_RAG_Engine.md#L44-L45)：「Adapter 模式：必须抽象 VectorStorePort 和 EmbeddingProviderPort；依赖倒置：核心逻辑仅依赖接口」。

---

## 决策 3：使用 MongoDB 存储会话记忆

**决策内容**：会话记忆持久化到 MongoDB，集合主键 `memory_id`，`upsert` 原子覆写。

**代码证据**：
- [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L129-L137) 使用 `mongoTemplate.upsert`
- [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L5-L8) 配置 MongoDB URI

**设计动机（来源）**：
- [1_Session_Memory.md](file:///d:/A_big/ShopMind/docs/02_Specifications/1_Session_Memory.md#L41-L43) 明确规定「必须使用 MongoDB 的 upsert 原子操作，禁止 delete 后 insert」「连接失败返回空列表，禁止抛异常中断主流程」。

**「待人工确认」**：**为什么选择 MongoDB 而非 Redis / 关系型数据库**，现有规范只给了"用 MongoDB"的约束，未记录与其他存储方案的对比取舍；[ADR 001](../05_ADR/001_Why_Mongo_For_Memory.md) 已补记决策事实，但「MongoDB vs 其他存储」的对比理由仍需人工补充。

---

## 决策 4：滑动窗口（默认 20 条）

**决策内容**：记忆保留最近 N 条（默认 20），超出按 FIFO 截断。

**代码证据**：
- [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L172-L184) `applySlidingWindow`
- [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L11-L13) `max-messages: 20`

**设计动机（来源）**：
- [1_Session_Memory.md](file:///d:/A_big/ShopMind/docs/02_Specifications/1_Session_Memory.md#L18-L19)：「成本与计费控制：防止 Token 消耗无上限增长」；
- [1_Session_Memory.md](file:///d:/A_big/ShopMind/docs/02_Specifications/1_Session_Memory.md#L23)：「超过阈值自动 FIFO 截断，移除最老记录」。

---

## 决策 5：YAML Workflow（版本化 Prompt 建模）

**决策内容**：System Prompt 由 YAML 工作流定义（persona + toolRules + constraints）渲染生成，支持版本化与 A/B。

**代码证据**：
- [WorkflowDefinitionLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowDefinitionLoader.java) 从 classpath 加载 YAML
- [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java) 渲染 System Prompt
- [v2.3.yaml](file:///d:/A_big/ShopMind/backend/src/main/resources/workflows/customer-service/v2.3.yaml)

**设计动机（来源）**：
- [5_Workflow_Engine.md](file:///d:/A_big/ShopMind/docs/02_Specifications/5_Workflow_Engine.md#L24)：「摒弃传统硬编码 Prompt 拼接，将企业 AI 运行过程抽象为可维护的软件工程模型」；
- [5_Workflow_Engine.md](file:///d:/A_big/ShopMind/docs/02_Specifications/5_Workflow_Engine.md#L45)：「支持版本控制，对 v1/v2 做严谨 A/B 测试对比」。

---

## 决策 6：MCP Tool Registry（注解驱动工具注册）

**决策内容**：用 `@McpTool` / `@McpParam` 注解标记业务方法，`BeanPostProcessor` 启动时扫描注册，运行时反射调用。

**代码证据**：
- [ToolRegistry.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/registry/ToolRegistry.java#L49-L92) `postProcessAfterInitialization` 扫描注册
- [McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java#L164-L209) 反射 `Method.invoke`

**设计动机（来源）**：
- [2_MCP_Engine.md](file:///d:/A_big/ShopMind/docs/02_Specifications/2_MCP_Engine.md#L15)：「业务插件化：新增业务功能只需加注解，无需修改 Agent 核心流转代码」；
- [2_MCP_Engine.md](file:///d:/A_big/ShopMind/docs/02_Specifications/2_MCP_Engine.md#L31)：「隔离边界：LLM 只能提交意图，不能直接操作数据库」。

**实现与意图的差异**：命名借用了 "MCP (Model Context Protocol)"，但当前实现是**进程内注解 + 反射**，并非标准 MCP 的 JSON-RPC / stdio / 远程 server 协议。见 [MODULE_GUIDE.md](./MODULE_GUIDE.md) Tool 模块限制。

---

## 决策 7：Resilience4j（熔断 + 限流）

**决策内容**：LLM Provider 调用叠加 CircuitBreaker（`llmProvider`）与重试；评测链路叠加 RateLimiter（`llmRateLimiter`）。

**代码证据**：
- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L264-L274) `CircuitBreakerOperator + retryWhen`
- [BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java#L137-L158) `RateLimiterOperator + maxConcurrency`
- [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L42-L70) 熔断/限流配置

**设计动机（来源）**：
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L52)：「熔断降级：对 LLM Provider 调用必须使用 @CircuitBreaker」；
- [BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java#L43-L48) 注释：「双重限流策略，防止瞬发流量触发 LLM 厂商 HTTP 429」。

---

## 决策 8：SSE（Server-Sent Events）流式返回

**决策内容**：`POST /api/chat` 以 `text/event-stream` 流式返回，前端通过 fetch 流式读取。

**代码证据**：
- [ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java#L36) `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
- [nginx.conf](file:///d:/A_big/ShopMind/frontend/nginx.conf) `proxy_buffering off`（保障 SSE 不被缓冲）
- [sseClient.ts](file:///d:/A_big/ShopMind/frontend/src/infrastructure/api/sseClient.ts) fetch-based SSE reader

**设计动机（来源）**：
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L22)：「毫秒级打字机回复体验」；
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L29)：「流式响应管道：底层基于 SSE，将 Token 实时透传」。

---

## 决策 9：Mock / Qwen / DeepSeek 三 Profile

**决策内容**：通过 Spring `@Profile` 切换 LLM 与 Embedding 实现。

| Profile | Chat Adapter | Embedding Adapter | 激活条件 |
|---------|--------------|-------------------|----------|
| `default`（无 profile） | `MockChatModelAdapter` | `MockEmbeddingAdapter` | `!prod & !qwen & !deepseek` / `!prod & !qwen` |
| `qwen` | `DashScopeChatAdapter` | `DashScopeEmbeddingAdapter` | `@Profile("qwen")` |
| `deepseek` | `DeepSeekChatAdapter` | `MockEmbeddingAdapter`（无真实 Embedding） | `@Profile("deepseek")` |

**代码证据**：
- [MockChatModelAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L22) `@Profile("!prod & !qwen & !deepseek")`
- [DeepSeekChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L45-L47) `@Component @Primary @Profile("deepseek")`
- [DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L43-L44) `@Profile("qwen")`
- [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L20) `@Profile("!prod & !qwen")`

**设计动机（来源）**：
- [MockChatModelAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L15-L19) 注释：「默认 Profile，无需外部 API Key；生产环境请使用 deepseek 或 qwen」；
- 三个 adapter 各自注释明确「激活条件 + 需配置 API Key」。

**关键事实**：DeepSeek 不提供 Embedding API，其 profile 下 Embedding 退化为 Mock（`MockEmbeddingAdapter` 激活条件不含 `!deepseek`）。详见 [LLM_CONFIGURATION.md](./LLM_CONFIGURATION.md)。

---

## 决策 10：测试环境使用 Mock + Embedded MongoDB

**决策内容**：测试不依赖真实 LLM API 与外部 MongoDB，使用 Mock 适配器与 Flapdoodle embedded MongoDB。

**代码证据**：
- [MockChatModelAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L15) 注释：「用于开发/测试环境」
- [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L112-L118) `de.flapdoodle.embed.mongo`（test scope）
- [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L12-L16) 注释

**设计动机（来源）**：
- Mock 适配器注释：「用于开发/测试环境」「确保相同文本产生相同向量（支持缓存命中测试）」；
- [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L112) 注释：「Embedded MongoDB for testing（无需安装 MongoDB）」。

**「待人工确认」**：Mock 向量基于 SHA-256 伪向量（非语义向量），其"相同文本→相同向量"可测缓存命中，但**无法验证语义检索质量**——这一限制在规范中未展开说明，属实现层面的已知取舍。

---

## 附加决策（补充）

### Inner / Outer Loop 双循环
- [4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L60-L80) 定义内外双循环；`ShopAgentOrchestrator` 实现对应逻辑。
- 动机：复杂意图需"多轮工具调度"，并设置 `max-iterations=3` 防死循环（[ToolIterationGuard.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ToolIterationGuard.java#L27)）。

### Memory + RAG 并行水合（Mono.zip）
- [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L88-L89) 使用 `Mono.zip` 并行加载。
- 动机（[4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L50)）：「TTFT 优化：必须 Mono.zip 并行加载 Memory 和 RAG，禁止串行阻塞首字渲染」。

### 背压保护
- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L188) `onBackpressureBuffer(256, DROP_OLDEST)`。
- 动机（[4_Agent_Orchestrator.md](file:///d:/A_big/ShopMind/docs/02_Specifications/4_Agent_Orchestrator.md#L51)）：「背压保护：防止慢客户端导致 OOM」。

### LLM-as-Judge（第二路 LLM 评估）
- [LlmJudgeMetricEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/LlmJudgeMetricEvaluator.java#L32-L36) 注释：「用第二路 LLM 取代关键词匹配做语义评估」。
- 动机：从"关键词匹配"跃升为"语义理解"（同文件注释）。
