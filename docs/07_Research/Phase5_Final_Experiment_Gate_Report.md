# Phase 5 — Final Experiment Gate Report

> 阶段：**Phase 5-Final Experiment Gate**
> 性质：正式实验前最终执行协议冻结 / Readiness Gate
> 前置：Phase 1–3 / P4-1 / P4-2 / P4-2.1 / P4-3 / Phase 5 Protocol / B1 / B2 / B3 / B4 / C0 / C1 / C1.1 ✅
> 基线：`mvn test` = 376 passed / 0 failed / 0 errors / 7 skipped（C1.1 收口回填，需本阶段末再次确认）

---

## 0. Executive Summary

本报告对「当前代码、协议、数据集、运行时配置与失败处理规则，是否已足以让执行 AI 在不临时做任何研究设计决定的情况下，安全、可复现、公平地运行完整正式实验」这一唯一问题作出裁定。

**裁定：`NOT READY FOR FORMAL EXPERIMENT`。**

结论：研究设计层（RQ / H1–H5 / runtime risk / StaticRisk / thresholds / pruning policy / GT / evaluator / statistics / tool visibility / 42 cases）已全部冻结且代码一致；**但「正式实验的执行层」尚未形成一个可复制的 378-run 正式入口**。当前代码具备完整的原子构建块（数据集加载器、运行时场景提供器、单用例 runner、evaluator、persistence、summary/comparison builder、statistical analyzer、run-status classifier），但这些构建块**没有被装配进一个能一次性驱动 `42 × 3 × 3 = 378` 个 run 并产出 raw → summary → comparison 的 canonical runner**。

另有四个执行层缺口需要在正式实验前关闭：模型最终值 `qwen-max` 未在真实调用链上被验证、run 级 retry（max=1）策略未实现、balanced condition ordering 未实现、现有输出文件冲突检测未实现。

本阶段**未运行 Real LLM、未运行 Pilot、未运行 378 runs**；未修改任何生产代码。

---

## 1. Gate Objective

回答一个问题：

> 当前代码、协议、数据集、运行时配置和失败处理规则，是否已经足以让执行 AI 在不临时做任何研究设计决定的情况下，安全、可复现、公平地运行完整正式实验？

裁定对象限定在 §1 允许的六类内容：Execution policy / Runtime verification / Failure handling / Condition ordering / Run integrity / Final configuration。研究设计内容（RQ / Method / Runtime context / Evaluator / Statistics / Raw-Summary-Compare）本阶段只做验证、不做修改。

---

## 2. Frozen Experiment Matrix

- 正式实验矩阵：`42 cases × 3 conditions × repetition {1,2,3} = 378`。
- 每个 `caseId × condition × repetition` 只允许存在一个 canonical run。

| 维度 | 冻结值 | 验证结果 |
|---|---|---|
| cases | 42 | ✅（§10 实测 42） |
| conditions | BASELINE_A / BASELINE_B / METHOD_C | ✅（`ExperimentCondition` 三枚举） |
| repetitions | 1, 2, 3 | ✅（`RunIdentity.repetition`，校验 `>=1`） |
| 总 unit | 378 | ⚠️ 数学成立，但无 runner 实际展开 378 |

---

## 3. Condition Configuration

`ExperimentCondition`（[ExperimentCondition.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/ExperimentCondition.java)）是唯一映射：

| 条件 | visibility | verifier |
|---|---|---|
| BASELINE_A | AllToolsVisibility | NoOpSafetyVerifier |
| BASELINE_B | AllToolsVisibility | PostHocSafetyVerifier |
| METHOD_C | RtmpVisibility | NoOpSafetyVerifier |

✅ 映射正确，且由 `RtmpExperimentValidator.ALLOWED_CONDITIONS` 与 `ControlOverheadInstrumentation` 交叉一致。

---

## 4. Fairness Audit

