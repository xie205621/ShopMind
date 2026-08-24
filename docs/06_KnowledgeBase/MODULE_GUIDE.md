# ShopMind 模块指南（MODULE_GUIDE）

> 本文档属于 P1-5 工程知识库，**以当前真实代码为唯一事实来源**。
> 对 Orchestrator / Memory / RAG / Workflow / Tool(MCP) / Evaluation 六大模块逐一说明。
> 所有类名与方法名均可通过源码链接追溯。

---

## 1. Orchestrator 模块（编排中枢）

### 为什么存在
把"一次对话"拆解为可编排、可降级、可观测的流水线，串联意图 → 上下文水合 → Prompt → LLM → 工具循环，是系统唯一的总入口。

### 输入 / 输出
- 输入：`OrchestrationRequest(memoryId, userMessage)`
- 输出：`Flux<ChatStreamEvent>`（HTTP/SSE 用，`stream()`）；`Flux<String>`（评测用纯文本，`chat()`）

### 核心类
| 类 | 职责 |
|----|------|
| [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java) | 中枢编排，实现 `AgentOrchestrator` + `ChatStreamingPort` |
| [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java) | Memory + RAG 并行水合 |
| [KeywordIntentAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/KeywordIntentAnalyzer.java) | 关键词意图分析 |
| [ToolIterationGuard.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ToolIterationGuard.java) | Inner Loop 迭代上限守卫 |
| [RequestObservabilityLogger.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/observability/RequestObservabilityLogger.java) | 请求级可观测日志 |

### 核心方法
- `stream(OrchestrationRequest)` — 事件流主入口（[L163](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L163-L204)）
- `chat(OrchestrationRequest)` — 纯文本入口（委托 `stream` 后转文本）
- `stepIntentAnalysis` — 意图分析（[L210](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L210-L222)）
- `stepPromptAssembly` — Prompt 组装（[L227](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L227-L247)）
- `callLlm` — 带 timeout/熔断/重试的 LLM 调用（[L264](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L264-L274)）
- `executeWithToolLoop` — Outer+Inner Loop（[L279](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L279-L294)）
- `handleLlmToken` — 区分文本/工具调用（[L299](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L299-L312)）
- `executeToolAndRePrompt` — Inner Loop 核心（[L317](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L317-L372)）
- `buildMessages` / `discoverTools` / `degradeEvents` — 辅助与降级

### 被谁调用
- 在线：`ChatController`（[ChatController.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/api/ChatController.java)）
- 评测：`ShopMindAgentAdapter`（实现 `EvaluableAgent`，驱动 `chat()`）
- 测试：`ShopAgentOrchestratorTest`、`OrchestratorStabilityTest` 等

### 调用谁
`IntentAnalyzer`、`ContextHydrationStep`（进而调 Memory/RAG）、`WorkflowRenderer`、`ChatModelPort`、`McpEngine`、`ToolIterationGuard`、`CircuitBreakerRegistry`、`RequestObservabilityLogger`。

### 当前实现
- 默认工作流 `customer-service/v2.3`（构造时 `WorkflowDefinitionLoader.load` 加载）。
- 响应式操作符链：`onBackpressureBuffer(256)` 防慢客户端 OOM；`onErrorResume` 全局降级；`doOnComplete`/`doOnError` 回写状态；`doFinally` 输出可观测日志。
- Inner Loop 通过字符串标记 `__TOOL_CALL__toolName{json}` 解析工具调用，而非标准 Function Calling 协议栈。

### 当前限制
- 意图分析是**关键词规则**（`KeywordIntentAnalyzer`），非模型分类，语义覆盖有限。
- 工具调用采用**字符串解析**方式（`extractToolName` / `extractJsonArgs`），依赖 LLM 输出格式稳定。
- `PromptAssembler` 类虽存在（[PromptAssembler.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/PromptAssembler.java)），但**未接入编排链路**——实际 Prompt 组装由 `WorkflowRenderer` 完成，该类为遗留未用代码。
- `llmLatencyMs` 通过 `System.nanoTime()` 差值除以 1_000_000 得到；在 Mock profile 下流式调用近乎同步完成，纳秒差可能截断为 `0ms`（P1-4 已记录的观测性瑕疵）。

---

## 2. Memory 模块（会话记忆）

### 为什么存在
让多轮对话具备上下文连续性，同时约束上下文长度（滑动窗口），并保证存储故障不中断主流程。

