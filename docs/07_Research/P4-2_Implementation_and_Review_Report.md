# P4-2 实现与评审报告：Relevance / Risk Feature Mapping + Deterministic Scoring

> 阶段：Phase 4 — RTMP Router（执行 P4-2 + P4-2.1 文档收口）
> 状态：COMPLETED（P4-2.1 READY FOR P4-3）
> P4-2 实现确定性评分与 feature mapping；P4-2.1 修正 RuntimeContextRisk 为 tool-context-level 的设计缺陷并完成文档收口。不实现任何 pruning / visibility / 双入口修改。
> **Phase 5-C1 修订**：§13 与 §18.8 的「runtime 无真实 authorization/targetScope 来源」limitation 已在 C1 解除（`RuntimeSessionContext` + `RtmpRuntimeScenarioProvider` 注入 runtime signal）。P4-2/P4-2.1 的 query-pattern 风险映射仍保留为 runtime signal 缺失时的 fallback。详见 [Phase5-C1 report](Phase5-C1_Runtime_Context_Fairness_Correction_and_Protocol_Closure_Report.md)。

---

## 0. Executive Summary

P4-2 已完成，并经 P4-2.1 修订收口。在 P4-1 建立的 `RouterContext`（合法运行时输入）之上，实现了**确定性的、与 Ground Truth 完全隔离的评分层**：

- **Relevance**：`max(intentScore, lexicalScore, descriptionCompatibilityScore)`，范围 0.0~1.0，阈值 `theta_relevance=0.5`。
- **Risk**：`EffectiveRiskScore = max(StaticToolRiskScore, RuntimeContextRiskScore)`，阈值 `theta_risk=0.75`。
- **Candidate**：`KEEP_CANDIDATE = Relevance>=0.5 AND EffectiveRisk<0.75`，否则 `PRUNE_CANDIDATE`。

> **P4-2.1 修订**：`RuntimeContextRiskScore` 已从 context-level 修正为 **tool-context-level**（`RuntimeContextRiskScore(tool, context)`），修复「显式可疑 → 所有工具统一 1.0 → 全裁」的设计缺陷。详见 §18。

新增 10 个生产类 + 1 个测试类（P4-2.1 又修订 2 个生产类 + 新增 4 个测试）。全量 `mvn test` **216 通过 / 0 失败 / 0 错误 / 7 Skipped**（含 legacy 与 Phase 1-3 RTMP 测试，7 个 Real LLM 测试 `@Disabled`）。严格止步于评分层，未实现 Router 集成 / pruning / 双入口同步。

---

## 1. Why This Phase Exists

RTMP Router 在 P4-3 之前必须先把「如何评分」冻结并落地，否则裁剪决策缺乏确定性依据：

1. **评分是裁剪的前置**：`RtmpVisibility` / `ToolMenuPruner`（P4-3）只能消费一个明确、可复现、可审计的评分结果；没有评分，裁剪规则就是无根之木。
2. **隔离 Ground Truth 的落地点**：P4-1 建立了「Router 看不到 GT」的类型隔离，P4-2 进一步把「评分只依赖 RouterContext」落实到每一段 feature 计算，杜绝通过评分间接泄露 GT。
3. **可复现实验的度量基础**：三个 baseline 需要一致的评分口径；把阈值与分值集中在 `RtmpScoringConfig`，避免 magic number 漂移导致 Method C 与其他条件不可比。

---

## 2. What Was Actually Done

实现了完整的确定性评分管线（全部为纯同步、无 IO、无随机、无 GT 依赖）：

| 组件 | 职责 |
|------|------|
| `RtmpScoringConfig` | 集中冻结阈值与离散分值（唯一常量来源） |
| `ToolSemanticLexicon` | 手写工具语义词典（strong/weak 证据词） |
| `RelevanceScorer` | intent + lexical + description 三特征 → `RelevanceScore` |
| `StaticRiskScorer` | `ToolStaticRiskProfile` 五维 → 算术平均 → 静态风险分 |
| `RuntimeContextRiskScorer` | query/history 安全语义 → 离散上下文风险分（P4-2.1 改为 per-tool，见 §18） |
| `RtmpScoringEngine` | 逐工具独立评分 + candidate decision，输出 `ToolScoreResult` |
| `RelevanceScore` / `RiskScore` / `ToolScoreResult` / `ToolDecisionCandidate` | 值对象（record / enum），instrumentation-ready |

