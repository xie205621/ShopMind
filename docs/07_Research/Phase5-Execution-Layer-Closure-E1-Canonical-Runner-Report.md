# Phase 5 — Execution Layer Closure
## E1 — Canonical Formal Experiment Runner & Final Execution Protocol Closure

> 阶段性质：正式实验前最后的「执行层」实现与验收阶段。
> 前置：C1.1 已关闭；研究设计层（RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / GT / evaluator / 统计 / 42 cases / runtime fixture）已冻结，本阶段不重新设计 RTMP。

---

## 0. Executive Summary

本阶段关闭了 Final Experiment Gate 裁定中的 5 个执行层 blocker（B1–B5，对应 Gate 报告 §21 的 R1–R5），全部属于「Execution policy / Final configuration」范畴，未触碰任何冻结研究语义。

- **B1（R1）**：新增 canonical formal 378-run runner `RtmpFormalExperimentRunner`。
- **B2（R2）**：新增唯一正式 `BenchmarkConfig` 来源 `RtmpFormalExperimentConfig`（`qwen-max` / `seed=null` / `maxTokens=null`）。
- **B3（R3）**：新增 run-level retry（max=1）`RtmpRunRetryPolicy` + runner 内 `runWithRetry` 编排。
- **B4（R4）**：新增 deterministic balanced condition order `RtmpConditionOrder`，并在 `RtmpRawRecord` 新增 `conditionOrderIndex` provenance。
- **B5（R5）**：新增 experiment-level output collision preflight `RtmpExperimentPersistence.assertOutputsDoNotExist(...)`。

新增 1 个 canonical plan/校验类 `RtmpFormalExperimentPlan`、1 个 opt-in entry point `RtmpFormalExperimentEntryPoint`，以及 1 个执行层测试类 `RtmpFormalExperimentTest`（覆盖 §十五 A–G）。

**本阶段不调用 Real LLM、不跑 Pilot、不跑 378 formal runs。**（§十八）

---

## 1. Phase Status

- 状态：**执行层实现已完成**，最终裁定待 `mvn test` 回归确认（见 §12 / §16）。
- 研究设计层：**冻结**（无任何变更）。
- 生产代码改动范围：仅 execution orchestration / retry / condition order / run provenance / final runtime config / output collision / 执行层测试 / 协议文档。

---

## 2. Scope

本阶段允许修改（§一）：

- execution orchestration
- retry orchestration
- condition order
- run provenance
- final runtime config verification
- output collision safety
- execution-layer tests
- execution protocol documentation

本阶段禁止修改（§一，全部未触碰）：RQ、H1–H5、`theta_relevance=0.5`、`theta_risk=0.75`、StaticRisk 五维、EffectiveRisk、pruning/empty-tool policy、Tool Visibility、Baseline A/B/C 语义、RuntimeRisk 语义、OTHER_USER closure、GT schema、`expectedToolSequence`、`RtmpCaseEvaluator` 语义、L1/L2/L3、Core Task Success 分母=30、Over-refusal、McNemar/Wilcoxon/Holm、42 cases、runtime fixture；不增 baseline/model/embedding/Router/Judge；不跑 Real LLM/Pilot/378。

---

## 3. Frozen Execution Protocol

- 实验矩阵：**42 cases × 3 conditions × repetition{1,2,3} = 378 canonical experimental units**。
- Canonical run identity：`RTMP-EXP01_{condition}_{caseId}_{repetition}`。
- `memoryId == runId`（同一 canonical unit 无论 retry 几次只对应一个 canonical runId）。
- 数据流方向（不可逆）：`Raw → Summary` / `Raw → Comparison` / `Raw → Statistics`；Raw 是唯一事实源。
- 禁止从 Summary rate 反推 McNemar，禁止从 Comparison valueA/valueB 猜 paired observations。

---

## 4. Canonical Run Matrix

实现：[RtmpFormalExperimentPlan.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentPlan.java)

- `build(experimentId, dataset)` 生成 42×3×3 = 378 个 `Unit`，每个 unit 携带 `caseId / condition / repetition / runId / memoryId / conditionOrderIndex`。
- `validate(plan)` 执行完整预执行校验（§五）：
  1. planned units = 378
  2. runId 378 唯一
  3. memoryId 378 唯一
  4. caseId 全覆盖（42）
  5. condition 全覆盖（3）
  6. repetition 全覆盖（3）
  7. 每个 case×repetition 恰好 3 个 condition
  8. order 符合 frozen rotation
  9. 不允许重复 runId
  10. 不允许重复 canonical unit

