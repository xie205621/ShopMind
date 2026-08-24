# P1-5 文档一致性审计报告（Document Consistency Report）

> 阶段：P1-5 工程知识库与项目文档整理
> 性质：**真实性审计** —— 以当前代码与最近验收结果为唯一事实来源，扫描文档中"代码无法支撑"的表述。
> 审计对象：`README.md`、`Enterprise_README.md`、`PROJECT_METRICS.md`、前端文案、`docs/` 全部文档。
> 审计原则：文档与代码冲突时，以当前代码和最近验收结果（P0 / P0-3 / P1-2 / P1-3 / P1-4）为准。

---

## 0. 审计结论（TL;DR）

- 共识别 **19 项不一致**，其中**高严重度 6 项**（数字矛盾 / 逻辑相反）、**中严重度 4 项**（过度宣传 / 无对应实现）、**低严重度 9 项**（过时 / 占位 / 代码瑕疵）。
- 最集中问题源是 **`PROJECT_METRICS.md`**（约占 14/19），其"简历/面试/论文"定位使其存在系统性**过度宣传**倾向。
- **`llmLatencyMs=0` 根因已定位为 Docker 旧镜像（缺计时逻辑），当前工作区代码已修复，运行时实测 2645ms**（详见第 3 节）。
- 19 项不一致中，**文档类项已在本阶段全部订正**（`PROJECT_METRICS.md` / `README.md` / `Enterprise_README.md` / `application.yml` 注释 / ADR 001·002）；代码类项 D4/D15/D17 依 P1-5「只整理不改功能」约束**不改代码**，仅在下方标注待后续处理。

---

## 1. 审计范围与方法

**方法**：
1. 逐行核对 `PROJECT_METRICS.md` 的每个量化指标与对应代码/资源；
2. 核对 `README.md`、`Enterprise_README.md` 的关键数字与最新验收报告；
3. 对"可追溯"的表述，用 `Grep`/`Read` 找到对应代码验证真伪；
4. 对无法验证的数字（如"97 源文件 / 6622 行"），标注"未逐一复核"。

**未纳入本报告**（属"工程规模统计"，需脚本复核）：`PROJECT_METRICS.md` 中的 `Java 源文件 97`、`代码 6622 行`、`包 19`、`接口 18`、`技术文档 18 份`。这些数字是否准确需用 `find`/`cloc` 重新统计，本阶段未逐个数，避免误判。

---

## 2. 不一致项清单

### 2.1 高严重度（数字矛盾 / 与代码逻辑相反）

#### D1 — 知识库 Chunk 数：80 vs 30（内部矛盾 + 与代码不符）
- **文档表述**：
  - [PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L50-L51)：「知识库总 Chunk 数 **80**」「商品25/售后15/物流10/会员10/支付10/FAQ10」
  - [PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L191)：「构建 **30 chunks** 真实业务知识库」
  - [PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L196)：「**30 chunks** + 售后/物流/支付/营销/会员/商品/安全/客服/订单 **9 大主题**」
- **代码事实**：静态资源 `backend/src/main/resources/knowledge/knowledge-base.json` 实际为 **30 chunks**；P0-3 / P0 报告均为 30。
- **根因**：`80 chunks` 是 [RealLlmBenchmarkTest.seedKnowledge()](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/RealLlmBenchmarkTest.java) 里**代码内置的测试知识库**，被误写成静态资源规模。
- **附带矛盾**：L51 主题是 **6 类**，L196 主题是 **9 类**，两个"主题分解"本身也对不上。
- **建议**：统一改为 30 chunks + 9 主题；80 chunks 若保留，须明确标注"仅真实 LLM Benchmark 测试数据"。

#### D2 — 测试数：81 vs 102（过时）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L24)「单元测试数 **81**」、[L191](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L191)「81 个测试用例」；`README.md` 亦写 81。
- **代码事实**：P1-4 最新为 **102 run / 0 fail / 7 skipped**（P1-3 亦为 102）。
- **建议**：更新为 102（run）/ 7（skipped），并注明 7 skipped 来自真实 LLM 用例的条件禁用。

