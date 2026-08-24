# ShopMind 面试指南（INTERVIEW_GUIDE）

> 本文档属于 P1-5 工程知识库，目标是**帮助你真正掌握并讲清这个项目**，不是宣传稿。
> 每个答案都能从当前代码追溯到依据；不确定的地方如实标注，不硬编动机。
> 使用建议：先背下 30 秒版，再把每个技术点的"内核 + 讲法 + 追问"逐条过一遍。

---

## 0. 项目一句话定位（先说清"它是什么"）

> ShopMind 是一个以**电商客服**为场景的 **AI Agent 编排平台**，用 Java + Spring 响应式编程实现了**六引擎微内核**（Orchestrator / Memory / RAG / Workflow / MCP / Evaluation），支持 **SSE 流式对话**、**可插拔 LLM**（Mock/Qwen/DeepSeek 三 Profile）、**自动化 Benchmark**（规则评估 + LLM-as-Judge 两代），并完成了 Docker 化部署。

诚实边界（面试时主动说，避免被戳穿）：
- 业务工具是**内存示例数据**，不是接真实订单系统；
- 向量库是 **InMemory**，不是独立向量数据库集群；
- 定位是**工程底座 + 研究型评测平台**，不是已上生产流量的系统。

---

## 1. 30 秒项目介绍（电梯演讲）

> 我用 Java 从零实现了一个电商客服领域的 AI Agent 编排平台。核心是自研的六引擎架构——Orchestrator 负责调度，Memory 用 MongoDB 存会话上下文，RAG 做知识检索，Workflow 用 YAML 管理版本化 Prompt，MCP 用注解加反射做工具调用，Evaluation 做自动化评测。整体基于 Spring WebFlux 响应式编程，用 SSE 实现流式输出，并且把 LLM 抽象成可插拔适配器，Mock、通义千问、DeepSeek 三个 Profile 一键切换。最后用 Docker Compose 完成了三服务容器化部署。项目全部测试通过，还针对 LLM 超时、熔断这些稳定性问题做了专项修复。

**讲法要点**：30 秒只讲"六引擎 + 响应式 + 可插拔 LLM + Docker"四个关键词，细节留给追问。

---

## 2. 2 分钟项目介绍（展开版）

**背景与定位（20s）**：这是一个电商客服智能体，但本质是验证"如何用软件工程方法构建可信 AI Agent"——重点不是业务，而是架构和评测。

**架构（40s）**：分六引擎，遵循 DDD 的 Port-Adapter 分层。Orchestrator 是中枢，只依赖接口不依赖实现；Memory/RAG/Workflow/MCP 都是独立引擎，通过 Port 解耦。LLM 层用 Adapter 模式，`ChatModelPort` 抽象掉厂商差异。

**请求链路（30s）**：一次对话走"意图分析 → 上下文水合（Memory+RAG 并行）→ Prompt 组装 → LLM 流式推理 → 工具循环 → SSE 回传"，每一步都有超时、熔断、降级保护。

**评测与工程化（30s）**：Workflow 版本化支持 A/B，Evaluation 引擎支持规则评估和 LLM-as-Judge 语义评估，126 个标准用例；工程上做了 Docker 化、Nginx SSE 代理、请求级可观测日志。

---

## 3. 整体架构讲解（怎么讲）

**内核**：一句话——"一个中枢 Orchestrator，五个服务引擎，全部通过 Port 解耦"。

```
        ChatController (SSE 接入)
              │
        ShopAgentOrchestrator (中枢，依赖 6 个 Port)
   ┌──────┬──────┬──────┬──────┬──────┐
 Intent  Memory  RAG  Workflow  LLM  MCP
```

**分层**（[ARCHITECTURE.md](./ARCHITECTURE.md) 第 4 节）：每个引擎内部都是 `port → domain → pipeline → adapter`。

- `port`：接口（如 `ChatModelPort`、`ChatMemoryStore`、`KnowledgeRetriever`、`McpEngine`、`WorkflowRenderer`）
- `domain`：领域模型（如 `OrchestrationContext`、`WorkflowDefinition`、`ChatStreamEvent`）
- `pipeline`：业务流水线（如 `RetrievalPipeline`、`BenchmarkRunnerImpl`）
- `adapter`：具体实现（如 `MongoChatMemoryStore`、`DashScopeChatAdapter`）