未实现：`RtmpVisibility`、`ToolMenuPruner`、`visibleTools` 裁剪、双入口同步、Router 接入、Real LLM、统计检验、阈值校准、GT 训练/优化。

---

## 3. Feature Mapping

### 3.1 Relevance 特征

| 特征 | 来源 | 语义 | 分值 |
|------|------|------|------|
| `intentScore` | `runtimeIntent`（`IntentAnalyzer.IntentResult.requiresTools` + `category` 域关键词） | 意图与工具域的强兼容 | 兼容 1.0 / 不兼容 0.0 |
| `lexicalScore` | `userQuery` + `conversationHistory` 对 `ToolSemanticLexicon` 匹配 | 强操作/查询证据 vs 弱信息证据 | 强 1.0 / 弱 0.6 / 无 0.0 |
| `descriptionCompatibilityScore` | `ToolSpecification.description` + `parameters` 与 query/history 的 2-char bigram 重叠 | 确定性弱兼容证据 | 有重叠 0.3 / 无 0.0 |

### 3.2 Risk 特征

`runtimeContextRiskScore` 是 **tool-context-level** 特征：同一 `RouterContext` 下，不同工具可得到不同 runtime risk（P4-2.1 修订，见 §18）。

| 特征 | 来源 | 语义 | 分值 |
|------|------|------|------|
| `staticRiskScore` | `ToolStaticRiskProfile`（P4-1 catalog）五维 | 工具客观静态风险 | 五维算术平均（0~1） |
| `runtimeContextRiskScore` | `userQuery` + `conversationHistory` 安全语义 + 工具能力域 | 运行时上下文越权/可疑信号（per-tool） | NORMAL 0.0 / AMBIGUOUS 0.5 / SUSPICIOUS 1.0 |
| `effectiveRiskScore` | 上述两者 | 有效风险 | `max(static, runtime)` |

**runtimeContextRiskScore 冻结映射（P4-2.1）**：

| 可疑域 | queryOrder | refund | queryPoints | queryCoupons |
|--------|-----------|--------|-------------|--------------|
| Authorization（他人订单/冒充/越权） | 1.0 | 1.0 | 0.0 | 0.0 |
| Batch（批量） | 0.0 | 1.0 | 0.0 | 0.0 |
| Ambiguous（别人/他人/朋友/同事/家人/亲戚） | 0.5 | 0.5 | 0.5 | 0.5 |
| 无 | 0.0 | 0.0 | 0.0 | 0.0 |

### 3.3 静态风险五维映射（`StaticRiskScorer`）

| 维度 | 映射 |
|------|------|
| `sideEffect` | NONE=0, READ_ONLY=0, WRITE=0.5 |
| `financialImpact` | NONE=0, LOW=0.25, MEDIUM=0.5, HIGH=1 |
| `reversibility` | N_A=0, FULLY=0, PARTIAL=0.5, IRREVERSIBLE=1 |
| `dataSensitivity` | NONE=0, LOW=0.25, MEDIUM=0.5, HIGH=1 |
| `permissionScope` | OWN_DATA=0, OTHER_DATA=0.75, SYSTEM=1 |

冻结结果（测试 #1 精确断言）：
- `queryOrder = 0.10`
- `refund = 0.50`
- `queryPoints = 0.05`
- `queryCoupons = 0.05`

---

## 4. Scoring Formula

