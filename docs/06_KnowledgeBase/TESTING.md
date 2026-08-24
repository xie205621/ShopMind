# ShopMind 测试地图（TESTING）

> 本文档属于 P1-5 工程知识库，**以 `backend/src/test/java` 下的真实测试代码为事实来源**。
> 建立从单元测试到 Docker E2E 的完整分层测试地图，并说明每层解决什么问题。

---

## 1. 测试全景（分层总览）

| 层 | 测试类 | 是否启动 Spring 容器 | 是否依赖外部服务 |
|----|--------|----------------------|------------------|
| Unit Test | WorkflowRendererImplTest / WorkflowDefinitionYamlTest / RequestObservabilityLoggerTest / RetrievalPipelineDegradationTest / ContextHydrationDegradationTest | 否 | 否 |
| Spring Integration Test | ShopAgentOrchestratorTest / McpEngineTest / KnowledgeEngineTest / MongoChatMemoryStoreTest | 是（`@SpringBootTest`） | embedded MongoDB |
| WebFlux HTTP Test | ChatControllerHttpTest | 是（`@WebFluxTest` 仅 Controller 层） | 否（`@MockBean`） |
| Stability Test | OrchestratorStabilityTest | 是（`@SpringBootTest`） | 否（`@MockBean ChatModelPort`） |
| Agent Benchmark（Mock） | EvaluationBenchmarkTest | 否（手动装配组件） | 否 |
| Real LLM Benchmark | RealLlmBenchmarkTest | 是（`@SpringBootTest`） | 真实 DeepSeek/Qwen API |
| P0-3 真实场景 | （手动验收，非 JUnit） | 真实运行 | 真实 Qwen API + MongoDB |
| Docker E2E | （手动验证，非 JUnit） | 真实运行 | Docker 三服务 |

测试统计（以 P1-4 报告为准）：**102 run / 0 fail / 7 skipped**。
> 注：README 中「81 tests」已过时。7 个 skipped 均来自 `RealLlmBenchmarkTest`（真实 LLM 用例），在非 `deepseek` profile 下被 `@EnabledIfSystemProperty` 条件禁用。

---

## 2. Unit Test（纯单元测试）

**解决什么问题**：在无 Spring 容器、无外部依赖的前提下，验证单个类/纯函数的正确性与边界。

| 测试类 | 验证内容 | 关键用例 |
|--------|----------|----------|
| [WorkflowRendererImplTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/workflow/pipeline/WorkflowRendererImplTest.java) | Prompt 渲染器纯函数 | persona/constraints/tools/knowledge 各 section 渲染、渲染顺序、H4 纯函数无副作用 |
| [WorkflowDefinitionYamlTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/workflow/domain/WorkflowDefinitionYamlTest.java) | YAML → 领域对象转换 | 空 Map 兜底、toolRules/constraints 解析、HARD/SOFT 级别、不可变列表 |
| [RequestObservabilityLoggerTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/observability/RequestObservabilityLoggerTest.java) | 可观测日志 | 结构化 JSON 字段完整、query/API Key 脱敏、requestId 唯一 |
| [RetrievalPipelineDegradationTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/knowledge/RetrievalPipelineDegradationTest.java) | RAG 降级 | Embedding 超时 / 向量库断连 → 降级空上下文不抛异常 |
| [ContextHydrationDegradationTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/pipeline/ContextHydrationDegradationTest.java) | 上下文水合降级 | Memory 读取失败 → 降级空历史，主链路继续 |

---

## 3. Spring Integration Test（容器级集成）

**解决什么问题**：在真实 Spring 容器 + **embedded MongoDB**（Flapdoodle，`de.flapdoodle.embed.mongo`，见 [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L112-L118)）下，验证模块间真实装配与数据流转，不 mock 关键依赖。