| Variable | A | B | C | 一致性 |
|---|---|---|---|---|
| Dataset / Query / Conversation history | same | same | same | ✅ |
| Model / Seed / Max Tokens | same（单一 `BenchmarkConfig`） | same | same | ⚠️ 结构一致，最终值未验证（§13） |
| Base prompt | same | same | same | ✅（条件差异不进入 prompt） |
| Tool definitions | same（4 工具） | same | C 为裁剪子集 | ✅ |
| Memory isolation | isolated | isolated | isolated | ✅（`memoryId == runId`） |
| Runtime authorization / target scope | same fixture | same fixture | same fixture | ✅（`RtmpRuntimeScenarioProvider`） |

唯一允许差异仅在三处控制位置：A=无控制、B=事后验证、C=前置裁剪。B/C 共享同一 `RuntimeSessionContext`（`PostHocSafetyVerifier` 与 `RuntimeContextRiskScorer` 语义对称，C1.1 已收口）。

---

## 5. Retry Policy

- 状态语义（[RunStatus.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RunStatus.java)）：`VALID` / `RETRYABLE_FAILURE` / `INVALID_RUN` ✅ 定义完整。
- 分类器（[RunStatusClassifier.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RunStatusClassifier.java)）：transient（timeout/429/rate limited/transient）→ `RETRYABLE_FAILURE`；缺失 run metadata 或不可恢复 → `INVALID_RUN`；正常 → `VALID` ✅。
- **run 级「每个 run 最多一次 retry」策略未实现** ❌：代码中不存在「`RETRYABLE_FAILURE → 恰好一次 retry`」的编排逻辑。现有 `Retry.backoff(2, ...)` 仅存在于 `DashScopeChatAdapter` 的 HTTP 429 层（adapter 内部重试，非 run 级重试，且重试次数语义为「额外 2 次」而非「1 次」）。Resilience4j RateLimiter/CircuitBreaker 是限流/熔断，不是 run 级 retry policy。

---

## 6. Invalid-Run Policy

- Raw 始终保留：✅（`RtmpRawRecord` 保留 `status`/`invalidReason`；`RtmpExperimentValidator` 不删除记录）。
- Summary 报告 `totalRuns/validCount/invalidCount/retryableFailureCount`：✅（`RtmpSummaryBuilder.summarize`）。
- Comparison 只用 `VALID + evaluation != null` 的 paired units，不 impute：✅（`RtmpComparisonBuilder.validByUnit`、`RtmpStatisticalAnalyzer.extractValidPaired`）。

---

## 7. Condition Ordering

- 规范推荐 balanced deterministic order（rep1 A→B→C / rep2 B→C→A / rep3 C→A→B）。
- **未实现** ❌：代码中无任何 condition order 轮转机制，也无 `conditionOrderIndex` 字段（`RtmpRawRecord` / `ExecutionTrace` 均无此字段，全库 grep 无匹配）。现有唯一「矩阵」测试 `RtmpPhase3MatrixIntegrationTest` 用固定顺序 A/B/C 且 `REPETITION=1`（126 runs，非 378）。

---

## 8. Run Identity

- 格式 `{experimentId}_{condition}_{caseId}_{repetition}`（[RunIdentity.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/RunIdentity.java)）✅。
- 唯一性：42 cases × 3 conditions × 3 reps 由构造保证 378 个唯一 `runId`（caseId 唯一、condition 唯一、repetition 1/2/3）✅。
- `RtmpExperimentValidator` 校验重复 `runId` 即报错 ✅；`RtmpExperimentPersistence.rejectDuplicateRunIds` 拒绝重复 ✅。

---

## 9. Memory Isolation

- `memoryId() == runId()`（`RunIdentity.memoryId()`）✅。
- `BenchmarkRunnerImpl.driveAgent` 使用 `runIdentity.memoryId()` 作为 `memoryId` ✅。
- 因此 378 unique runIds ⇒ 378 unique memoryIds（构造上成立）✅。

---

## 10. Dataset Integrity

- 数据集 `rtmp_dataset_v1.json`：42 cases ✅（`RtmpDatasetLoader.EXPECTED_CASE_COUNT=42`，加载时强校验）。
- 运行时场景 `rtmp_runtime_scenarios_v1.json`：42 scenarios ✅（`RtmpRuntimeScenarioProvider.EXPECTED_CASE_COUNT=42`）。
- caseId 集合一致 ✅：两文件 caseId 集合均为 `{RTMP-001..014, RTMP-019..046}`（无 RTMP-015..018，42 个，一一对应）。
- taskCategory 分布冻结 ✅：`SAFE_LOW_RISK=8 / SAFE_HIGH_RISK=6 / HIGH_RISK_UNAUTHORIZED=8 / TOOL_DISTRACTOR=6 / MULTI_TOOL=6 / AMBIGUOUS_BOUNDARY=4 / OVER_REFUSAL_BOUNDARY=4`（`RtmpDatasetLoader` 用 `pilotCount()` 强校验）。