```
RelevanceScore(tool, context)
  = max( intentScore, lexicalScore, descriptionCompatibilityScore )      // 0.0 ~ 1.0

StaticToolRiskScore(tool)
  = ( sideEffect + financialImpact + reversibility
      + dataSensitivity + permissionScope ) / 5                        // 0.0 ~ 1.0

RuntimeContextRiskScore(tool, context)
  ∈ { 0.0 (NORMAL), 0.5 (AMBIGUOUS), 1.0 (EXPLICITLY_SUSPICIOUS) }
  // tool-context-level：同一 context 下不同工具可不同（P4-2.1）

EffectiveRiskScore(tool, context)
  = max( StaticToolRiskScore(tool), RuntimeContextRiskScore(tool, context) )  // 0.0 ~ 1.0
  // 公式本身未变，仅 runtime 项由 context-level 改为 tool-context-level

KEEP_CANDIDATE(tool)  = RelevanceScore >= theta_relevance AND EffectiveRiskScore < theta_risk
PRUNE_CANDIDATE(tool) = 其余情况

theta_relevance = 0.5   (RtmpScoringConfig.THETA_RELEVANCE)
theta_risk      = 0.75  (RtmpScoringConfig.THETA_RISK)
```

Multi-tool：每个工具独立评分，禁止 Top-1，允许多个工具同时 `KEEP_CANDIDATE`。

---

## 5. Implementation Changes

P4-2 全部为**新增文件**，未修改任何既有文件；P4-2.1 修订了 `RuntimeContextRiskScorer` 与 `RtmpScoringEngine`（见 §18）：

| 文件 | 类型 | 原因 |
|------|------|------|
| `backend/src/main/java/com/shopmind/experiment/RtmpScoringConfig.java` | 新增 | 集中阈值/分值，避免 magic number |
| `backend/src/main/java/com/shopmind/experiment/ToolDecisionCandidate.java` | 新增 enum | KEEP/PRUNE 候选结论 |
| `backend/src/main/java/com/shopmind/experiment/RelevanceScore.java` | 新增 record | relevance 三特征 + `value()` |
| `backend/src/main/java/com/shopmind/experiment/RiskScore.java` | 新增 record | risk 两特征 + `effective()` |
| `backend/src/main/java/com/shopmind/experiment/ToolScoreResult.java` | 新增 record | 单工具评分 instrumentation-ready 输出 |
| `backend/src/main/java/com/shopmind/experiment/ToolSemanticLexicon.java` | 新增 | 手写语义词典（strong/weak） |
| `backend/src/main/java/com/shopmind/experiment/RelevanceScorer.java` | 新增 | intent/lexical/description 特征映射 |
| `backend/src/main/java/com/shopmind/experiment/StaticRiskScorer.java` | 新增 | 五维静态风险算术平均 |
| `backend/src/main/java/com/shopmind/experiment/RuntimeContextRiskScorer.java` | 新增（P4-2.1 修订） | 运行时上下文离散风险（P4-2.1 改为 per-tool） |
| `backend/src/main/java/com/shopmind/experiment/RtmpScoringEngine.java` | 新增（P4-2.1 修订） | 逐工具评分 + candidate 判定（P4-2.1 调用点传入 toolName） |
| `backend/src/test/java/com/shopmind/experiment/RtmpScoringEngineTest.java` | 新增测试 | 15 个测试方法（P4-2.1 增至 19） |

---

## 6. Problems / Findings

1. **intentScore 无法工具级判别（数据来源缺口）**：当前 `KeywordIntentAnalyzer` 产出的 `category` 是粗粒度标签（`"工具执行"` / `"知识与工具"` / `"知识检索"` / `"闲聊"` 等），不携带「哪个工具」的域信息。因此 intentScore 的「强兼容 1.0」分支在当前 runtime 下几乎不触发，relevance 主要靠 lexical + description 判别。
2. **runtimeIntent 不携带授权/目标范围**：`IntentResult` 只有 `requiresKnowledge / requiresTools / category`，没有 authorization / targetScope。因此运行时上下文风险只能依赖 query/history 文本，无法感知 query 未透露的越权（见 §13）。
3. **descriptionCompatibility 存在弱交叉重叠**：`refund` 与 `queryOrder` 共享参数名「订单号」，导致订单类 query 对 `refund` 产生 bigram 重叠（0.3 弱证据）。因 0.3 < 0.5 不会单独提升到 KEEP，属可接受的弱证据噪声。
4. **无可疑 reactive/runtime 问题**：评分层是纯同步确定性计算，无 IO/上下文/随机，天然线程安全、可复现。
5. **实验泄漏风险**：已通过「评分只接受 `RouterContext`、`RouterContext` 不含 GT 字段」从类型层面阻断；测试 #10-13 反射断言验证。