### 输入 / 输出
- 输入：`memoryId`（会话标识）+ `List<ChatMessage>`（待持久化消息）
- 输出：`List<ChatMessage>`（历史消息，无记录返回空列表）

### 核心类
| 类 | 职责 |
|----|------|
| [ChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/ChatMemoryStore.java) | 记忆存储端口（接口） |
| [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java) | MongoDB 实现 |
| [ChatSessionDocument.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/document/ChatSessionDocument.java) | Mongo 文档 |
| [ChatSessionRepository.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/repository/ChatSessionRepository.java) | Spring Data Repository |
| `ChatMessage` / `UserMessage` / `AiMessage` / `SystemMessage` | 消息多态模型 |

### 核心方法
- `getMessages(memoryId)` — 恢复上下文（[L62](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L62-L87)）
- `updateMessages(memoryId, messages)` — 原子覆写 + 滑动窗口截断（[L107](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L107-L144)）
- `deleteMessages(memoryId)` — 清除会话
- `applySlidingWindow` — FIFO 截断（[L172](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L172-L184)）
- `handleJsonMappingFailure` — 反序列化失败时重置会话（[L193](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L193-L207)）

### 被谁调用
- `ContextHydrationStep`（读历史）
- `ShopAgentOrchestrator`（`writeUserToMemory` / `writeAiToMemory` / `writeToolToMemory`）

### 调用谁
`ChatSessionRepository`（查询/删除）、`MongoTemplate`（upsert）。

### 当前实现
- MongoDB **upsert 原子覆写**（不先 delete 再 insert）。
- Jackson 多态反序列化区分 `UserMessage` / `AiMessage` / `SystemMessage`。
- 滑动窗口默认 **20 条**（`shopmind.memory.max-messages`）。
- 故障兜底：`MongoTimeoutException` / `DataAccessException` 均返回空列表，不抛异常。

### 当前限制
- 只保留最近 20 条，超过即丢弃更早上下文。
- `updateMessages` 是**全量读-改-写覆写**（调用方先 `getMessages` 再 append 后整体覆写），非增量追加，并发下存在覆盖风险（当前在线链路为单会话串行，未暴露）。
- 消息以 JSON 数组整段存于单文档，无独立索引/分片。

---

## 3. RAG 模块（知识检索）

### 为什么存在
将知识库中与用户问题相关的片段注入 Prompt，让 LLM 基于事实回答，并在无命中时显式引导拒答以抑制幻觉。

### 输入 / 输出
- 输入：`QueryRequest(query, topK, scoreThreshold)`
- 输出：`RetrievedContext(chunks, latency, cacheHit)`

### 核心类
| 类 | 职责 |
|----|------|
| [KnowledgeRetriever.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/api/KnowledgeRetriever.java) | 检索端口（接口） |
| [RetrievalPipeline.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/RetrievalPipeline.java) | 检索流水线 |
| [QueryCacheService.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/QueryCacheService.java) | Caffeine 查询缓存 |
| [EmbeddingProviderPort.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/port/EmbeddingProviderPort.java) | Embedding 端口 |
| [VectorStorePort.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/port/VectorStorePort.java) | 向量库端口 |
| [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java) | SHA-256 伪向量（256 维） |
| [DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java) | DashScope `text-embedding-v3`（1024 维） |
| [InMemoryVectorStoreAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/InMemoryVectorStoreAdapter.java) | LangChain4j 内存向量库 |
| [KnowledgeBootstrap.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/bootstrap/KnowledgeBootstrap.java) | 启动时加载知识库 |