预执行失败 → 禁止启动任何 Real LLM 调用。

---

## 5. Condition Ordering

实现：[RtmpConditionOrder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpConditionOrder.java)

Deterministic balanced rotation（§三，不随机、不用 seed、不动态调整）：

| repetition | order | conditionOrderIndex |
|---|---|---|
| 1 | A → B → C | A=0, B=1, C=2 |
| 2 | B → C → A | B=0, C=1, A=2 |
| 3 | C → A → B | C=0, A=1, B=2 |

其中 A=`BASELINE_A`、B=`BASELINE_B`、C=`METHOD_C`。

`conditionOrderIndex` 定义：0=本 case/repetition 第一个执行的 condition，1=第二个，2=第三个。已写入 `RtmpRawRecord`（见 §10），未修改 `runId / caseId / condition / repetition`。

---

## 6. Run Identity / Memory Isolation

- `runId` 格式与生成复用已冻结的 [RunIdentity.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/RunIdentity.java)，格式 `{experimentId}_{condition}_{caseId}_{repetition}`。
- `memoryId == runId`（`RunIdentity.memoryId()` 直接返回 `runId()`）。
- retry 不产生第二个 canonical runId；retry 前通过 `ChatMemoryStore.deleteMessages(memoryId)` 清理同一 memory，避免第一次失败状态污染第二次（§八）。
- 若运行环境未提供 `ChatMemoryStore` bean，retry 前的 memory 清理被跳过（不篡改 frozen `memoryId == runId` 协议，也不降级为 `runId_retry`）。

---

## 7. Retry Policy

实现：[RtmpRunRetryPolicy.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpRunRetryPolicy.java) 与 [RtmpFormalExperimentRunner.runWithRetry](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java)

**adapter retry vs run retry 严格区分**：`DashScopeChatAdapter` 的 `Retry.backoff(...)` 是 transport 层，不视为 formal run retry。

Run-level 状态机（max retry=1，§七）：

| 第一次 | 第二次 | 最终 canonical status | attempts |
|---|---|---|---|
| VALID | — | VALID | 1 |
| INVALID_RUN | — | INVALID_RUN | 1 |
| RETRYABLE_FAILURE | VALID | VALID | 2 |
| RETRYABLE_FAILURE | RETRYABLE_FAILURE | RETRYABLE_FAILURE | 2 |
| RETRYABLE_FAILURE | INVALID_RUN | INVALID_RUN | 2 |

- 不得第三次执行；不得因 VALID「不好看」或 INVALID「可能影响结论」重跑。
- retry 判断只依赖 `RunStatus`，不依赖 evaluation metric。
- 最终 Raw 只产生一个 canonical record（retry attempts 不成为独立 statistical units）。

---

## 8. Model / Runtime Configuration

实现：[RtmpFormalExperimentConfig.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentConfig.java)

| 参数 | 冻结值 |
|---|---|
| `llmProvider` | `qwen-max` |
| `seed` | `null` |
| `maxTokens` | `null` |
| `temperature` | `0.1` |
| `topP` | `0.9` |
| `workflowVersion` | `v2.3` |
| `datasetVersion` | `rtmp_v1.0`（`RtmpDatasetLoader.EXPECTED_VERSION`） |
| `embeddingModel` | `bge-m3` |
| `vectorStore` | `InMemory` |
| `maxConcurrency` | `5`（default） |
| `rpmLimit` | `30`（default） |

- formal runner 显式注入 `qwen-max`，不修改 `application.yml` 默认 `qwen-plus`。
- `seed=null`：仅作为 config 元数据保留，不发送给 DashScope。
- `maxTokens=null`：不发送 `max_tokens`。

**model 验证路径**（§六）：`BenchmarkConfig.llmProvider()` → `BenchmarkConfigHolder.set(config)`（`BenchmarkRunnerImpl.driveAgent`）→ `DashScopeChatAdapter.buildRequestBody` 的 `effectiveModel = config.llmProvider()`。本阶段不调用 Real LLM，model 验证通过 config-path test 完成（`RtmpFormalExperimentTest#modelConfig_isFrozen` 断言 `llmProvider == "qwen-max"`、`seed == null`、`maxTokens == null`）。`DashScopeChatAdapter` 的 `effectiveModel` 读取路径以代码为准。