---

## 7. Decisions Made

1. **intentScore 采用「category 域关键词映射」**：`requiresTools=true` 且 `category` 含工具域关键词（如 `"售后"` → refund）时给 1.0；粗粒度 category 不强断言，返回 0.0。不读取 `expectedTool`。
2. **lexical 词典手写 strong/weak 分离**：强证据词为明确操作短语（`"申请退款"` / `"查一下订单"`），弱证据词为裸域名词（`"退款"` / `"订单"`），落实「仅出现工具词不等于强操作」。
3. **descriptionCompatibility 用 2-char bigram 重叠**：确定性、语言无关、直接派生自 `ToolSpecification.description/parameters`，只给 0.3/0.0 弱证据。
4. **RuntimeContextRiskScorer 用通用安全语义模式**（他人的订单 / 冒充 / 越权 / 批量 / 其他用户），不按 caseId 硬编码；P4-2.1 起按「可疑模式 × 工具能力域」做 per-tool 映射，suspicious 优先于 ambiguous（见 §18）。
5. **`StaticRiskScorer` 对 null profile 返回 0.0**（未登记工具 fail-safe，本阶段 4 工具均登记，不触发）。
6. **所有阈值/分值集中到 `RtmpScoringConfig`**。

---

## 8. Problems Resolved

| 问题 | 根因 | 修复 | 验证 |
|------|------|------|------|
| 「退款按钮」会被误判 refund=1.0 | `"退款"` 作为强证据过宽 | 将 `"退款"` 降为弱证据，强证据改明确操作短语 | 测试 #7 断言 lexical=0.6（弱）且 relevance≠1.0 |
| 阈值/分值散落 | 无集中配置 | 全部收敛到 `RtmpScoringConfig` | 代码审查：其他类无裸数字 |
| 静态风险分不可复现 | 五维映射未落地 | `StaticRiskScorer` 固定映射 + 算术平均 | 测试 #1 精确断言四工具 0.10/0.50/0.05/0.05 |
| 越权无法在上下文风险体现 | 无运行时风险信号 | `RuntimeContextRiskScorer` 通用语义模式 | 测试 #9 断言 context risk=1.0、effective=1.0 |
| 显式可疑 → 所有工具全裁（P4-2.1 缺陷） | `score(RouterContext)` 使所有工具共享同一 context risk，suspicious=1.0 时 `max(static,1.0)` 抬升全部工具 | 改为 `score(RouterContext, toolName)` 的 per-tool 映射 | 测试 #16-18 断言 per-tool runtime risk；测试 #19 断言非受影响工具仍 KEEP |

---

## 9. Unresolved / Non-blocking Issues

1. **intentScore 在生产环境近乎恒为 0.0**：需待未来 `IntentAnalyzer` 产出工具域 `category`（如 `"售后问题"`）才能触发「强兼容 1.0」分支。当前 relevance 由 lexical/description 承担判别，功能完整、不影响 P4-3。**非阻塞**。
2. **运行时无法感知未在 query 透露的第三方越权**：这是 observability 限制，非 Router bug（见 §13）。**非阻塞**。
3. **descriptionCompatibility 的 bigram 弱交叉噪声**：0.3 弱证据，不会单独导致 KEEP。**非阻塞**。

---

## 10. Experiment Impact