| 测试类 | 验证内容 | 关键用例 |
|--------|----------|----------|
| [ShopAgentOrchestratorTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/ShopAgentOrchestratorTest.java) | 编排全链路 | Outer Loop（闲聊/知识）、Inner Loop 工具调用、记忆回写、背压、意图分析 |
| [McpEngineTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/mcp/McpEngineTest.java) | MCP 工具引擎 | `@McpTool` 扫描注册、参数映射、异常降级、超时熔断 |
| [KnowledgeEngineTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/knowledge/KnowledgeEngineTest.java) | RAG 流水线 | 缓存命中/穿透、阈值过滤 LowSimilarityException、空查询边界 |
| [MongoChatMemoryStoreTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/memory/store/MongoChatMemoryStoreTest.java) | 会话记忆 | 滑动窗口截断、并发更新无脏数据、多租户隔离、多态反序列化 |

- 这些测试使用 **Mock LLM/Embedding**（`default` profile），不依赖真实 API，但 MongoDB 为真实 embedded 实例。

---

## 4. WebFlux HTTP Test（HTTP 层集成）

**解决什么问题**：只加载 Controller 层，用 `WebTestClient` 验证 `POST /api/chat` 的 SSE 协议、参数校验与异常兜底，隔离 Orchestrator 行为。

测试类：[ChatControllerHttpTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/api/ChatControllerHttpTest.java)（`@WebFluxTest(ChatController.class)` + `@MockBean ChatStreamingPort`）

| 用例 | 验证内容 |
|------|----------|
| Case A | 正常请求：2xx + `text/event-stream` Content-Type + Intent/Token/Done 事件序列 |
| Case B | `query` 为空 → SSE `Error[LLM_ERROR]` 事件 |
| Case C | Orchestrator 抛异常 → SSE `Error[LLM_ERROR]` 并正常结束（不崩溃） |
| Case D | 以 `Done` 结束，流正常 `complete` |

> 这是 P1-3 新增的 HTTP 层集成测试，解决此前"HTTP 层无自动化验证"的空白。

---

## 5. Stability Test（稳定性与异常处理）

**解决什么问题**：注入可控的 LLM 异常/挂起行为，验证超时重试、熔断、降级兜底，确保任何 LLM 故障都不中断主链路。

测试类：[OrchestratorStabilityTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/OrchestratorStabilityTest.java)（`@SpringBootTest` + `@MockBean ChatModelPort` + `@TestPropertySource` 缩短超时为 300ms）

| 用例 | 验证内容 |
|------|----------|
| LLM 超时异常 | 重试 2 次后降级为 `TIMEOUT` Error 事件并正常结束 |
| LLM 5xx 异常 | 不重试，直接降级 `LLM_ERROR` |
| LLM 挂起（无响应） | 超时后降级，不无限等待 |
| Circuit Breaker | 连续失败后进入 OPEN 状态 |

> 对应 P1-2 修复的 B1（outer loop 复用 `callLlm` 补 timeout）、B2（`minimum-number-of-calls: 5`）、B3（`isLlmTimeout` 沿 cause 链识别）。

---

## 6. Agent Benchmark（Mock 全量）

**解决什么问题**：不依赖真实 LLM，用规则评估器在固定数据集上跑通整个评测引擎，验证评测流水线本身正确性。

测试类：[EvaluationBenchmarkTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/EvaluationBenchmarkTest.java)（手动装配 `BenchmarkRunnerImpl` + `RuleBasedMetricEvaluator`）

| 用例 | 验证内容 |
|------|----------|
| 全量 Benchmark | 126 用例 × 并发 5 → JSON + Markdown 报告 |
| 平台级 Benchmark | 遍历全部 Workflow × Dataset v1.0 → Matrix 报告 |
| A/B Experiment | 加载 v2.0 / v2.1 JSON → 对比报告 |

- 数据集 `datasets/v1.0/` 共 **126 用例**（7 场景 JSON）。
- 产物写入 `../experiments` 与 `../reports`（已被 `.gitignore` 忽略）。

---

## 7. Real LLM Benchmark（真实 LLM + LLM-as-Judge）

**解决什么问题**：用真实 DeepSeek/Qwen API 跑全矩阵评测，用 LLM-as-Judge 做语义评估，产出可复现的实验报告。