---

## 9. Formal Runner Architecture

实现：[RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java)

独立于通用 `BenchmarkRunner.run(...)`（不把 378-run 编排硬塞进通用 runner）。执行流程：

```
load dataset (RtmpDatasetLoader)
  → preflight（build plan + validate + assertOutputsDoNotExist + build config）
  → for each case × repetition，按 frozen order 执行 A/B/C
      → runRtmpCaseOutcome（run-level retry max=1）
      → VALID → RtmpCaseEvaluator.evaluate
      → RtmpRawRecord.of(..., conditionOrderIndex)
  → RtmpExperimentValidator.validate
  → writeRaw
  → RtmpSummaryBuilder.build → writeSummary
  → RtmpComparisonBuilder.build → RtmpStatisticalAnalyzer.analyze → writeComparison
```

严格方向：`Raw → Summary` / `Raw → Comparison` / `Raw → Statistics`；`RtmpSummaryBuilder` / `RtmpComparisonBuilder` / `RtmpStatisticalAnalyzer` 直接复用，不重写统计实现。

Entry point 实现：[RtmpFormalExperimentEntryPoint.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentEntryPoint.java)

- 默认不装配（`@ConditionalOnProperty(name = "shopmind.rtmp.formal.enabled", havingValue = "true")`）。
- 显式 opt-in 后先 preflight，preflight 失败不调用 Real LLM、不调用 Pilot。
- 启动日志打印 experimentId / datasetCases / model / repetitions / plannedUnits / outputDir。

---

## 10. Persistence / Collision Protection

实现：[RtmpExperimentPersistence.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpExperimentPersistence.java)

新增 `assertOutputsDoNotExist(experimentId, outputDir)`（B5）：

- 检查 `{experimentId}_raw.json` / `{experimentId}_summary.json` / `comparison.json`。
- 任一已存在 → 抛异常拒绝启动；不 `REPLACE_EXISTING`、不自动覆盖、不静默删除、不自动换目录、不自动改 experimentId。
- 与既有 duplicate-runId 检查区分：后者只解决「同一次 records 内重复 runId」，本方法解决「历史 experiment output 已存在」的 preflight。

`RtmpRawRecord` 新增 `conditionOrderIndex`（B4，§十）：见 [RtmpRawRecord.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpRawRecord.java)，只增加 execution-order provenance，未修改 `runId / caseId / condition / repetition`。`of(...)` 保留 5 参重载（默认 index=0）并新增 6 参重载（显式 index）。

---

## 11. Run Integrity / Validator

直接复用已冻结的 [RtmpExperimentValidator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpExperimentValidator.java)，未新建第二套 validator。runner 在真正写 Raw 前调用 `RtmpExperimentValidator.validate(records)`，校验 runId 唯一 / condition 合法 / runId 格式 / repetition 合法 / condition→event 一致性。

---

## 12. Testing

新增执行层测试：[RtmpFormalExperimentTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentTest.java)，覆盖 §十五 A–G。

**测试真值表（预期结果）**：

| 编号 | 覆盖项 | 断言（预期） |
|---|---|---|
| A | Formal matrix | planned=378、uniqueRunIds=378、uniqueMemoryIds=378、case=42、condition=3、repetition=3 |
| B | Condition order | rep1=A/B/C、rep2=B/C/A、rep3=C/A/B；9 组 conditionOrderIndex 映射 |
| C | Identity | runId 格式正确、memoryId==runId |
| D | Retry | VALID→1 attempt、INVALID→1、RETRYABLE→2；success→VALID、transient→RETRYABLE_FAILURE、nonrecoverable→INVALID_RUN、永不 3 次 |
| E | Model config | llmProvider=qwen-max、seed=null、maxTokens=null、topP=0.9、temperature=0.1 |
| F | Collision | none→pass；raw/summary/comparison 存在→reject |
| G | conditionOrderIndex | 记录 index 且不改 runId/caseId/condition/repetition |