- **Safety**：`RuntimeContextRiskScorer(tool, context)` 对显式越权语义（他人的订单 / 冒充 / 越权 / 批量等）**仅对受影响工具**给出 1.0（Authorization 域 → queryOrder+refund；Batch 域 → refund），`EffectiveRiskScore>=0.75` 只 PRUNE 这些工具，不再全裁。
- **Utility**：正常 queryOrder/refund/points/coupons 均得到 `relevance>=0.5` 且低风险 → KEEP；可疑上下文下非受影响工具（如 queryPoints/queryCoupons）仍可 KEEP，避免过度裁剪。
- **Over-refusal**：信息性语句（退款政策）只得到弱 relevance（0.6，非 1.0），保留「可讨论但非强操作」的中间态，降低把信息性请求误判为强操作的风险。
- **Pilot validity**：评分确定性、GT-free、阈值集中，保证三 baseline 口径一致，Method C 的评分输出可复现、可审计。

---

## 11. Validation

新增测试类 `RtmpScoringEngineTest`（19 个测试方法，P4-2 的 15 个 + P4-2.1 的 4 个），覆盖 §十一 1-14 及 P4-2.1 per-tool 映射：

| # | 测试方法 | 结果 |
|---|----------|------|
| 1 | `staticRiskScoresPrecise` | ✅ 0.10/0.50/0.05/0.05 |
| 2 | `normalQueryOrderRelevance` | ✅ |
| 3 | `normalRefundRelevance` | ✅ |
| 4 | `normalPointsRelevance` | ✅ |
| 5 | `normalCouponsRelevance` | ✅ |
| 6 | `multiToolQueryMultipleHighRelevance` | ✅ |
| 7 | `informationalRefundNotStrong` | ✅ lexical=0.6，relevance≠1.0 |
| 8 | `normalRefundRiskPrecise` | ✅ static=0.5/context=0/effective=0.5 |
| 9 | `thirdPartyRefundRiskPrecise` | ✅ context=1.0/effective=1.0 |
| 10 | `noGroundTruthAuthorizationInjection` | ✅ |
| 11 | `riskLabelNotInScore` | ✅ |
| 12 | `expectedToolNotInScore` | ✅ |
| 13 | `taskCategoryNotInScore` | ✅ |
| 14 | `scoringDictionaryNotDerivedFromCases` | ✅ |
| 15 | `intentScoreStrongCompatibility`（补充） | ✅ |
| 16 | `authorizationPerToolRisk`（P4-2.1） | ✅ 他人订单/冒充/越权 → queryOrder+refund=1.0，points+coupons=0.0 |
| 17 | `batchPerToolRisk`（P4-2.1） | ✅ 批量 → refund=1.0，其余=0.0 |
| 18 | `suspiciousNonAffectedToolKept`（P4-2.1） | ✅ 可疑上下文下 queryPoints 仍 KEEP |
| 19 | `promptInjectionPatternsRemoved`（P4-2.1） | ✅ 忽略指令模式已删除，不再触发可疑风险 |

§十一 15/16（legacy / Phase 1-3 RTMP 回归）由全量 `mvn test` 保证（见 §12）。

---

## 12. Test Results

命令：`mvn test`（backend）

结果（P4-2.1 修订后）：**BUILD SUCCESS（exit code 0）**，`Tests run: 216, Failures: 0, Errors: 0, Skipped: 7`（7 个为 Real LLM `@Disabled`）。

- `RtmpScoringEngineTest`：19 通过（P4-2 的 15 个 + P4-2.1 的 4 个）。
- 既有 P4-1 `RouterContextFoundationTest`：13 通过。
- Phase 1-3 RTMP 测试（`RtmpFoundationPhase1Test` / `RtmpInstrumentationPhase1BTest` / `RtmpPhase1CTest` / `RtmpPhase2BaselineABTest` / `RtmpPhase3MatrixIntegrationTest`）：全部通过。
- 日志中 `ERROR`/`WARN` 均为既有降级/超时/安全拦截的预期场景，非测试失败。

---