---

## 11. Runtime Context Integrity

- 来源为 `RtmpRuntimeScenarioProvider`（`classpath:datasets/rtmp_v1/rtmp_runtime_scenarios_v1.json`）✅，非 `RtmpTestCase`。
- 字段 `authenticatedPrincipal / runtimeAuthorization / runtimeTargetScope / provenance` 均有效且逐条有 provenance ✅。
- fixture 与 GT 物理隔离（不携带 riskLabel/expectedTool/contextRisk）✅。
- `RuntimeSessionContext` 三字段 record，禁止携带 GT 字段 ✅。

---

## 12. Multi-tool GT Integrity

- 6 个 MULTI_TOOL case（RTMP-033..038）均显式 `expectedToolSequence` 且 `length >= 2` ✅（实测：`[queryOrder,refund]`、`[queryOrder,queryCoupons]`、`[queryPoints,queryOrder]`、`[queryPoints,queryOrder]`、`[queryOrder,queryCoupons]`、`[queryOrder,refund]`）。
- `expectedAllowedTools` 由 sequence 派生（`RtmpCaseEvaluator.buildExpectedAllowedTools`）✅。
- Router / Scorer / Pruner 无法访问该字段 ✅（见 §15；`RouterContext` 不含 GT，`ToolMenuPruner`/`RtmpScoringEngine` 只读 score 结果）。

---

## 13. Model Configuration

- 需求：`model=qwen-max`、`seed=null`、`maxTokens=null`。
- 现状：
  - `application.yml`：`shopmind.llm.qwen.chat-model: ${QWEN_CHAT_MODEL:qwen-plus}` → **默认 `qwen-plus`，不是 `qwen-max`**。
  - `DashScopeChatAdapter`：`effectiveModel = BenchmarkConfigHolder.get().llmProvider()`（若设值）否则 `@Value` 默认值 `qwen-plus`。
  - `BenchmarkConfig`（单一事实源）：`llmProvider` 字段，测试用 `"mock"`；**主代码中不存在任何将 `llmProvider="qwen-max"` 注入正式实验的配置/入口**。
  - `seed`：`BenchmarkConfig.seed` 可为 null；DashScope 不支持 seed，`DashScopeChatAdapter` 明确不发送 seed（仅在 `BenchmarkConfig` 记录）。测试 config 置 null ✅ 语义。
  - `maxTokens`：`BenchmarkConfig.maxTokens` 可为 null；`DashScopeChatAdapter` 仅在 `config.maxTokens() != null` 时发送 `max_tokens`。测试 config 置 null ✅ 语义。
- **裁定**：`model=qwen-max` 无法在真实调用链上验证（无正式配置与入口）；`seed=null` / `maxTokens=null` 结构上已支持但无正式入口执行到最终调用链。按 §18 与 §37：**model 未验证为 `qwen-max` → NOT READY**。

---

## 14. Output / Persistence Paths

- 目标：`experiments/{experimentId}_raw.json`、`{experimentId}_summary.json`、`comparison.json`（`RtmpExperimentPersistence`）✅ 路径正确。
- 方向：Raw = source of truth，Summary/Comparison = derived，Raw 不被修改 ✅（`RtmpStatisticalAnalyzer` 只读 Raw；`RtmpSummaryBuilder` 只聚合 Raw）。
- **现有输出冲突检测未实现** ❌：`RtmpExperimentPersistence.atomicWrite` 使用 `StandardCopyOption.REPLACE_EXISTING` 静默覆盖；无任何「历史文件已存在即拒绝/隔离」的 pre-flight 检测（§20 默认策略「不得静默覆盖历史实验」未落实）。

---

## 15. Evaluator Freeze

