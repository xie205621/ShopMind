# ShopMind LLM 配置（LLM_CONFIGURATION）

> 本文档属于 P1-5 工程知识库，**以当前真实代码与 `application.yml` 为唯一事实来源**。
> 覆盖 LLM / Embedding 的 profile、模型名、参数、API Key 注入、超时与窗口等全部配置。

---

## 1. Profile 与实现映射总览

| Profile | Chat 模型（Adapter） | Embedding（Adapter） | 用途 |
|---------|----------------------|----------------------|------|
| `default`（无 profile） | `MockChatModelAdapter` → `"mock"` | `MockEmbeddingAdapter`（SHA-256 256 维） | 开发 / 测试，无外部依赖 |
| `qwen` | `DashScopeChatAdapter` → `qwen-plus` | `DashScopeEmbeddingAdapter`（`text-embedding-v3` 1024 维） | 真实全链路（Chat + Embedding 均为真实） |
| `deepseek` | `DeepSeekChatAdapter` → `deepseek-v4-flash` | `MockEmbeddingAdapter`（**无真实 Embedding**） | 真实 LLM，但 RAG 语义检索退化 |

激活条件（代码事实）：
- [MockChatModelAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L22) `@Profile("!prod & !qwen & !deepseek")`
- [DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L44) `@Profile("qwen")`
- [DeepSeekChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L45-L47) `@Component @Primary @Profile("deepseek")`
- [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L20) `@Profile("!prod & !qwen")`
- [DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L28) `@Profile("qwen")`

---

## 2. Mock Chat

| 项 | 值 | 依据 |
|----|----|------|
| 类 | `MockChatModelAdapter` | [MockChatModelAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java) |
| 模型名 | `"mock"` | `modelName()` 返回 |
| 激活 | `!prod & !qwen & !deepseek` | `@Profile` |
| API Key | 不需要 | 无鉴权逻辑 |
| 行为 | 关键词触发工具调用；默认返回固定文本流 | [L47-L58](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L47-L58) |

Mock 关键词 → 工具映射（[L47-L57](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/MockChatModelAdapter.java#L47-L57)）：
- 含 `退款`/`付款`/`支付` → `refund`
- 含 `订单`/`查订单`/`物流`/`快递` → `queryOrder`
- 含 `积分`/`会员` → `queryPoints`
- 其余 → 纯文本模拟回复

---

## 3. Qwen Chat（DashScope 通义千问）

| 项 | 值 | 依据 |
|----|----|------|
| 类 | `DashScopeChatAdapter` | [DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java) |
| 激活 | `@Profile("qwen")` | [L44](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L44) |
| Base URL | `https://dashscope.aliyuncs.com/api/v1` | [L49](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L49) |
| 路径 | `/services/aigc/text-generation/generation` | [L50](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L50) |
| 模型名 | `${QWEN_CHAT_MODEL:qwen-plus}` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L28) |
| 请求参数 | `result_format=message`、`incremental_output=true` | [L266-L267](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L266-L267) |
| SSE 头 | `X-DashScope-SSE: enable` | [L109](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L109) |
| 重试 | `Retry.backoff(2, 500ms)`，仅 429 | [L124-L125](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java#L124-L125) |

---

## 4. DeepSeek Chat

| 项 | 值 | 依据 |
|----|----|------|
| 类 | `DeepSeekChatAdapter` | [DeepSeekChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java) |
| 激活 | `@Component @Primary @Profile("deepseek")` | [L45-L47](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L45-L47) |
| Base URL | `https://api.deepseek.com` | [L52](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L52) |
| 路径 | `/v1/chat/completions`（OpenAI 兼容） | [L53](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L53) |
| 模型名 | `${DEEPSEEK_CHAT_MODEL:deepseek-v4-flash}` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L36) |
| 请求参数 | `stream=true`、`temperature=0.1` | [L257-L278](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L257-L278) |
| 连接超时 | 响应 120s、连接 30s | [L74-L77](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L74-L77) |
| 重试 | `Retry.backoff(2, 1000ms)`，429/5xx | [L135-L137](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L135-L137) |

> 关于模型名：`application.yml` 注释写「deepseek-chat / deepseek-reasoner」，但**默认值为 `deepseek-v4-flash`**（[application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L35-L36)）。实际使用请以环境变量 `DEEPSEEK_CHAT_MODEL` 显式指定为准，避免默认值语义不清。

---

## 5. Embedding

### 5.1 Mock Embedding