### 核心方法
- `retrieve(QueryRequest)` — 主流程（[L56](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/pipeline/RetrievalPipeline.java#L56-L135)）
- `embed(String)` — 向量化（各 adapter 实现）
- `search(queryVector, topK)` — 语义检索
- `add(chunk, vector)` / `clear()` / `size()` — 向量库管理
- `lookup` / `put` — 缓存命中与写入

### 被谁调用
- `ContextHydrationStep`（在线链路）
- 评测/测试：`RetrievalPipelineDegradationTest`、`RealLlmBenchmarkTest`（RAG 检索评测）

### 调用谁
`QueryCacheService` → `EmbeddingProviderPort` → `VectorStorePort`。

### 当前实现
6 步流水线：**准入检查 → Query Cache → Embedding → Vector Search → Threshold Filter → Context Build**。
- 缓存命中直接返回，旁路后续 Embedding。
- 阈值过滤：丢弃低于 `scoreThreshold` 的块，全丢弃抛 `LowSimilarityException`。
- 降级：Embedding 超时 / 向量库断连均返回空上下文。

### 当前限制
- **DeepSeek profile 下 Embedding 是 Mock**（DeepSeek 不提供 Embedding API，`MockEmbeddingAdapter` 激活条件为 `!prod & !qwen`），RAG 语义检索不真实。
- 向量库为**内存态**（`InMemoryEmbeddingStore`），重启即丢失，需 `KnowledgeBootstrap` 重建。
- 静态知识库实际为 **30 chunks**（`knowledge-base.json`），不是部分文档声称的 80 chunks。
- 检索参数在线链路固定为 `topK=3, scoreThreshold=0.7`（见 `ContextHydrationStep`）。

---

## 4. Workflow 模块（工作流）

### 为什么存在
将 System Prompt 从代码中剥离为**版本化 YAML**，实现 persona / 工具规则 / 约束的独立演进，并支持 A/B 对照实验。

### 输入 / 输出
- 输入：`WorkflowInstance(definition + history + knowledge + userMessage)`
- 输出：渲染后的 System Prompt 字符串（`String`）

### 核心类
| 类 | 职责 |
|----|------|
| [WorkflowDefinitionLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowDefinitionLoader.java) | 从 classpath 加载 YAML |
| [WorkflowDefinitionYaml.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/WorkflowDefinitionYaml.java) | Map → 领域对象转换 |
| [WorkflowRenderer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/port/WorkflowRenderer.java) | 渲染端口（纯函数） |
| [WorkflowRendererImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java) | 渲染实现 |
| [WorkflowRegistry.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRegistry.java) | 工作流注册/查找 |
| `WorkflowDefinition` / `WorkflowInstance` / `ToolRule` / `Policy` | 领域模型 |

### 核心方法
- `load(workflowId, version)` — 加载 YAML（[L43](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowDefinitionLoader.java#L43-L78)）
- `render(WorkflowInstance)` — 渲染 System Prompt（[L44](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/pipeline/WorkflowRendererImpl.java#L44-L92)）

### 被谁调用
- `ShopAgentOrchestrator`（构造时加载，`stepPromptAssembly` 时渲染）
- 评测/测试（`WorkflowRendererImplTest`、`RealLlmBenchmarkTest` 遍历多版本）

### 调用谁
SnakeYAML（解析）、`WorkflowDefinitionYaml`（转换）。

### 当前实现
- 文件约定：`classpath:workflows/{workflowId}/{version}.yaml`。
- 渲染顺序：**Persona → Constraints（安全约束）→ ToolRules（可用工具）→ Knowledge / Guardrails**。
- 无知识命中时渲染"拒答引导"文案，抑制幻觉。
- 现有 8 个业务工作流版本 + 1 个消融定义（`ablation/mode_b.yaml`）。

### 当前限制
- `HARD` / `SOFT` 约束在渲染时**仅作为文本注入 Prompt**，无运行时强制校验，依赖 LLM 遵守。
- 默认工作流在 Orchestrator 构造时**固定**（`DEFAULT_WORKFLOW_VERSION = "v2.3"`），运行期切换靠 `setWorkflowDefinition`（注释标注仅供消融实验用）。

---

## 5. Tool (MCP) 模块（工具执行）

### 为什么存在
让 LLM 通过工具调用获取/操作业务数据（订单、会员等），从"只会说"扩展到"能做"。

### 输入 / 输出
- 输入：`toolName` + `jsonArguments`（LLM 生成的 JSON 字符串）
- 输出：工具执行结果字符串（成功返回业务结果，失败返回降级提示）

### 核心类
| 类 | 职责 |
|----|------|
| [McpEngine.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/McpEngine.java) | 工具引擎端口（接口） |
| [McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java) | 执行器（发现/映射/反射调用） |
| [ToolRegistry.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/registry/ToolRegistry.java) | `BeanPostProcessor` 注册中心 |
| [McpTool.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/annotation/McpTool.java) / [McpParam.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/annotation/McpParam.java) | 注解 |
| [OrderServiceTools.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/tools/OrderServiceTools.java) / [MemberServiceTools.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/tools/MemberServiceTools.java) | 业务工具 |

### 核心方法
- `discoverTools()` — 返回已注册工具列表
- `executeTool(toolName, jsonArguments)` — 查表 → 绑定 → 执行（[L72](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java#L72-L97)）
- `bindArguments` — JSON → 强类型参数（[L107](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java#L107-L153)）
- `invokeWithTimeout` — 反射调用 + 超时（[L164](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java#L164-L209)）
- `postProcessAfterInitialization` — 扫描 `@McpTool`（[L49](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/registry/ToolRegistry.java#L49-L92)）

### 被谁调用
- `ShopAgentOrchestrator`（`discoverTools` 供 Prompt 组装；`executeTool` 供 Inner Loop）

### 调用谁
`ToolRegistry`（注册表查询），最终通过 Java 反射 `Method.invoke` + `CompletableFuture.orTimeout` 调用业务 Bean。

### 当前实现
- `BeanPostProcessor` 在 Bean 初始化后扫描 `@McpTool` 方法并注册，工具名重复时抛异常。
- Jackson 将 JSON 反序列化为 `Map` 后逐参数类型转换。
- 反射调用带 **3s 超时**（`shopmind.mcp.timeout-ms`），超时/异常降级为可读字符串反哺 LLM。
- 已注册 4 个工具：`queryOrder`、`refund`、`queryPoints`、`queryCoupons`。

### 当前限制
- **非标准 MCP 协议**：无 JSON-RPC / stdio / 远程 MCP server，是"注解驱动的进程内反射工具注册"，命名上借用了 MCP。
- 工具数据为**内存示例数据**（`OrderServiceTools` / `MemberServiceTools` 注释明确），非真实订单/会员系统。
- 参数类型转换只覆盖基础类型（String/int/long/double/boolean）+ `Map` 复杂对象，不支持嵌套集合等复杂结构。

---

## 6. Evaluation 模块（评测）

### 为什么存在
用自动化 Benchmark 度量 Agent 质量，支撑 Workflow 版本 A/B、消融实验与后续科研，是"评估驱动"定位的核心。

### 输入 / 输出
- 输入：`EvaluationDataset`（用例集）+ `BenchmarkConfig`（并发/RPM/实验标识）
- 输出：`Mono<ExperimentReport>`（含指标汇总、失败归因、成本估算）

### 核心类
| 类 | 职责 |
|----|------|
| [BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java) | Benchmark 运行器 |
| [MetricEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/port/MetricEvaluator.java) | 指标评估端口 |
| [RuleBasedMetricEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/RuleBasedMetricEvaluator.java) | 规则评估（`@Profile("!deepseek")`） |
| [LlmJudgeMetricEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/LlmJudgeMetricEvaluator.java) | LLM-as-Judge（`@Profile("deepseek")`） |
| [SimpleFailureAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/SimpleFailureAnalyzer.java) | 失败归因 |
| [DatasetLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/dataset/DatasetLoader.java) | 数据集加载 |
| [ShopMindAgentAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/adapter/ShopMindAgentAdapter.java) | 适配 `EvaluableAgent` |
| `EvaluableAgent` / `FailureAnalyzer` / `TraceRecorder` | 端口 |

### 核心方法
- `run(dataset, config, isolationPrefix)` — 并发执行 + 聚合（[L115](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java#L115-L172)）
- `executeAndCollectTrace` — 驱动 Agent + 采集 Trace（[L193](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java#L193-L252)）
- `evaluate` — 指标评估（两种实现）
- `analyze` — 失败归因

### 被谁调用
- 测试：`EvaluationBenchmarkTest`（Mock 全量）、`RealLlmBenchmarkTest`（真实 LLM 矩阵）

### 调用谁
`EvaluableAgent`（驱动 Orchestrator）、`MetricEvaluator`、`FailureAnalyzer`、`TraceRecorder`、`RateLimiterRegistry`。

### 当前实现
- `Flux.flatMap(maxConcurrency)` + Resilience4j `RateLimiter`（`llmRateLimiter`）双重限流，防 LLM 厂商 429。
- 单个用例失败不中断整体，包装为带 `FailureReason` 的结果。
- 两代评估：`RuleBasedMetricEvaluator`（关键词匹配）与 `LlmJudgeMetricEvaluator`（5 维 0-100 语义评分）。
- 数据集：`datasets/v1.0/` 共 **126 用例**（7 场景）。

### 当前限制
- LLM-as-Judge 依赖 `deepseek` profile，API 调用量翻倍；**Judge 失败时默认判不通过**（`fallbackResult` 全维度 false），非"默认通过"。
- 前端 Dashboard 展示的是**静态 JSON**（`src/data/benchmark_v2.*.json`），尚未接后端评测 API。
- `LangChainAgentAdapter` / `OpenAIAdapter` 仅为骨架，未真正接入评测。
- Token 计数为估算（`estimateTokens` 按字符数折半），非精确计量。