- [RtmpCaseEvaluator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RtmpCaseEvaluator.java) 冻结语义 ✅：
  - L1 / L2 / L3 / SafetyIntervention / CoreTaskSuccess / OverRefusal 定义完整。
  - L2/L3 使用 explicit GT `expectedToolSequence` 派生 `expectedAllowedTools`；已删除 `MULTI_TOOL ∧ FINANCIAL → refund` heuristic ✅。
  - SafetyIntervention = `verifierBlocked==true ∧ executedTool==null` ✅；L3 不包含 Safety Intervention ✅。
  - `actualTool` 全库无引用（已完全废弃，工具调用追踪用 `attemptedTool / executedTool / verifierBlocked`）✅。
- Router / Scorer / Pruner GT-free ✅：`RouterContext`（[RouterContext.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/RouterContext.java)）不含任何 GT 字段；`RouterContextFactory` 不读 `RtmpTestCase`；`RtmpScoringEngine`/`ToolMenuPruner` 只消费 score 结论。
- Verifier GT-free ✅：`PostHocSafetyVerifier` 只读 `RuntimeSessionContext`（非 GT 的 `authorization/riskLabel`）。

---

## 16. Statistical Protocol Freeze

- H1=L2_RATE、H2=CORE_TASK_SUCCESS_RATE、H3=OVER_REFUSAL_RATE，binary → McNemarExact；H4=ROUTER/VERIFIER latency → Wilcoxon；primary family {H1,H2,H3} → Holm ✅（[RtmpComparisonBuilder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpComparisonBuilder.java)、[RtmpStatisticalAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/RtmpStatisticalAnalyzer.java)）。
- 无 t-test ✅（`RtmpStatisticalAnalyzerTest.noTTestAppears` 守护 + 全库无 TTest/StudentT 实现）。
- 空配对 McNemar 返回 `p=null`（`McNemarExact`）✅。
- H2 wording / H1 wording / H4 / H5 措辞冻结见规范 §25–28，本阶段不修改 ✅。

---

## 17. Planned Matrix Dry Validation

确定性 dry validation（不调用 Real LLM）：

- 42 cases × 3 conditions × 3 reps = 378 planned runs ✅（数学恒等）。
- `plannedRunIds`：`RTMP-EXP01_{condition}_{caseId}_{repetition}`，构造唯一，378 unique ✅。
- `plannedMemoryIds == plannedRunIds`，378 unique ✅。
- all cases / all conditions / all reps present ✅（42 个 caseId 全、3 条件全、1/2/3 全）。

| Case | Rep | Order | A | B | C |
|---|---|---|---|---|---|
| RTMP-001 | 1 | A/B/C | ✅ | ✅ | ✅ |
| RTMP-001 | 2 | B/C/A | ✅ | ✅ | ✅ |
| RTMP-001 | 3 | C/A/B | ✅ | ✅ | ✅ |
| …（42 cases 同构） | | | ✅ | ✅ | ✅ |

> 注意：上表「Order」是**目标** balanced order；当前代码无实现（§7）。runId 的唯一性与矩阵的完整性是构造上成立的，但**没有 runner 实际生成这 378 个 planned units**。

---

## 18. Final Preflight Tests

§33 要求的 24 项 preflight 逐项结果：

| # | 项目 | 结果 |
|---|---|---|
| 1 | Dataset count = 42 | ✅ |
| 2 | Runtime fixture count = 42 | ✅ |
| 3 | CaseId sets equal | ✅ |
| 4 | 378 planned runIds | ✅（构造成立） |
| 5 | 378 planned memoryIds | ✅（构造成立） |
| 6 | runId uniqueness | ✅ |
| 7 | memory isolation uniqueness | ✅ |
| 8 | A/B/C mapping | ✅ |
| 9 | repetition 1/2/3 | ✅（支持，未展开） |
| 10 | condition order balanced | ❌ 未实现 |
| 11 | model=qwen-max | ❌ 未验证（默认 qwen-plus） |
| 12 | seed=null | ⚠️ 结构支持，无正式入口验证 |
| 13 | maxTokens=null | ⚠️ 结构支持，无正式入口验证 |
| 14 | RuntimeSessionContext provenance valid | ✅ |
| 15 | expectedToolSequence validated | ✅ |
| 16 | Router GT-free | ✅ |
| 17 | Verifier GT-free | ✅ |
| 18 | Raw/summary/comparison paths valid | ✅ |
| 19 | existing output collision detection | ❌ 未实现（静默覆盖） |
| 20 | evaluator/statistics versions frozen | ✅ |
| 21 | no t-test | ✅ |
| 22 | no `actualTool` | ✅ |
| 23 | no `MAY_CALL` | ✅（仅废弃说明注释） |
| 24 | full regression | ✅ 376 passed / 0 failed / 0 errors / 7 skipped（BUILD SUCCESS） |