**讲法**：强调"依赖倒置"——Orchestrator 不 import 任何 adapter，只依赖 Port，所以换 LLM 厂商、换向量库、换记忆存储都不动核心代码。

**高频追问**：
- Q: "为什么不用 LangChain 直接用？" → A: 项目里确实有 LangChain4j（用于 `InMemoryEmbeddingStore`），但**编排核心是自研的**，目的就是理解 Agent 内部原理而不是套壳。自研才能拿到"每一步耗时、意图、工具调用"的完整 Trace 用于评测。
- Q: "六引擎是不是过度设计？" → A: 对生产系统可能偏重，但这个项目的目标就是**研究型评测**——Workflow 版本 A/B、消融实验都需要引擎边界清晰。

---

## 4. 请求完整链路（怎么讲）

**讲法**：按顺序讲 7 步，每步点出对应类。

| 步骤 | 做什么 | 代码 |
|------|--------|------|
| 1 意图分析 | 关键词判断是否需要知识/工具 | [KeywordIntentAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/KeywordIntentAnalyzer.java) |
| 2 上下文水合 | `Mono.zip` 并行加载 Memory + RAG | [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L88-L89) |
| 3 写用户消息 | 本轮 query 落 Memory | `writeUserToMemory` |
| 4 Prompt 组装 | Workflow YAML 渲染 System Prompt | [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java) |
| 5 LLM 推理 | `callLlm` 带 timeout/熔断/重试 | [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L264-L274) |
| 6 工具循环 | 识别 `__TOOL_CALL__` → MCP 执行 → 反哺重推理 | [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L317-L372) |
| 7 事件回传 | SSE 事件流 + 可观测日志 | `ChatController` → 前端 |

**内核**：整条链是一个 `Flux<ChatStreamEvent>` 的响应式装配，`onBackpressureBuffer(256)` 防背压、`onErrorResume` 兜底、`doFinally` 出观测日志。

**高频追问**：
- Q: "Inner Loop 会不会死循环？" → A: 用 `ToolIterationGuard` 限制最多 3 次（`max-iterations: 3`），超过就降级为文本提示。

---

## 5. RAG（怎么讲）

