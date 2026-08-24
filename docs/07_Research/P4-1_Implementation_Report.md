# P4-1 实现报告：Runtime Context & Tool Static Risk Foundation

> 阶段：Phase 4 — RTMP Router（仅执行 P4-1）
> 状态：✅ 完成（READY FOR P4-2）
> 本阶段只建立 RTMP Router 的「合法输入基础」，不实现任何 Router / scoring / pruning / threshold / visibility 决策。

---

## 0. 这一阶段做了什么

P4-1 的目标是**建立 RTMP Router 的合法输入基础**，核心完成三件事：

1. **建立 `RouterContext`** —— Router 的合法运行时输入载体，只承载 `userQuery / conversationHistory / runtimeIntent / tool metadata`，以及四个「当前无真实 runtime 来源」的 Optional 占位字段（`intentConfidence / runtimeAuthorization / runtimeTargetScope / runtimeRequestType`），并严格与 Ground Truth 隔离。
2. **建立工具级静态风险 canonical source** —— 新增 `ToolStaticRiskProfile` + `ToolStaticRiskCatalog`，以 `toolName → ToolStaticRiskProfile` 提供 4 个生产工具的静态风险元数据，**不复用** `RtmpTestCase.toolRiskProfile`（case-level Ground Truth）作为最终来源。
3. **在 `ExperimentRuntimeConfig` 中语义分离 `verifierGroundTruth` 与 `routerContext`** —— Baseline B Verifier 继续读取完整 GT，Router 只能读取 `routerContext`，且无法通过 downcast / helper / getter 间接取得完整 `RtmpTestCase`。

**未实现（严格止步于 P4-1 边界）**：RTMP scoring、pruning、threshold、`RtmpVisibility`、`ToolMenuPruner`、`ToolVisibilityStrategy.apply(allTools, context)`、Router 本体，以及 System Prompt / Function Calling 双入口的任何修改。Baseline A/B 行为未做任何改动。

---

## 1. 修改文件

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `backend/src/main/java/com/shopmind/experiment/ExperimentRuntimeConfig.java` | 修改 | 新增 `routerContext` 字段、`of(condition, gt, routerContext)` 重载、`withRouterContext()`、`verifierGroundTruth()` 访问器；`defaults()`/`of(condition, gt)` 保持兼容 |
| `backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java` | 修改（1 行） | Verifier 调用点由 `runtime.groundTruth()` 改为 `runtime.verifierGroundTruth()`，使「Verifier 只读 verifierGroundTruth」的边界在调用点显式化；行为不变 |

其余既有文件（`BenchmarkRunnerImpl`、`ToolVisibilityStrategy`、`AllToolsVisibility`、`PostHocSafetyVerifier`、`ToolSpecification` 等）**均未修改**。

---

## 2. 新增类 / interface / metadata

| 类 | 类型 | 职责 |
|----|------|------|
| `com.shopmind.experiment.RouterContext` | record | Router 合法运行时输入，不含任何 GT 字段 |
| `com.shopmind.experiment.RouterContextFactory` | final class | 从 `OrchestrationContext` + 工具列表构建 `RouterContext`，隔离 GT |
| `com.shopmind.experiment.ToolRuntimeMetadata` | record | 组合 `ToolSpecification` 描述字段 + `ToolStaticRiskProfile`，供 Router 观察工具元数据 |
| `com.shopmind.experiment.ToolStaticRiskProfile` | record | 工具级静态风险 schema（复用 5 个定性维度，不新增维度、不做 score） |
| `com.shopmind.experiment.ToolStaticRiskCatalog` | final class | `toolName → ToolStaticRiskProfile` 的 canonical source（4 工具固定值） |
| `com.shopmind.experiment.RouterContextFoundationTest` | test class | P4-1 验收测试（13 个测试方法，覆盖 §八 1-12 + 配置分离补充） |

未新增任何 interface 到 `ToolVisibilityStrategy`（`apply(allTools, context)` 留待 P4-2）。

---

## 3. RouterContext 字段与真实来源

| 字段 | 类型 | 真实 runtime 来源 |
|------|------|------------------|
| `userQuery` | `String` | `OrchestrationContext.getUserMessage()`（来自 `OrchestrationRequest`，真实运行时） |
| `conversationHistory` | `List<ChatMessage>` | `OrchestrationContext.getHistory()`（来自 ChatMemoryStore，真实运行时） |
| `runtimeIntent` | `IntentAnalyzer.IntentResult` | `OrchestrationContext.getIntent()`（来自 IntentAnalyzer，真实运行时） |
| `intentConfidence` | `Optional<String>` | **无真实来源** → `Optional.empty()` |
| `runtimeAuthorization` | `Optional<String>` | **无真实来源** → `Optional.empty()` |
| `runtimeTargetScope` | `Optional<String>` | **无真实来源** → `Optional.empty()` |
| `runtimeRequestType` | `Optional<String>` | **无真实来源** → `Optional.empty()` |
| `toolMetadata` | `List<ToolRuntimeMetadata>` | `discoverWorkflowTools()` 的 `ToolSpecification`（toolName/description/parameters）+ `ToolStaticRiskCatalog` |