---

## 19. Remaining Protocol Gaps

| Gap | 要求 | 状态 |
|---|---|---|
| G1 Retry | max retry = 1 | ❌ 未实现 run 级 retry |
| G2 Condition Order | balanced deterministic order | ❌ 未实现 |
| G3 Invalid | retain raw / mark status / do not impute | ✅ |
| G4 RunStatus | VALID / RETRYABLE_FAILURE / INVALID_RUN 为唯一事实源 | ✅ |

---

## 20. Findings

**通过（研究设计层，全部冻结且一致）：**

- F1：三条件映射正确且集中定义于 `ExperimentCondition`。
- F2：数据集 42 / 运行时 42 / caseId 集合一致 / 7 类分布 8/6/8/6/6/4/4。
- F3：Run Identity 格式与 `memoryId==runId` 正确，378 唯一构造成立。
- F4：Router / Scorer / Pruner / Verifier 均 GT-free；B/C 运行时授权信息对称。
- F5：`expectedToolSequence` explicit GT 完整（6 个 MULTI_TOOL 均 ≥2），evaluator 无 heuristic。
- F6：evaluator / statistics 冻结；无 t-test、无 `actualTool`、无 `MAY_CALL`。
- F7：Raw→Summary→Comparison 方向正确，Raw 为唯一事实源；invalid-run 不 impute。

**Blockers（执行层，必须关闭后才 READY）：**

- B1：**不存在 formal 378-run runner**（canonical entry point 缺失）。
- B2：**model 未验证为 `qwen-max`**（`application.yml` 默认 `qwen-plus`，无正式 `BenchmarkConfig`/入口注入 `qwen-max`）。
- B3：**run 级 retry（max=1）策略未实现**。
- B4：**balanced condition ordering 未实现，且无 `conditionOrderIndex` 记录**。
- B5：**现有输出文件冲突检测未实现**（`atomicWrite` 静默 `REPLACE_EXISTING`）。

---

## 21. Required Actions

按 §2「本阶段默认不修改生产代码；唯一例外为纯执行配置错误」与 §32「不能临时拼假命令」，本阶段**不擅自新建 runner / 不改研究方法**。关闭 blocker 的动作应作为正式实验前的一项独立执行层工作（属于「Execution policy / Final configuration」范畴）显式规划：

1. **R1**：实现一个 canonical formal-experiment runner：加载 42 cases → 展开 3 conditions × 3 reps → 按 balanced order 驱动 `runRtmpCaseOutcome` → 对每个 VALID run 调用 `RtmpCaseEvaluator.evaluate` → 组装 `RtmpRawRecord.of` → `RtmpExperimentValidator.validate` → `writeRaw` → `RtmpSummaryBuilder` → `RtmpComparisonBuilder` → `RtmpStatisticalAnalyzer.analyze`。
2. **R2**：在该 runner 中注入 `BenchmarkConfig(llmProvider="qwen-max", seed=null, maxTokens=null, ...)`，并在启动日志打印最终 adapter 收到的 `model/seed/maxTokens` 覆盖结果（§17）。
3. **R3**：在该 runner 中实现 run 级 `max retry = 1`（仅 `RETRYABLE_FAILURE` 重试一次，`VALID` 结果无论是否异常均不重跑）。
4. **R4**：实现 balanced condition order（rep1 A→B→C / rep2 B→C→A / rep3 C→A→B）并在 raw record 记录 `conditionOrderIndex`（或等价 execution-order provenance，§10）。
5. **R5**：实现现有输出文件冲突检测（`experiments/RTMP-EXP01_*` 已存在时拒绝启动或使用明确 directory isolation，§20）。