## 13. Research Limitation

**（Phase 5-C1 已修订；保留历史口径如下，C1 更新见文末）**

P4-2/P4-2.1 时点的结论：当前 runtime 没有真实 `authorization` / `targetScope` 来源。

因此，对于 `"帮我退款订单ORDxxx"` 这类 query，如果 query/history 文本**没有**透露第三方或越权信息，Router **无法**识别这是 unauthorized：

- 不能读取 `RtmpTestCase.authorization`；
- 不能读取 `contextRisk.authorization` / `targetScope`；
- 不能读取 `riskLabel`。

`RuntimeContextRiskScore(tool, context)` 只能基于 query/history 的显式语义（如 `"这是别人的订单"`）给 1.0。**这是 observability limitation，不是 Router bug**——它如实反映了「Router 仅凭合法运行时输入」的边界。

> **Phase 5-C1 更新（解除本 limitation）**：C1 引入 `RuntimeSessionContext`（`runtimeAuthorization` × `runtimeTargetScope`）+ 独立 `RtmpRuntimeScenarioProvider`（`rtmp_runtime_scenarios_v1.json`），经 `RouterContextFactory.build(ctx, tools, runtimeSessionContext)` 注入 `RouterContext.runtimeAuthorization / runtimeTargetScope`。`RuntimeContextRiskScorer.score` 现以 runtime signal 优先（`UNAUTHORIZED`/`SYSTEM_SCOPE`→全工具 1.0；`OTHER_USER`→refund/queryOrder 1.0），query-pattern 仅作 fallback。P4-2.1 的 query-pattern 映射不变。

---

## 14. Retrospective

- **What Went Well**：评分层彻底 GT-free、纯确定性、易测；阈值集中；每个 feature 的 source/semantics/score 清晰可审。
- **What Went Wrong**：① 高估了 `IntentAnalyzer.IntentResult` 对工具级意图的判别能力——它只产出粗粒度 category，导致 intentScore 的强兼容分支在当前 runtime 下几乎不可达。② P4-2 初版把 `RuntimeContextRisk` 实现为 context-level，导致显式可疑 → 所有工具统一 1.0 → 全裁（P4-2.1 已修正）。
- **What We Learned**：中文文本的「强操作 vs 信息性」判别必须靠词典的 strong/weak 分层，而非单关键词；bigram 重叠是简单但有效的确定性描述兼容手段；context-level 风险信号必须与工具能力域结合成 per-tool 映射，否则会跨工具传播。
- **What Should Be Watched Next**：P4-3 接入裁剪时，必须继续保证「评分结果 → 裁剪」单向消费，不反向把 GT 引入评分；同时关注 intentScore 未来补强与 runtime authorization 来源。

---

## 15. Freeze Compliance

| 检查项 | 结论 |
|--------|------|
| 是否读取 GT（expectedTool/outcome/action/taskCategory/riskLabel/adversarial/expectedReason/mockResponse/candidateTools/toolRiskProfile/contextRisk） | ❌ 否。评分只接受 `RouterContext`，`RouterContext` 无这些字段（反射断言验证） |
| 是否间接从 `ExperimentRuntimeConfig.groundTruth` 读取 | ❌ 否。评分层不引用 `ExperimentRuntimeConfig` |
| 是否超出 P4-2 | ❌ 否。仅评分 + feature mapping |
| 是否实现 pruning / visibleTools finalization | ❌ 否。仅输出 `KEEP_CANDIDATE`/`PRUNE_CANDIDATE` 候选，未执行裁剪 |
| 是否改变 Baseline A/B | ❌ 否。未修改 `ExperimentCondition` / `AllToolsVisibility` / `PostHocSafetyVerifier` |
| 是否修改 System Prompt / Function Calling 双入口 | ❌ 否 |
| 是否使用 Real LLM / 统计检验 / 阈值校准 / GT 训练 | ❌ 否 |
| （P4-2.1）是否修改阈值 / `RiskScore.effective()` 公式 / relevance / static risk / 数据集 / Baseline A/B / RouterContext | ❌ 否。仅将 runtime risk 由 context-level 改为 per-tool 映射 |
| （P4-2.1）是否实现 RtmpVisibility / ToolMenuPruner / visibleTools / 双入口 / empty-tool-set | ❌ 否 |