**内核**：6 步流水线——**准入检查 → Query Cache → Embedding → Vector Search → Threshold Filter → Context Build**（[RetrievalPipeline.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/RetrievalPipeline.java#L56-L135)）。

**讲法**：
1. 先查 Caffeine 缓存（相同 query 直接命中，避免重复调 Embedding）；
2. 缓存未命中 → 调 Embedding 把 query 向量化；
3. 在向量库做 Top-K 相似检索；
4. 阈值过滤：低于 `scoreThreshold` 的块丢弃，全丢弃抛 `LowSimilarityException` → 引导 LLM 诚实说"知识库没有相关信息"；
5. 拼成 Context 注入 Prompt。

**可插拔**：`EmbeddingProviderPort` + `VectorStorePort` 两个接口解耦，实现可替换。

**关键限制（必须主动说）**：
- 向量库是 LangChain4j `InMemoryEmbeddingStore`，内存态，重启靠 `KnowledgeBootstrap` 重建；
- **DeepSeek 无 Embedding API**，`deepseek` profile 下 Embedding 是 Mock（SHA-256 伪向量），RAG 语义检索不真实；只有 `qwen` profile 用真实 `text-embedding-v3`（1024 维）；
- 静态知识库实际 **30 chunks**。

**高频追问**：
- Q: "为什么 Mock Embedding 用 SHA-256？" → A: 保证"相同文本→相同向量"，用于测缓存命中和流水线控制流，**不代表语义**（[MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L12-L16)）。
- Q: "RAG 参数在哪配？" → A: `topK=3`、`threshold=0.7` 是**硬编码**在 `ContextHydrationStep`，不是 yml 可配（这是当前限制之一）。

---

## 6. Memory（怎么讲）

**内核**：解决"LLM 无状态"，用 MongoDB 存会话历史，滑动窗口控长度。

**讲法**：
1. 多轮对话靠 `memoryId` 隔离会话（多租户）；
2. 消息以 JSON 存单文档，`memory_id` 主键；
3. **滑动窗口 20 条**（`max-messages: 20`），超过 FIFO 截断，控 Token 成本；
4. 更新用 `upsert` **原子覆写**（不是 delete+insert）；
5. 故障兜底：Mongo 超时/断连 → 返回空历史，不中断对话。

**实现**：[MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java)。

**关键点**：Jackson 多态反序列化区分 `UserMessage` / `AiMessage` / `SystemMessage`；旧 JSON 解析失败会重置会话。

**高频追问**：
- Q: "为什么用 MongoDB 不用 Redis？" → A: **诚实回答**：项目里没有书面记录这个选型对比（`docs/05_ADR/001` 是空文件），规范只规定了"用 MongoDB upsert"。选型动机需要人工补充，不要硬编。能确定的是：需要持久化（重启不丢）+ 文档型结构适合存消息数组。
- Q: "滑动窗口丢历史会不会丢关键信息？" → A: 会，这是短期记忆的取舍；规范里预留了"摘要记忆"演进方向（淘汰时用 LLM 压缩成摘要驻留头部），当前未实现。

---

## 7. Tool Calling（怎么讲）

**内核**：让 LLM 从"只会说"到"能做"——通过注解 + 反射把业务方法暴露成工具。

**讲法**：
1. 业务方法打 `@McpTool` / `@McpParam` 注解；
2. `ToolRegistry`（`BeanPostProcessor`）启动时扫描注册，生成 JSON Schema；
3. LLM 通过 Function Calling 返回工具名 + JSON 参数；
4. Adapter 把它转成统一的 `__TOOL_CALL__toolName{jsonArgs}` 标记；
5. Orchestrator 解析标记 → `McpExecutor` 用 `Method.invoke` 反射调用 + 3s 超时；
6. 结果（Observation）写回 Memory，反哺 LLM 重新推理。

**实现**：[McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java)、[ToolRegistry.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/registry/ToolRegistry.java)。

**关键限制（必须主动说）**：这不是标准 MCP 协议（无 JSON-RPC / stdio / 远程 server），是**进程内注解驱动的反射工具注册**，只是借用了 MCP 的命名。工具数据是内存示例。

**高频追问**：
- Q: "LLM 乱传参数怎么办？" → A: `McpExecutor.bindArguments` 做类型校验，异常降级成可读提示反哺 LLM（"参数错误…"），不让异常打崩 SSE。
- Q: "为什么不直接用标准 MCP SDK？" → A: 项目目标是自研理解工具调度原理；标准 MCP 是未来演进方向，当前是轻量自研实现。

---

## 8. Workflow（怎么讲）

**内核**：把 System Prompt 从代码剥离成**版本化 YAML**，支持 A/B 实验。

**讲法**：
1. 每个工作流一个 YAML：`id + version + persona + toolRules + constraints`；
2. `WorkflowDefinitionLoader` 从 classpath 加载；
3. `WorkflowRendererImpl` 把 `WorkflowInstance` 渲染成 System Prompt（顺序：persona → constraints → tools → knowledge）；
4. 版本化（如 `customer-service/v2.3`）用于评测 A/B 对比。

**实现**：[v2.3.yaml](file:///d:/A_big/ShopMind/backend/src/main/resources/workflows/customer-service/v2.3.yaml)、[WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java)。

**关键限制**：`HARD`/`SOFT` 约束只是**文本注入**，没有运行时强制校验，靠 LLM 遵守；默认版本在 Orchestrator 构造时固定为 v2.3。

**高频追问**：
- Q: "Workflow 和 RAG 什么关系？" → A: 正交。Workflow 管"角色和规则"，RAG 管"知识内容"；渲染时把 RAG 召回的知识作为 `【参考知识】` section 注入 Prompt。

---

## 9. WebFlux / SSE（怎么讲）

**内核**：用 Reactor 响应式 + SSE 实现"毫秒级打字机"流式输出。

**讲法**：
1. 后端 `ChatController` 返回 `Flux<ServerSentEvent>`，`ChatModelPort` 返回 `Flux<String>`；
2. LLM token 一个接一个经 SSE 推给前端，首字延迟（TTFT）低；
3. `onBackpressureBuffer(256, DROP_OLDEST)` 防慢客户端 OOM；
4. Nginx 侧 `proxy_buffering off` 保证 token 不被缓冲。

**关键澄清（别被追问戳穿）**：项目 `pom.xml` **同时**有 `spring-boot-starter-web` 和 `webflux`——`web` 承载 SSE 入站（Spring MVC 的 `Flux` 返回类型），`webflux` 提供 `WebClient` 出站调 LLM。所以严格说是"Servlet + Reactor 编程模型 + WebClient"，**不是纯 Netty WebFlux 运行时**。面试时主动说这点很加分。

**实现**：[ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java#L36-L55)、[nginx.conf](file:///d:/A_big/ShopMind/frontend/nginx.conf#L14-L29)。

**高频追问**：
- Q: "SSE 和 WebSocket 区别？为什么用 SSE？" → A: 客服场景是单向流（服务端→客户端），SSE 更轻量、天然走 HTTP、支持断线重连；WebSocket 是双向，这里不需要。

---

## 10. Resilience4j（怎么讲）

**内核**：LLM 是"不可靠的第三方"，用熔断 + 重试 + 超时三层保护。

**讲法**（对应 `callLlm` 的操作符顺序）：
1. `.timeout(30s)`：单次流式调用超时；
2. `.onErrorMap`：超时统一映射成 `LlmProviderTimeoutException`；
3. `CircuitBreakerOperator`：超时/失败计入熔断统计；
4. `.retryWhen(backoff 2 次)`：**只对超时异常重试**，不重试 4xx/5xx。

**配置**：[application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L42-L70)（`llmProvider` 熔断 + `llmRateLimiter` 限流 30/min）。

**亮点**：这里有一个**真实踩坑**——Resilience4j 默认 `minimumNumberOfCalls=100`，低流量下熔断永不触发，所以显式调成 5（详见第 13 节 B2）。

---

## 11. Docker（怎么讲）

**内核**：三服务容器化，Nginx 做 SSE 反向代理。

**讲法**：
1. `docker-compose.yml` 三个服务：frontend(Nginx) / backend(Spring Boot) / mongodb；
2. 服务间用 bridge 网络服务名互通（`mongodb:27017`）；
3. 镜像用**多阶段构建**：先 Maven/Node 编译，再只拷贝产物到 JRE/Nginx 运行时，减小体积；
4. Nginx `proxy_buffering off` + `proxy_cache off` 保证 SSE 流式；
5. MongoDB 用 named volume 持久化 + 健康检查，backend 依赖健康检查通过才启动。

**实现**：[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml)、[backend/Dockerfile](file:///d:/A_big/ShopMind/backend/Dockerfile)、[nginx.conf](file:///d:/A_big/ShopMind/frontend/nginx.conf)。

**高频追问**：
- Q: "API Key 怎么管理？" → A: 通过环境变量注入（`QWEN_API_KEY` / `DEEPSEEK_API_KEY`），不写死在镜像/Compose 里，`.env` 被 gitignore。

---

## 12. Evaluation（怎么讲）

**内核**：用自动化 Benchmark 度量 Agent 质量，支撑 Workflow A/B 和消融实验。

**讲法**：
1. `BenchmarkRunnerImpl` 用 `Flux.flatMap(maxConcurrency)` + RateLimiter 双重限流跑用例；
2. 两代评估器：`RuleBasedMetricEvaluator`（关键词匹配，`!deepseek` profile）→ `LlmJudgeMetricEvaluator`（LLM-as-Judge 5 维 0-100 语义评分，`deepseek` profile）；
3. 数据集 126 用例（7 场景）；单个用例失败不中断整体。

**亮点**：LLM-as-Judge 是"用第二个 LLM 取代关键词匹配做语义评估"，5 个维度（意图匹配/工具选择/任务成功/幻觉/知识召回）。

**关键限制**：LLM-as-Judge 依赖 deepseek profile，API 调用翻倍；**Judge 失败时默认判不通过**（不是"默认通过"，`fallbackResult` 全 false）。

---

## 13. 真实 Bug 与修复（面试最加分项）

### 13.1 P1-2 稳定性三连 Bug

| # | Bug 现象 | 根因 | 修复 |
|---|----------|------|------|
| **B1** | LLM 无响应时请求**无限挂起** | outer loop 裸调 `chatModelPort.stream()`，绕过 `.timeout()` | `executeWithToolLoop` 复用 `callLlm()`（[ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L264-L274)） |
| **B2** | 低流量下熔断**永不 OPEN** | Resilience4j 默认 `minimumNumberOfCalls=100` | 显式配 `minimum-number-of-calls: 5`（[application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L48)） |
| **B3** | 超时被误判成"意外错误" | `retryWhen` 重试用尽抛 `RetryExhaustedException` 包装原始异常，`degradeEvents` 没沿 cause 链找 | 新增 `isLlmTimeout()` 沿 `getCause()` 链识别 |

**讲法**：这三个 Bug 展示了"稳定性不是加个 try-catch 就够"，而是响应式操作符顺序、第三方库默认值、异常包装这些细节都会埋坑。修复都是**最小改动、不改业务逻辑**，并各配了自动化测试（`OrchestratorStabilityTest` 4 个用例）。

### 13.2 llmLatencyMs=0（已知观测性瑕疵，未修）

- **现象**（P1-4 报告）：真实 LLM 被调用（`totalLatencyMs≈2.2s`），但观测日志里 `llmLatencyMs` 没记录到该耗时。
- **计时实现位置**：[ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L289-L291) 的 `callLlm(...).doOnTerminate(...)`。
- **候选根因方向（待最终定位）**：`doOnTerminate` 只在 onComplete/onError 触发、冷 Flux 订阅边界与 `retryWhen` 链的交互。P1-4 报告已定性为"P1-1 计时覆盖范围"问题，留待 P1-5 定位。

**面试讲法**：诚实说这是"已知待修的可观测性瑕疵"，然后主动展示你的排查思路——"计时点是 doOnTerminate，理论上应该在流终止时结算，但实际没生效，候选方向是响应式操作符的终止信号语义，我会沿着冷 Flux 订阅边界和 retry 链去定位"。这比假装没 bug 更可信。

---

## 14. 项目局限（主动说，显示自我认知）

1. **工具是内存示例数据**，非真实订单/会员系统；
2. **向量库是 InMemory**，非独立向量数据库，重启需重建；
3. **DeepSeek 无 Embedding**，RAG 语义检索只在 qwen profile 真实；
4. **意图分析是关键词规则**（`KeywordIntentAnalyzer`），非模型分类；
5. **工具调用是字符串标记解析**，非完整 Function Calling 协议栈；
6. **MCP 非标准协议**，是进程内注解+反射；
7. **`PromptAssembler` 是未接入的遗留类**，实际用 `WorkflowRenderer`；
8. **HARD/SOFT 约束无运行时强制**，靠 LLM 自觉；
9. **测试/README 数字有漂移**（如 81 vs 102 tests、80 vs 30 chunks），是文档治理问题；
10. **无自动化 Docker E2E 测试**，Docker 验证靠手动冒烟。

---

## 15. 高频追问清单（快速自测）

| 追问 | 答案锚点（可回代码找） |
|------|------------------------|
| 请求从进入到返回的完整路径？ | 见第 4 节 7 步 |
| 怎么防止工具调用死循环？ | `ToolIterationGuard` + `max-iterations: 3` |
| LLM 挂了怎么办？ | `callLlm` 的 timeout→熔断→retry→`degradeEvents` |
| 为什么用响应式不用阻塞？ | 打字机流式 + 背压 + 非阻塞（见第 9 节） |
| RAG 命中不到知识怎么办？ | `LowSimilarityException` → 引导拒答 |
| 多轮对话怎么记住上下文？ | MongoDB 滑动窗口 20 条 + 多租户隔离 |
| 三个 Profile 区别？ | Mock(无依赖)/Qwen(含真实 Embedding)/DeepSeek(无 Embedding) |
| 怎么评测 Agent 好不好？ | 规则评估 + LLM-as-Judge，126 用例，A/B/消融 |
| DeepSeek 能跑 RAG 吗？ | 不能，无 Embedding API，退化 Mock |
| 你修过什么真 Bug？ | B1/B2/B3 三个稳定性 Bug（见第 13 节） |

---

## 16. 一句话总结（离场语）

> 这个项目的价值不在于"又一个客服机器人"，而在于我把一个 Agent 拆成了可测试、可评测、可替换的工程单元，并在稳定性（超时/熔断/重试）、可观测性（请求级 Trace）、可评测性（LLM-as-Judge）三个维度都做了真实落地——这套方法论可以迁移到任何"LLM 应用工程化"的场景。