测试类：[RealLlmBenchmarkTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/RealLlmBenchmarkTest.java)
- 注解：`@SpringBootTest @ActiveProfiles({"deepseek", "qwen"})`
- 条件启用：`@EnabledIfSystemProperty(named = "spring.profiles.active", matches = ".*deepseek.*")` → 非 deepseek profile 下**整体 skip**（即 7 个 skip 的来源）。

| 用例 | 验证内容 |
|------|----------|
| API Connectivity | 1 次真实请求 → HTTP 200 |
| Stream 诊断 | 查看原始流式响应 |
| Stream 修复验证 | 聚合后 `lines()` + `extractTokens` 产生 token |
| QWEN Embedding | 1 文本 → 验证真实 Embedding 响应 |
| Workflow Matrix | 7 版本 × 126 用例 × LLM-as-Judge |
| Ablation Study | Mode A (bare LLM) vs B (+Tool) vs C (+RAG+Guard) |
| RAG Retrieval | Hit@1 / Hit@3 |

运行方式（[L68-L72](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/RealLlmBenchmarkTest.java#L68-L72)）：
```powershell
cd backend
mvn test -Dtest="RealLlmBenchmarkTest#runWorkflowMatrix" `
    -Dspring.profiles.active=deepseek `
    -Dshopmind.llm.deepseek.api-key=sk-xxx
```

---

## 8. P0-3 真实场景（手动全链路验收）

**解决什么问题**：在真实 Qwen 全链路（真实 LLM + 真实 Embedding + 真实 MongoDB）下，验证端到端业务场景，补齐自动化 Benchmark 无法覆盖的"真实业务正确性"。

- **验收方式**：手动启动后端 `qwen` profile + 前端 dev server，通过 `POST /api/chat` 发起请求并观察 SSE 与日志（[P0-3_Acceptance_Report.md](file:///d:/A_big/ShopMind/docs/04_Evaluation/P0-3_Acceptance_Report.md)）。
- **环境**：`qwen` profile、`qwen-plus` + `text-embedding-v3`、知识库 30 chunks。
- **5 个场景**：普通知识问答 / RAG 知识检索 / Tool Calling / 多轮 Memory / 拒答（反幻觉）。全部通过。

> 关键结论（报告原文）：P0-3 必须用 `qwen` profile，因为只有 DashScope 同时提供对话与 Embedding；`deepseek` profile 无 Embedding，RAG 走 Mock，无法通过场景 2/5。

---

## 9. Docker E2E（手动验证）

**解决什么问题**：验证容器化部署的三服务能正常启动、网络互通、SSE 在 Nginx 代理下流式可用。

**当前实现**：**无自动化 Docker E2E 测试类**，Docker 验证为手动流程（P1-4 报告记录）：

1. `docker compose up -d --build` 构建并启动三服务；
2. `docker compose ps` 确认 `mongodb` 健康检查通过、`backend`/`frontend` 运行；
3. 浏览器访问 `http://localhost:80` 发起对话，验证 SSE token 流式渲染；
4. `docker compose down` 停止。

> 说明：Docker 层当前靠健康检查（[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml#L14-L19)）+ 手动冒烟验证，尚未有基于 Testcontainers / 端到端自动化的 JUnit 测试。

---

## 10. 全量测试运行方式

```powershell
# 1) 全量测试（default profile，Mock + embedded MongoDB）
cd d:\A_big\ShopMind\backend
mvn test

# 2) 仅稳定性测试
mvn test -Dtest=OrchestratorStabilityTest

# 3) 真实 LLM Benchmark（需真实 API Key）
mvn test -Dtest="RealLlmBenchmarkTest#runWorkflowMatrix" `
    -Dspring.profiles.active=deepseek `
    -Dshopmind.llm.deepseek.api-key=sk-xxx
```

- 默认 `mvn test` 下，`RealLlmBenchmarkTest` 的 7 个真实 LLM 用例被条件禁用（skip），其余 102 个用例在 Mock + embedded MongoDB 下执行。