#### D3 — 测试文件数：9 vs 14（不准确）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L23)「测试文件 **9**」。
- **代码事实**：`backend/src/test/java/com/shopmind/**` 下共 **14 个 .java**（13 个测试类 + 1 个辅助类 `MockBusinessService`）。
- **建议**：改为 13（测试类）或 14（含辅助类），并注明口径。

#### D4 — RealLlmBenchmarkTest 内部口径漂移
- **现象**：[RealLlmBenchmarkTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/RealLlmBenchmarkTest.java) 中 `seedKnowledge()` 构造 80 chunks，但注释/打印出现 `15 chunks`、`80 chunks`、`~80 chunks` 多种口径。
- **影响**：作为论文/实验数据时易被误引。
- **建议**：统一实验知识库规模口径，并在报告里区分"静态资源 30"与"测试数据集 80"。

#### D5 — Agent 框架适配器 3（过度宣传 + 内部矛盾）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L34)「Agent 框架适配器 **3** (ShopMind / LangChain / OpenAI SDK)」、[L200](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L200)「适配 ShopMind / LangChain / OpenAI SDK **三种** Agent 框架」。
- **代码事实**：[LangChainAgentAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/adapter/LangChainAgentAdapter.java#L71-L76) 与 [OpenAIAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/adapter/OpenAIAdapter.java#L61-L66) 的 `chat()` **均抛 `not yet implemented`**，是骨架；仅 `ShopMindAgentAdapter` 可用。
- **注意**：同一文档 [L161](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L161) 已诚实写「骨架已完成」，但 L34/L200 的"3 适配器/适配三种"与之矛盾。
- **建议**：L34 改为"1 可用 + 2 骨架"；L200 改为"已适配 ShopMind，LangChain/OpenAI SDK 为骨架预留"。

#### D6 — 「全链路零幻觉」（实验子集→普适结论）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L91)、[L191](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L191)、[L199](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L199)「**全链路零幻觉**」。
- **代码事实**：该结论来自消融实验 **28 cases**（10 正常 + 18 对抗，见 [L62](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L62)），且"幻觉=0"是 LLM-as-Judge 在**特定对抗子集**上的评估，不是全量/全链路。
- **建议**：改为"在 28 用例消融对抗集上 LLM-as-Judge 判幻觉率 0%"，删除"全链路"限定词。

#### D7 — Hit@1/Hit@3 被写成普适指标（实验子集→全量）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L53-L54)（核心量化指标）「Hit@1 **90%**」「Hit@3 **100%**」、[L196](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L196) 简历模板同。
- **代码事实**：该数据来自 **检索评测 10 个用例**（9/10、10/10，见 [L63](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L63) / [L97](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L97)），是**小样本**，被写进"核心指标"像全量。
- **建议**：标注样本量（n=10）。

### 2.2 中严重度（无对应实现 / 表述不区分 profile）

#### D8 — 「Reactor Context 全链路 Trace 传播」（无对应实现）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L202)「Reactor Context 全链路 Trace 传播」。
- **代码事实**：[RequestObservabilityLogger.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/observability/RequestObservabilityLogger.java) 通过 `OrchestrationContext` **对象引用**传递；[InMemoryTraceRecorder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/InMemoryTraceRecorder.java) 用普通 `Map`。`Grep` 全工程**无 `contextWrite` / `deferContextual`** 调用，"Reactor Context 透传"仅出现在 [TraceRecorder 接口注释](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/port/TraceRecorder.java#L24) 作为设计意图。
- **建议**：改为"请求级上下文对象 + 评测 Trace 记录器"，删除"Reactor Context 全链路传播"。

#### D9 — 「MCP 反射沙箱安全执行」（无沙箱）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L202)「MCP 反射沙箱安全执行」。
- **代码事实**：[McpExecutor.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/mcp/executor/McpExecutor.java#L172-L173) 是 `method.setAccessible(true)` + `method.invoke()` + `CompletableFuture` 超时，**无 `SecurityManager` / sandbox / 权限隔离**。
- **建议**：改为"MCP 反射调用 + 超时熔断"，删除"沙箱"。

#### D10 — 「Spring Boot 3.2 (WebFlux) / 全链路响应式非阻塞 (Reactor WebFlux)」（轻微夸大）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L117)、[L139](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L139)。
- **代码事实**：[pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml) 同时引入 `spring-boot-starter-web`（SSE 入站，Servlet/Tomcat）与 `spring-boot-starter-webflux`（`WebClient` 出站）。运行时是 **Servlet + Spring MVC 响应式返回类型 + WebClient**，非纯 Netty/WebFlux 运行时。
- **建议**：表述改为"基于 Project Reactor 响应式编程（SSE 流式 + WebClient 出站）"。

#### D11 — 「Judge 失败自动降级到默认通过」（与代码逻辑相反）
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L169)「Judge 失败自动降级到默认**通过**」。
- **代码事实**：[LlmJudgeMetricEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/LlmJudgeMetricEvaluator.java#L296-L306) `fallbackResult` 所有维度返回 `false`，注释明确「降级：默认**不通过**，避免误判为 PASS」。
- **建议**：改为"默认不通过"（方向完全相反，务必修正）。

#### D12 — Embedding 模型描述不区分 profile
- **文档表述**：[PROJECT_METRICS.md](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L52)、[L123](file:///d:/A_big/ShopMind/PROJECT_METRICS.md#L123) 将「DashScope text-embedding-v3」写成**唯一** Embedding。
- **代码事实**：仅 `qwen` profile 用 `text-embedding-v3`（1024 维）；`default`/`deepseek` profile 下是 `MockEmbeddingAdapter`（SHA-256 伪向量，256 维），见 [MockEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/MockEmbeddingAdapter.java#L20)。
- **建议**：标注"Embedding 随 profile 切换：qwen=text-embedding-v3，其余=Mock SHA-256"。

### 2.3 低严重度（文档缺失 / 占位 / 代码瑕疵）

#### D13 — `P1-1_Observability_Report.md` 缺失
- 用户指定阅读该报告，但 `docs/05_Engineering/` 下**不存在**（仅有 P0 / P1-2 / P1-3 / P1-4）。
- **建议**：补写 P1-1 报告，或在本阶段文档中说明其缺失。

#### D14 — ADR 001 / 002 为空文件
- `docs/05_ADR/001_Why_Mongo_For_Memory.md`、`002_Vector_Store_Choice.md` 为**空占位**，无实际决策内容。
- **影响**：DESIGN_DECISIONS 中"MongoDB 选型动机"因此标记为「待人工确认」。
- **建议**：补写这两个 ADR，或删除占位。

#### D15 — `PromptAssembler` 是未使用的遗留类
- 实际 Prompt 组装由 `WorkflowRenderer` 完成，`ShopAgentOrchestrator` 未 import `PromptAssembler`。
- **建议**：确认后删除遗留类，或标注 deprecated。

#### D16 — `application.yml` deepseek 默认模型名注释与值不一致
- [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L35-L36) 注释写「deepseek-chat / deepseek-reasoner」，但默认值为 `deepseek-v4-flash`。
- **建议**：统一注释与实际默认值。

#### D17 — `DashScopeEmbeddingAdapter` 日志判断用错变量（仅日志误报）
- [DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L47-L58) 构造函数日志判断用**构造参数** `apiKey` 而非已兜底的字段 `this.apiKey`；当仅靠 `System.getenv` 兜底拿到 Key 时会误报 "QWEN_API_KEY is empty"，但 `embed()` 用字段，功能正常。
- **建议**：最小修复（改判断变量），风险极低，可与 llmLatencyMs 一起评估。

#### D18 — RAG topK/threshold 硬编码
- `topK=3`、`scoreThreshold=0.7` 硬编码于 [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L97-L101)，非 yml 可配。若文档暗示"可配置"则不一致。

#### D19 — Docker E2E 无自动化测试
- Docker 验证为手动 `docker compose up` + 健康检查 + 冒烟，无 Testcontainers/自动化 E2E 测试类。已在本阶段 `TESTING.md` 如实标注，未夸大为自动化。

---

## 3. llmLatencyMs=0 评估（唯一允许的代码修改项）

### 3.1 现象
- P1-4 报告记录：真实 LLM 被调用（`totalLatencyMs≈2252ms`），但观测日志中 `llmLatencyMs` 未正确记录该耗时（近似 0）。

### 3.2 计时实现位置
- [ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java#L289-L291)：
  ```java
  long llmStartNanos = System.nanoTime();
  return callLlm(messages, tools)
      .doOnTerminate(() -> ctx.addLlmLatencyMs((System.nanoTime() - llmStartNanos) / 1_000_000))
      .flatMap(token -> handleLlmToken(...));
  ```
- 累加字段：`OrchestrationContext.addLlmLatencyMs()`；输出：`RequestObservabilityLogger.buildJson()` 的 `llmLatencyMs`。

### 3.3 排查过程（已排除的方向 + 运行时定位）
1. **adapter 是否真流式**：是。`DashScopeChatAdapter`/`DeepSeekChatAdapter` 均用 `bodyToFlux(String.class).concatMap(extractTokens)`，无 `collectList`/`buffer` 破坏流式。
2. **ctx 引用是否唯一**：是。`ctx` 单实例贯穿整个 `stream()`，`addLlmLatencyMs` 与 `getLlmLatencyMs` 操作同一对象。
3. **操作符顺序**：`doOnTerminate` 位于 `retryWhen` 之后，理论上在最终 complete 时触发。
4. **运行时定位（临时插桩，已移除）**：本地 `qwen` profile + 真实请求，`doOnSubscribe` / `doOnTerminate` / `doFinally` 三处日志实测输出 `llmLatencyMs=2645ms`（同请求 `totalLatencyMs=3863ms`），证明当前工作区计时逻辑正确；对照 Docker 旧镜像同场景输出 `llmLatencyMs=0`，判定为**镜像旧代码缺失计时逻辑**，非 Reactor 终止信号时序 bug。

### 3.4 结论（运行时验证后更新）
- **曾确认存在**：`llmLatencyMs=0` 在 P1-4 Docker 容器日志中真实出现。
- **根因已定位**：Docker 容器镜像为**旧代码**——观测日志框架（`RequestObservabilityLogger` 输出 `llmLatencyMs` 字段）已存在，但 `executeWithToolLoop()` 中 `doOnTerminate(() -> ctx.addLlmLatencyMs(...))` 的计时逻辑在镜像构建版本中尚未加入，导致字段恒为默认值 0。
- **修复状态**：**当前工作区代码已修复**。`addLlmLatencyMs` 计时逻辑已存在（本阶段仅移除临时 DEBUG 插桩，未改动该修复逻辑）；[RequestObservabilityLoggerTest](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/observability/RequestObservabilityLoggerTest.java) 已覆盖 `llmLatencyMs` 序列化断言。**Docker 镜像需重建**（`docker compose build backend`）才能让容器环境生效。

### 3.5 附带发现（可一起评估的最小修复）
- D17（`DashScopeEmbeddingAdapter` 日志判断变量）是**确认的、风险极低**的代码瑕疵，若后续允许，可做最小修复并补测试。

---

## 4. 全量测试结果

本阶段已重新执行 `mvn test`（default profile，Mock + MongoDB）：**102 run / 0 fail / 0 error / 7 skipped**，`BUILD SUCCESS`。
- 7 skipped 均来自 `RealLlmBenchmarkTest`（真实 LLM 用例），在非 `deepseek` profile 下被 `@EnabledIfSystemProperty` 条件禁用。
- 移除 DEBUG 插桩后无回归，结果与 P1-4 验收一致。

---

## 5. 文档修正建议清单（汇总）

> 状态说明：**「已订正」**＝源文档已改回与真实代码一致；**「不改代码（待后续）」**＝属代码实现层面，依 P1-5「只整理不改功能」约束本阶段不修改，仅标注。

| 优先级 | 项 | 动作 | 状态 |
|--------|-----|------|------|
| P0（必改） | D1 知识库 80→30、D2 测试数 81→102、D11 Judge 降级方向、D5 适配器 3→1+2骨架 | 立即修正 `PROJECT_METRICS.md` | ✅ 已订正 |
| P1（应改） | D6 零幻觉、D7 Hit@K 样本量、D8 Reactor Context、D9 沙箱、D10 WebFlux、D12 Embedding profile | 修正过度宣传表述 | ✅ 已订正 |
| P2（可改） | D3 测试文件数、D13 P1-1 缺失、D14 ADR 空文件、D16 注释、D18 硬编码、D19 Docker E2E | 补写/标注 | ✅ 已订正（D13 标注缺失、D18/D19 标注，见下） |
| P2（代码） | D4 口径、D15 遗留类、D17 日志变量 | 需改代码 | ⏸ 不改代码（待后续） |

### 5.1 各文档订正落点

| 文件 | 订正内容 |
|------|----------|
| `PROJECT_METRICS.md` | D1(30 chunks+9主题)、D2(102)、D3(13)、D5(1+2骨架)、D6(对抗集幻觉率0%)、D7(n=10)、D8(请求级上下文对象+Trace记录器)、D9(反射调用+超时熔断)、D10(Servlet+WebClient)、D11(默认不通过)、D12(profile 区分)、D18(硬编码标注) |
| `README.md` | D2(102)、D3(13)、D5(1+2骨架)、D6(对抗集0%)、D9(反射+熔断)、D10(Servlet) |
| `Enterprise_README.md` | D2(102)、D3(13)、D5(1+2骨架)、D9(反射+熔断)、D10(Servlet+Project Reactor) |
| `application.yml` | D16(注释对齐 `deepseek-v4-flash`) |
| `docs/05_ADR/001·002` | D14(补写决策事实，对比理由标「待人工确认」) |
| `README_RESEARCH.md` | D10(Servlet+WebClient)、D12(profile 区分) — 最终扫描补订正 |
| `P1-1_Observability_Report.md` | D13(不存在，不伪造，标注缺失) |

> **边界说明**：`docs/00_Product/MASTER_BLUEPRINT.md` 与 `docs/02_Specifications/*` 中仍存在「沙箱」字样，但均属**设计意图/需求描述**（如 RQ1 安全目标、Policy 示例、失败分类枚举），性质上不同于 PROJECT_METRICS/README 中「已实现沙箱」的实现宣称（D9），故不纳入本次订正。若后续要求「实现即事实」的彻底统一，可另立任务处理。

---

## 6. P1-5 完成情况

| 交付项 | 状态 |
|--------|------|
| 7 份知识库文档（ARCHITECTURE / MODULE_GUIDE / DESIGN_DECISIONS / LLM_CONFIGURATION / DEPLOYMENT / TESTING / INTERVIEW_GUIDE） | ✅ 已生成于 `docs/06_KnowledgeBase/` |
| 真实性审计报告（本文档） | ✅ 已生成 |
| 代码与文档不一致项 | ✅ 已识别 19 项并定位 |
| 文档类不一致订正 | ✅ 已全部订正（第 5 节）；代码类 D4/D15/D17 依约束未改代码 |
| llmLatencyMs=0 | ✅ 根因已定位（Docker 旧镜像），工作区代码已修复，实测 2645ms |
| 全量测试结果 | ✅ 本阶段重跑 102 run / 0 fail / 7 skipped |

> 19 项不一致中，文档类项已全部订正（见第 5 节）；仅 D4/D15/D17 三项代码类缺陷依 P1-5「只整理不改功能」约束未改代码，留待后续阶段处理。