| 项 | 值 | 依据 |
|----|----|------|
| 类 | `MockEmbeddingAdapter` | [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java) |
| 激活 | `!prod & !qwen` | [L20](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L20) |
| 算法 | SHA-256 哈希，字节映射到 `[-1,1]` | [L32-L50](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L32-L50) |
| 维度 | **256** | [L24](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L24) |
| 性质 | 确定性伪向量，**非语义向量** | 注释 [L13-L15](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L13-L15) |

### 5.2 DashScope Embedding（真实）

| 项 | 值 | 依据 |
|----|----|------|
| 类 | `DashScopeEmbeddingAdapter` | [DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java) |
| 激活 | `@Profile("qwen")` | [L28](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L28) |
| 路径 | `/services/embeddings/text-embedding/text-embedding` | [L34](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L34) |
| 模型名 | `${QWEN_EMBEDDING_MODEL:text-embedding-v3}` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L30) |
| 维度 | **1024**（零向量默认值） | [L72](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L72) |
| 调用方式 | **阻塞式** `.block(30s)`，非流式 | [L93](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L93) |
| 文本类型 | `text_type=query` | [L120](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L120) |

### 5.3 DeepSeek Embedding 能力限制（重点）

- **DeepSeek 不提供 Embedding API**（代码注释明确：[DeepSeekChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DeepSeekChatAdapter.java#L43)）。
- 因此 `deepseek` profile 下没有真实 Embedding 实现，`MockEmbeddingAdapter` 因 `@Profile("!prod & !qwen")`（未排除 `deepseek`）被激活。
- **结论**：`deepseek` profile 下 RAG 语义检索不真实（伪向量无法表达语义相似度），仅能验证 RAG 流水线的控制流，不能验证检索质量。要获得真实 RAG，必须使用 `qwen` profile。

---

## 6. API Key 注入方式

所有 Key 通过 `application.yml` 的占位符从环境变量读取，运行期再被 `@Value` 注入到 adapter：

| 配置项 | 环境变量 | 默认值 |
|--------|----------|--------|
| `shopmind.llm.qwen.api-key` | `QWEN_API_KEY` | 空 |
| `shopmind.llm.qwen.chat-model` | `QWEN_CHAT_MODEL` | `qwen-plus` |
| `shopmind.llm.qwen.embedding-model` | `QWEN_EMBEDDING_MODEL` | `text-embedding-v3` |
| `shopmind.llm.deepseek.api-key` | `DEEPSEEK_API_KEY` | 空 |
| `shopmind.llm.deepseek.chat-model` | `DEEPSEEK_CHAT_MODEL` | `deepseek-v4-flash` |

- 见 [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L24-L36)。

**各 adapter 的兜底差异**：
- `DeepSeekChatAdapter` 与 `DashScopeEmbeddingAdapter` 在 `@Value` 注入为空时，额外从 `System.getenv(...)` 兜底一次。
- `DashScopeChatAdapter` 无 `System.getenv` 兜底（依赖 `application.yml` 的 `${QWEN_API_KEY:}` 展开）。

**已知小瑕疵（不影响功能）**：
- [DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L47-L58) 构造函数把兜底结果赋给字段 `this.apiKey`，但日志判断 `if (apiKey == null || apiKey.isBlank())` 用的是**构造参数** `apiKey` 而非字段。当 Spring 未注入、仅靠 `System.getenv` 兜底拿到 Key 时，日志会误报 "QWEN_API_KEY is empty"，但 `embed()` 方法实际使用字段 `this.apiKey`，功能正常。

---

## 7. 全局参数配置表

| 配置项 | 值 | 配置位置 | 代码消费方 |
|--------|----|----------|------------|
| 会话滑动窗口 | `max-messages: 20` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L13) | `MongoChatMemoryStore` |
| 查询缓存大小 | `max-size: 1000` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L16) | `QueryCacheService` |
| 查询缓存 TTL | `ttl-minutes: 30` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L17) | `QueryCacheService` |
| 工具执行超时 | `timeout-ms: 3000` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L19) | `McpExecutor` |
| LLM 流式调用超时 | `timeout-ms: 30000` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L22) | `ShopAgentOrchestrator.callLlm` |
| Inner Loop 最大迭代 | `max-iterations: 3` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L39) | `ToolIterationGuard` |

### RAG 检索参数（在线链路固定值）
- `topK = 3`、`scoreThreshold = 0.7`，**硬编码**于 [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L97-L101)，非 `application.yml` 可配。

### Resilience4j 参数

| 组件 | 实例名 | 关键参数 | 依据 |
|------|--------|----------|------|
| CircuitBreaker | `llmProvider` | 滑动窗口 5、最小调用 5、失败率 50%、开态 15s | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L53-L57) |
| RateLimiter | `llmRateLimiter` | 30 次/分钟 | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L66-L70) |