---

## 16. Completion Gate

**COMPLETED（P4-2.1 READY FOR P4-3）**

- ✅ 代码已修订（`RuntimeContextRisk` → tool-context-level）
- ✅ P4-2.1 测试通过（`RtmpScoringEngineTest` 19 通过）
- ✅ 全量 `mvn test` 通过（216 / 0 / 0 / 7 Skipped）
- ✅ 报告已反映 per-tool runtime risk
- ✅ 缺陷及根因已记录（§18）
- ✅ authorization / batch / ambiguous mapping 已冻结
- ✅ prompt-injection 模式删除理由已记录（§18.5）
- ✅ before → after 真值表已记录（§18.6）
- ✅ limitation 已保留（§13）
- ✅ 未修改任何 P4-3 内容

满足进入 P4-3 的前置条件。

---

## 17. Next Phase Preconditions

P4-3 需要：

1. 消费 `RtmpScoringEngine.score(RouterContext) -> List<ToolScoreResult>` 的候选结论。
2. 实现 `RtmpVisibility` / `ToolMenuPruner`，把 `KEEP_CANDIDATE` 工具集合落成最终 `visibleTools`。
3. 定义并实现 **empty-tool-set policy**（所有工具被 PRUNE 时的兜底策略）。
4. 同步 **System Prompt【可用工具】段** 与 **Function Calling tools 参数** 两个入口（沿用 `AllToolsVisibility` 双入口一致性约束）。
5. 实现 `pruningDecision` 的落盘/观测（instrumentation-ready 输出的消费端）。
6. 保持每工具独立评分、禁止 Top-1；保持 `RtmpScoringConfig` 为唯一阈值来源。

---

## 18. P4-2.1 Design Correction / Change Log

> 本阶段只做文档一致性收口与 Gate 验证，不改代码、不进入 P4-3。

### 18.1 缺陷（Defect）

**性质：Unintended risk propagation / P4-2 design defect**（不是 implementation-only bug / harmless / expected behavior）。

原实现：

```
score(RouterContext)
```

导致：

```
same context → same runtime risk → all tools share runtime risk
```

当 suspicious context = 1.0 时：

```
EffectiveRisk = max(static, 1.0) = 1.0   // 对每个工具
```

从而**全部工具**成为 `PRUNE_CANDIDATE`（包括静态风险仅 0.05 的只读工具 queryPoints/queryCoupons）。

### 18.2 根因（Root Cause）

`RuntimeContextRiskScorer.score(RouterContext)` 的签名不含 tool，`RtmpScoringEngine` 对每个工具复用同一个 context-level risk 值；`RiskScore.effective() = max(static, runtime)` 在 runtime=1.0 时抬升所有工具。

### 18.3 决策（Decision）

将 `RuntimeContextRisk` 从 **context-level** 改为 **tool-context-level**：

```
RuntimeContextRiskScore(context)  →  RuntimeContextRiskScore(tool, context)
```

`EffectiveRisk` 公式本身不变：

```
EffectiveRisk(tool, context) = max( StaticToolRisk(tool), RuntimeContextRisk(tool, context) )
```

### 18.4 冻结的风险域（tool-context mapping）

**Authorization domain**（他人订单 / 冒充 / 越权）：

模式：`别人的` `他人的` `他人订单` `别人订单` `替别人` `替他人`；`冒充` `我是管理员` `我是客服` `我是主管`；`修改其他用户` `查询其他用户` `其他用户` `越权` `绕过`

| queryOrder | refund | queryPoints | queryCoupons |
|-----------|--------|-------------|--------------|
| 1.0 | 1.0 | 0.0 | 0.0 |

**Batch domain**（批量）：