`ToolRuntimeMetadata` 内部字段：`toolName`、`description`、`parameters`、`staticRisk`（`ToolStaticRiskProfile`）。

---

## 4. 哪些字段目前没有 runtime source

以下 4 个字段目前**没有真实 runtime 来源**，按要求处理：

1. `intentConfidence`
2. `runtimeAuthorization`
3. `runtimeTargetScope`
4. `runtimeRequestType`

处理方式：
- **不**从 `RtmpTestCase` / `ContextRisk` 读取；
- **不**伪造 runtime 值；
- 统一填 `Optional.empty()`；
- 在 `RouterContextFactory` 源码注释中明确记录「当前无合法 runtime source」。

> 说明：`RtmpTestCase.contextRisk` 中确实存在 `intentConfidence / authorization / targetScope / requestType`，但它们是 **case-level Ground Truth**，属于 Router 禁止读取的范畴。若未来有真实的 runtime intent 识别置信度 / 运行时授权 / 目标范围 / 请求类型来源，再回填这 4 个字段。

---

## 5. Ground Truth isolation 设计

Router 与 GT 的隔离在**类型层面**强制实现：

- `RouterContext` 是一个纯 record，**只有 8 个字段**（见 §3），其中不含任何 GT 字段。Router 无法通过 `RouterContext` 访问：
  `expectedTool / expectedOutcome / expectedToolAction / taskCategory / riskLabel / adversarial / expectedReason / mockResponse / candidateTools / case-level toolRiskProfile / case-level contextRisk`。
- `RouterContextFactory.build(OrchestrationContext, List<ToolSpecification>)` 的两个入参都是运行时对象，**不接受 `RtmpTestCase`**，从源头阻断 GT 注入。
- 工具静态风险通过 `ToolStaticRiskCatalog.forTool(toolName)` 获取（工具名 → canonical），**不读取** `RtmpTestCase.toolRiskProfile`。
- `riskLabel` 继续由 `PostHocSafetyVerifier` 通过 `SafetyVerificationRequest.groundTruth` 读取；`RouterContext` 不暴露 `riskLabel`。

`RouterContext` 禁止携带的字段通过 `RouterContextFoundationTest` 中的反射断言逐一验证（测试 5-9，见 §8）。

---

## 6. ToolStaticRiskProfile 的 canonical source

新增 `ToolStaticRiskCatalog` 作为**工具级静态风险的 canonical source**，键为工具名，值固定，**不从任何具体 `RtmpTestCase` 动态生成**。

canonical 值取自 `docs/07_Research/RTMP_Dataset_Schema.md` §4.2「四工具风险属性」冻结表：

| 工具 | sideEffect | financialImpact | reversibility | dataSensitivity | permissionScope |
|------|-----------|-----------------|---------------|-----------------|-----------------|
| `queryOrder` | NONE | NONE | N_A | MEDIUM | OWN_DATA |
| `refund` | WRITE | HIGH | PARTIAL | MEDIUM | OWN_DATA |
| `queryPoints` | NONE | NONE | N_A | LOW | OWN_DATA |
| `queryCoupons` | NONE | NONE | N_A | LOW | OWN_DATA |

设计要点：
- `ToolStaticRiskProfile` 复用 `ToolRiskProfile` 的字段 schema（5 个定性维度），**不新增风险维度**、**不做数值 score 映射**（score 属 P4-2 及之后）。
- `ToolStaticRiskCatalog.forTool(String)` 只接收工具名，签名不含 `RtmpTestCase`，从 API 层面保证「非 case 派生」。
- 未登记工具返回 `Optional.empty()`（`ToolRuntimeMetadata.staticRisk` 可为 null）。

---

## 7. Baseline B Verifier 与 RouterContext 的边界

`ExperimentRuntimeConfig` 现在承载两类语义不同的数据：

```
ExperimentRuntimeConfig
├── verifierGroundTruth  ——  groundTruth() / verifierGroundTruth()：仅供 Baseline B ToolSafetyVerifier 读取
└── routerContext        ——  routerContext()：Router 的合法运行时输入（与 GT 严格隔离）
```

边界规则：
- **Baseline B Verifier**：通过 `SafetyVerificationRequest(runtime.verifierGroundTruth(), attemptedTool, args)` 读取完整 `RtmpTestCase`（含 `riskLabel` / 顶层 `authorization` / `contextRisk.targetScope`）。此能力保留不变（测试 10 验证）。
- **Router**：只能访问 `routerContext()`；`RouterContext` 不引用 `RtmpTestCase`，`ExperimentRuntimeConfig` 未提供任何把 `routerContext` 向下转型为 `RtmpTestCase` 的 helper / getter。
- `verifierGroundTruth()` 与 `groundTruth()` 返回同一对象，仅语义别名；`verifierGroundTruth()` 明确标注「Router 禁止调用」。
- `defaults()` 与 `of(condition, gt)` 保持兼容，`routerContext` 默认为 null（P4-1 尚未在运行时填充/消费）。

---

## 8. 测试列表与结果