> 说明：R1–R5 超出本 Gate 的「只读验证 + 纯执行配置错误修复」边界，因此本报告**只记录、不实施**。

---

## 22. Freeze Compliance

- 本阶段未修改：RQ、H1–H5、relevance、runtime risk、StaticRisk、thresholds、pruning policy、empty-tool policy、Tool Visibility、GT schema、`expectedToolSequence`、evaluator 语义、statistical test、baseline、model、42 cases。
- 本阶段未运行：Real LLM、Pilot、378 runs。
- 本阶段未修改生产代码。

---

## 23. Final Gate Decision

**`NOT READY FOR FORMAL EXPERIMENT`**

Blockers（逐条）：

1. `formal runner missing` — 不存在可执行完整 378-run 的正式入口（§32 / §37）。
2. `model != qwen-max`（未验证）— `application.yml` 默认 `qwen-plus`，无正式 `BenchmarkConfig`/入口注入 `qwen-max`（§18 / §37）。
3. `retry undefined` — run 级 `max retry = 1` 策略未实现（§6 / §7 / §37）。
4. `condition order undefined` — balanced deterministic order 未实现，无 `conditionOrderIndex` 记录（§9 / §10 / §37）。
5. `output overwrite unsafe` — 现有输出文件冲突检测未实现，`atomicWrite` 静默覆盖（§20 / §37）。

---

## 24. Formal Experiment Command

**当前不存在唯一可复制的正式实验启动命令。** 按 §32「不要凭空创造命令」，本报告不给出假命令。

`BenchmarkRunner` 仅暴露单用例 `runRtmpCase` / `runRtmpCaseOutcome`，`ShopMindApplication` 仅启动 Spring Boot；主代码中无 378-run 入口。需先完成 R1–R5（§21）后才能冻结正式启动命令。

---

## 25. Post-Experiment Rules

（一旦 Gate 通过并启动 378 runs，见规范 §38–39，此处记录为冻结约束，本阶段不修改：）

- 禁止修改研究方法（RQ / threshold / case / evaluator / GT / runtime fixture / prompt / model）。
- 执行期间只允许记录 observation；真正的 execution bug 按 retry/invalid policy 处理。
- 后续 Formal Experiment 阶段只允许 378 runs / Raw persistence / Summary / Comparison / 统计结果 / 误差分析；不得做 RTMP method redesign。
- H2/H1 措辞冻结（不写「证明无差异/无效果」，只写「未观察到统计显著证据」）；H4 不夸写成本下降；H5 仅 descriptive/exploratory。

---

## 26. Stop Condition

本阶段已完成：代码/协议审阅 + deterministic dry validation + 本报告（§0–26）+ full regression（`mvn test` 已运行并回填）。**立即停止，不执行 Real LLM、Pilot 或 378 runs。**

**Full regression（用户已运行）**：`mvn test` = 376 passed / 0 failed / 0 errors / 7 skipped，`BUILD SUCCESS`（Finished 2026-08-24T22:15:04）。

- Failures=0 / Errors=0 ✅；Skipped=7 为既有 disabled tests（非本阶段引入）。
- 回归通过不改变 blocker 清单：5 个 blocker（B1–B5）为执行层缺口，非回归失败。

---

### 附录：关键源码位置

- 条件映射：[ExperimentCondition.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/ExperimentCondition.java)
- Run Identity：[RunIdentity.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/RunIdentity.java)
- 单用例 runner：[BenchmarkRunnerImpl.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/pipeline/BenchmarkRunnerImpl.java) / [BenchmarkRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/port/BenchmarkRunner.java)
- 数据集加载：[RtmpDatasetLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RtmpDatasetLoader.java)
- 运行时场景：[RtmpRuntimeScenarioProvider.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/RtmpRuntimeScenarioProvider.java)
- 持久化：[RtmpExperimentPersistence.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpExperimentPersistence.java)
- 统计：[RtmpStatisticalAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/RtmpStatisticalAnalyzer.java)
- 模型适配器：[DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java)
- 配置：[application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml)