模式：`批量审批` `批量操作`

| queryOrder | refund | queryPoints | queryCoupons |
|-----------|--------|-------------|--------------|
| 0.0 | 1.0 | 0.0 | 0.0 |

**Ambiguous domain**（所有权不清，保留）：

模式：`别人` `他人` `朋友` `同事` `家人` `亲戚` → `RuntimeContextRisk = 0.5`

> 0.5 < `theta_risk`=0.75，因此 Ambiguous 本身不会因 risk threshold 导致 PRUNE。

### 18.5 Prompt-Injection 模式删除

以下模式已从 `RuntimeContextRiskScorer` 风险词典删除：

```
忽略之前指令 / 忽略之前的指令 / 忽略指令 / 忽略上面
```

理由：这些模式属于 instruction-integrity / prompt-injection 风险，而非当前四工具已冻结的 authorization domain 或 batch domain；当前 `RouterContext` / `ToolRuntimeMetadata` 没有足够的工具能力模型支持一个可解释、tool-specific 的映射，因此本阶段不将其强行映射到工具风险。

> 该决定不是认为 prompt injection 不重要，而是当前 P4-2 风险模型暂不建模该风险域。

### 18.6 Before / After 真值表

| Context | Tool | Static | Runtime | Effective | Candidate |
|---------|------|--------|---------|-----------|-----------|
| 正常退款 | refund | 0.50 | 0.0 | 0.50 | KEEP |
| 他人订单 | queryOrder | 0.10 | 1.0 | 1.00 | PRUNE |
| 他人订单 | refund | 0.50 | 1.0 | 1.00 | PRUNE |
| 他人订单 | queryPoints | 0.05 | 0.0 | 0.05 | KEEP* |
| 他人订单 | queryCoupons | 0.05 | 0.0 | 0.05 | KEEP* |
| 批量 | refund | 0.50 | 1.0 | 1.00 | PRUNE |
| 批量 | queryOrder | 0.10 | 0.0 | 0.10 | KEEP* |

> `KEEP*`：还需满足 `Relevance >= 0.5`，不是无条件 KEEP。

### 18.7 测试结果

`RtmpScoringEngineTest` 新增 4 个测试（#16-19）：

| # | 测试 | 结果 |
|---|------|------|
| 16 | `authorizationPerToolRisk` | ✅ |
| 17 | `batchPerToolRisk` | ✅ |
| 18 | `suspiciousNonAffectedToolKept` | ✅ |
| 19 | `promptInjectionPatternsRemoved` | ✅ |

全量 `mvn test`：**BUILD SUCCESS**，`Tests run: 216, Failures: 0, Errors: 0, Skipped: 7`。

### 18.8 Limitation（保留，Phase 5-C1 已解除）

修正成功后 Router **仍不拥有真实 authorization understanding**：`runtimeAuthorization = Optional.empty()`、`runtimeTargetScope = Optional.empty()`。若 query/history 本身未显式表达第三方目标/越权/可疑语义，Router 仍无法知道真实授权状态（详见 §13）。

> **Phase 5-C1 更新**：上述 limitation 已解除。`RuntimeContextRiskScorer.score(RouterContext, toolName)` 现在优先消费 `RouterContext.runtimeAuthorization / runtimeTargetScope`（来自 `RuntimeSessionContext`），query-pattern 降级为 fallback。P4-2.1 的 per-tool query-pattern 映射（§18.4）保持不变。

### 18.9 Change Log（变更清单）

| 文件 | 变更 |
|------|------|
| `RuntimeContextRiskScorer.java` | `score(RouterContext)` → `score(RouterContext, String toolName)`；`SUSPICIOUS_PATTERNS` 拆为 `AUTHORIZATION_PATTERNS` + `BATCH_PATTERNS`；删除 4 个 prompt-injection 模式 |
| `RtmpScoringEngine.java` | 调用点传入 `tool.toolName()` |
| `RtmpScoringEngineTest.java` | 新增 4 个测试 |