新增测试类 `RouterContextFoundationTest`（纯单元测试，不依赖 Spring Context），13 个测试方法全部通过：

| # | 测试方法 | 覆盖要求 | 结果 |
|---|----------|----------|------|
| 1 | `routerContextHasUserQuery` | §八.1 userQuery | ✅ |
| 2 | `routerContextHasConversationHistory` | §八.2 history | ✅ |
| 3 | `routerContextHasRuntimeIntent` | §八.3 intent | ✅ |
| 4 | `fieldsWithoutRuntimeSourceStayEmpty` | §八.4 无 source 字段保持 empty | ✅ |
| 5 | `routerContextHasNoExpectedTool` | §八.5 | ✅ |
| 6 | `routerContextHasNoExpectedOutcome` | §八.6 | ✅ |
| 7 | `routerContextHasNoRiskLabel` | §八.7 | ✅ |
| 8 | `routerContextHasNoTaskCategory` | §八.8 | ✅ |
| 9 | `routerContextHasNoCaseLevelToolRiskProfile` | §八.9 | ✅ |
| 10 | `baselineBVerifierStillReadsGroundTruth` | §八.10 | ✅ |
| 11 | `catalogHasCanonicalEntryForAllFourTools` | §八.11 | ✅ |
| 12 | `catalogIsNotGeneratedFromSpecificTestCase` | §八.12 | ✅ |
| — | `runtimeConfigSeparatesVerifierGroundTruthFromRouterContext` | 补充：配置语义分离 | ✅ |

- 测试 5-8 通过反射断言 `RouterContext` 的 record component 不含对应字段。
- 测试 9 额外断言 `ToolRuntimeMetadata.staticRisk` 类型为 `ToolStaticRiskProfile`（非 case-level `ToolRiskProfile`）。
- 测试 12 用两个 `toolRiskProfile` 完全不同的 `RtmpTestCase` 证明 `ToolStaticRiskCatalog` 返回值不变。

§八.13（Legacy tests 全通过）与 §八.14（Phase 1-3 RTMP tests 全通过）由全量 `mvn test` 保证（见 §9）。

---

## 9. 全量 mvn test 结果

命令：`mvn test`（backend）

结果：**BUILD SUCCESS（exit code 0）**，`Tests run: 197, Failures: 0, Errors: 0`（含 7 个 `@Disabled` 的 Real LLM 测试 Skipped）。

新增测试类 `com.shopmind.experiment.RouterContextFoundationTest`：`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`。

关键既有 RTMP 测试类结果（Phase 1-3 全部通过）：
- `RtmpFoundationPhase1Test`：通过
- `RtmpInstrumentationPhase1BTest`：5 通过
- `RtmpPhase1CTest`：6 通过
- `RtmpPhase2BaselineABTest`：9 通过
- `RtmpPhase3MatrixIntegrationTest`：10 通过

（日志中出现的 `ERROR` / `WARN` 均为既有降级/超时/安全拦截的**预期场景日志**，非测试失败。）

---

## 10. 是否存在设计冲突

无阻塞性设计冲突。两点如实说明：

1. **`verifierGroundTruth()` 与 `groundTruth()` 并存**：二者语义等价，`verifierGroundTruth()` 仅为把「Verifier 专用 GT」边界显式化而新增的别名，未删除 `groundTruth()` 以避免破坏 `SafetyVerificationRequest` / 既有调用。若后续判定需要消除冗余，可在 P4-2 前收敛命名，但不影响当前正确性。
2. **`ToolStaticRiskCatalog` 数值的冻结依据**：canonical 值引用 `RTMP_Dataset_Schema.md` §4.2 的四工具风险属性表。该表本身是冻结工具级属性，独立于具体 case；本阶段未引入 score，故不产生与 P2 冻结规则的冲突。

未发现 `RouterContext` 暴露 GT、`ToolStaticRiskCatalog` 依赖 case、或 Verifier 读取路径被破坏的问题。

---

## 11. 是否严格遵守「不实现 Router / scoring / pruning」边界

**严格遵守。** 本阶段产出均为「输入基础」与「准备」：

- ✅ 建立了 `RouterContext` 与 `RouterContextFactory`（准备，未接入决策链路）。
- ✅ 建立了 `ToolStaticRiskProfile` / `ToolStaticRiskCatalog`（canonical source，无 score）。
- ✅ 在 `ExperimentRuntimeConfig` 分离了 `verifierGroundTruth` / `routerContext`（准备，未填充/消费 routerContext）。
- ❌ 未实现 `RtmpVisibility` / `ToolMenuPruner`。
- ❌ 未实现 RTMP scoring / pruning / threshold / relevance / risk score。
- ❌ 未扩展 `ToolVisibilityStrategy.apply(allTools, context)`（D1 留待 P4-2）。
- ❌ 未修改 System Prompt / Function Calling 双入口。
- ❌ 未修改 Baseline A/B 行为（`ExperimentCondition`、`AllToolsVisibility`、`PostHocSafetyVerifier` 均未变）。

**停止条件达成：P4-1 完成即止，未进入 P4-2。**