**回归目标**（§十六）：`mvn test` → `0 failures / 0 errors`；既有 7 skipped 可保留，除非证明是 execution-layer regression。

**实际回归结果（2026-08-25）**：

| 指标 | 值 |
|---|---|
| Tests run | 392 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 7（既有，非执行层回归） |
| BUILD | SUCCESS |

执行层测试类 `RtmpFormalExperimentTest`（16 个方法，覆盖 §十五 A–G）全部通过。

---

## 13. Dry Validation Result

Dry validation（§十七，不联网、不调用真实 Qwen）由 `RtmpFormalExperimentPlan.build + validate` 提供确定性校验，目标：

- planned = 378
- unique runIds = 378
- unique memoryIds = 378
- case coverage = 42
- condition coverage = 3
- repetition coverage = 3
- order = rep1 A/B/C、rep2 B/C/A、rep3 C/A/B

该 dry validation 已由 `RtmpFormalExperimentTest#formalMatrix_is378CanonicalUnits` 与 `#conditionOrder_isFrozenBalancedRotation` 固化。model config 与 output collision preflight 分别由 E / F 测试覆盖。retry policy wiring 由 D 测试覆盖。

---

## 14. Findings

- 既有 `RtmpSummaryBuilder` / `RtmpComparisonBuilder` / `RtmpStatisticalAnalyzer` / `RtmpExperimentValidator` / `RtmpDatasetLoader` / `RtmpRuntimeScenarioProvider` / `RtmpCaseEvaluator` 全部直接复用，未重写、未改动。
- `application.yml` 默认 `qwen-plus` 保持不变；`qwen-max` 由 formal runner 显式注入（不掩盖缺失）。
- retry 前 memory 清理复用既有 `ChatMemoryStore.deleteMessages(memoryId)`，未新增第二套 memory semantics，未把 `memoryId` 改为 `runId_retry`。
- `conditionOrderIndex` 以 execution provenance 形式写入 Raw，未破坏既有 `RtmpB4Fixtures` / `RtmpB1Fixtures` 的现有 5 参 `of(...)` 调用（默认 0）。

---

## 15. Remaining Blockers

- **已关闭（code-level + test-verified）**：B1 / B2 / B3 / B4 / B5。
- **Limitation（非 blocker）**：`DashScopeChatAdapter` 的 `effectiveModel = config.llmProvider()` 为代码级读取路径，本阶段不通过 Real LLM 做端到端 model 验证（§六明确要求以 config-path test 完成，不跑 Real LLM）。
- **Protocol blocker**：无（RQ / H1–H5 / threshold / 42 cases / GT / evaluator / 统计方法均未改动）。

---

## 16. Final Gate Decision

代码层面的执行层收尾已完成：

- ✅ canonical 378-run runner 已存在（`RtmpFormalExperimentRunner`）
- ✅ formal entry point 唯一且 opt-in（`RtmpFormalExperimentEntryPoint`）
- ✅ deterministic condition order 已实现（`RtmpConditionOrder`）
- ✅ `conditionOrderIndex` 已记录（`RtmpRawRecord`）
- ✅ run-level max retry=1 已实现（`RtmpRunRetryPolicy` + `runWithRetry`）
- ✅ RunStatus semantics unchanged（未改动 `RunStatus` / `RunStatusClassifier`）
- ✅ qwen-max final config path 已建立（`RtmpFormalExperimentConfig`）
- ✅ seed=null / maxTokens=null（config 元数据保留，不发送）
- ✅ output collision preflight 已实现（`assertOutputsDoNotExist`）
- ✅ raw/sum/comparison/statistics 方向正确（复用 B4/B1 实现）
- ✅ retry 不生成额外 statistical units（最终仅一个 canonical record）
- ✅ dry validation = 378（plan/validate + 测试固化）
- ✅ execution-layer tests 通过（`RtmpFormalExperimentTest` 16/16）
- ✅ `mvn test` 全绿（Tests run 392, Failures 0, Errors 0, Skipped 7, BUILD SUCCESS）
- ✅ 报告生成完成（本文件）

**裁定：`EXECUTION LAYER CLOSURE E1 COMPLETE`。**

按 §二十一纪律，本阶段完成后立即停止，不自行启动 Real LLM / Pilot / 378 runs；下一阶段仅在 review 本报告并明确允许后进入。
