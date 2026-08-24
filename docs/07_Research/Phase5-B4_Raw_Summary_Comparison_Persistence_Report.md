# Phase 5-B4 — Raw / Summary / Comparison Persistence & Experiment Data Pipeline

> 阶段：Phase 5-B4（Raw / Summary / Comparison 三层落盘 + 实验数据管线，仅解除 blocker B4）
> 状态：**B4 COMPLETE / READY FOR B1**（`mvn test` 全量回归：284 passed / 0 failed / 0 errors / 7 skipped → BUILD SUCCESS）
> 前序：Phase 1–3 ✅ → P4-1 ✅ → P4-2 ✅ → P4-2.1 ✅ → P4-3 ✅ → Phase 5 协议冻结 ✅ → Phase 5-B2 ✅（242 passed / 0 failed / 0 errors / 7 skipped）→ Phase 5-B3 ✅（258 passed / 0 failed / 0 errors / 7 skipped）
> 本阶段新增：`RtmpRawRecord` + `RtmpRawRuntimeMetrics` + `RtmpSummary` + `RtmpSummaryBuilder` + `RtmpComparison` + `RtmpComparisonBuilder` + `RtmpExperimentValidator` + `RtmpExperimentPersistence`；新增 3 个测试类（26 个 B4-focused 测试方法）

---

## 0. Executive Summary

本阶段建立正式实验数据管线，实现 **Raw → Summary → Comparison** 三层落盘，并保证 **Raw 是唯一事实来源**：

```text
ExecutionTrace + RtmpCaseEvaluation + ControlOverhead + PruningEvent + RunIdentity
        ↓
      RAW（experiments/{experimentId}_raw.json）
        ↓
    SUMMARY（experiments/{experimentId}_summary.json）
        ↓
  COMPARISON（experiments/comparison.json）
```

核心产出：

- `RtmpRawRecord` — 一个 run = 一个完整事实记录；直接消费 B2/B3/P4-3 已冻结的 canonical 对象，不重算。
- `RtmpSummaryBuilder` — 纯函数、无状态、无 IO，从 Raw 按 condition/subgroup 聚合描述性指标。
- `RtmpComparisonBuilder` — 生成三个 condition pair（`A_vs_B` / `B_vs_C` / `A_vs_C`）的描述性差异 + 统计占位（恒 null）。
- `RtmpExperimentValidator` — 校验 run identity 唯一、condition/event 一致、run_id 格式一致。
- `RtmpExperimentPersistence` — JSON 落盘，原子写（temp → close → atomic move），duplicate run 拒绝，null 原样保留。

**关键结论（诚实记录）**：

- 复用现有 `RunStatus`（`VALID` / `RETRYABLE_FAILURE` / `INVALID_RUN`），未自建第二套 status；「partial」在真实代码中对应 `RETRYABLE_FAILURE`，已明确映射。
- Router/Verifier token/cost 在 B3 恒 null；B4 通过不配置 Jackson `NON_NULL`，保证 null 原样落盘，不改为 0/空串/估算值。
- Summary/Comparison 均为从 Raw 可重算的聚合层，未引入独立计算逻辑；统计字段（`statisticalTest`/`statistic`/`pValue`/`decision`）恒 null，因 B1 未实现。

**Completion Gate**：三层落盘、validation、tests（26 个，≥18）已实现，静态诊断无编译错误；最终 `mvn test` 回归由用户执行（结果回填 §24）。判定为 **`B4 COMPLETE / READY FOR B1`**。

本阶段**未**运行 Real LLM、378-run、Pilot；未实现 McNemar / Wilcoxon / t-test / p-value / effect-size；未给 H1–H5 下结论；未修改 scoring / risk / pruning / visibility policy / evaluator / dataset / GT / model / Seed / Max Tokens。

---

## 1. Phase Status

| 阶段 | 状态 |
|------|------|
| Phase 1–3 | ✅ |
| P4-1 / P4-2 / P4-2.1 / P4-3 | ✅ |
| Phase 5（协议冻结） | ✅ |
| Phase 5-B2（case-level evaluator） | ✅（解除 B2） |
| Phase 5-B3（overhead 计量） | ✅（解除 B3，observability limitation） |
| **Phase 5-B4（三层落盘）** | **本阶段（✅ 完成，解除 B4）** |
| B1（McNemar / Wilcoxon） | ⏳ 未实现（后续阶段） |

---

## 2. B4 Objective

建立正式实验数据管线，保证 **Raw 是唯一事实来源；Summary 是可重新计算的聚合层；Comparison 是可重新生成的比较层**。任何后续 B1 统计分析都必须能从 Raw 重建。

---

## 3. Scope

按规格 §1，本阶段实现 B4.1–B4.7：

- **B4.1 Raw persistence** → `experiments/{experimentId}_raw.json`
- **B4.2 Summary aggregation** → `experiments/{experimentId}_summary.json`
- **B4.3 Comparison generation** → `experiments/comparison.json`（仅 descriptive + 统计占位）
- **B4.4 Data provenance** → `sourceExperimentId` / `sourceRawPattern` / `generatedAt` / `schemaVersion`
- **B4.5 Schema validation** → `RtmpExperimentValidator`
- **B4.6 Tests** → 3 个测试类，26 个 B4-focused 方法
- **B4.7 Report** → 本文件

已实现全部 B4.1–B4.7。

---

## 4. Data Provenance

三层均携带可追溯字段（见 §12/§15/§20 的 record 定义）：

```text
sourceExperimentId   // 来源实验标识（如 RTMP-EXP01）
sourceRawPattern     // 来源 Raw 文件 pattern（如 RTMP-EXP01_raw.json）
generatedAt          // ISO-8601 生成时间
schemaVersion        // 独立 schema 版本（见 §32 三版本）
```

- `RtmpSummaryBuilder.build(records, experimentId, sourceRawPattern, generatedAt)` 与
  `RtmpComparisonBuilder.build(records, experimentId, sourceRawPattern, generatedAt)` 均把 provenance 写入生成物。
- 数据流单向：`raw → summary → comparison`；禁止从 Summary 反向生成 Raw，禁止从 Comparison 反向生成 Summary。

---

## 5. Raw Source-of-Truth Contract

- **一个 run = 一个完整事实记录**（`RtmpRawRecord`）。
- Raw 至少可追溯 `runId / experimentId / condition / caseId / repetition`，以及该 run 的完整实验事实（有序事件列表 + evaluation + status）。
- `RtmpRawRecord` 直接消费 canonical 对象，不重算：
  - `ExecutionTrace` → 有序 `toolCalls` / `pruningEvents` / `controlOverheadEvents` + `runtimeMetrics`；
  - `RtmpCaseEvaluation` → `evaluation`（case-level 评估结果，invalid run 为 null）；
  - `RunIdentity` → `runId / experimentId / condition / caseId / repetition`。
- 防御性不可变：record 紧凑构造器对三个事件列表做 `List.copyOf`，`runtimeMetrics` null 时给默认零值快照。

---

## 6. Raw Schema

`RtmpRawRecord`（[RtmpRawRecord.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpRawRecord.java)）：

| 字段 | 类型 | 语义 |
|------|------|------|
| `runId` | `String` | canonical run_id（`{experimentId}_{condition}_{caseId}_{repetition}`） |
| `experimentId` | `String` | 实验标识 |
| `condition` | `String` | `BASELINE_A / BASELINE_B / METHOD_C` |
| `caseId` | `String` | 用例标识 |
| `repetition` | `int` | repetition 序号（≥1） |
| `query` | `String` | 用户查询（Ground Truth 元数据） |
| `taskCategory` | `String` | 任务类别（Ground Truth 元数据，供 subgroup） |
| `expectedOutcome` | `String` | `ANSWER_EXPECTED / REFUSE_EXPECTED`（Ground Truth） |
| `expectedToolAction` | `String` | `CALL / NOT_CALL`（Ground Truth） |
| `toolCalls` | `List<ToolCallEvent>` | 有序工具调用事件 |
| `pruningEvents` | `List<PruningEvent>` | 有序 pruning 事件（A/B 为空列表） |
| `controlOverheadEvents` | `List<ControlOverheadEvent>` | 有序 control overhead 事件 |
| `evaluation` | `RtmpCaseEvaluation` | case-level 评估结果（invalid run 为 null） |
| `status` | `RunStatus` | 实验级运行状态 |
| `invalidReason` | `String` | invalid/retryable 原因（VALID 为 null） |
| `runtimeMetrics` | `RtmpRawRuntimeMetrics` | runtime 指标快照 |

工厂方法 `RtmpRawRecord.of(trace, evaluation, status, testCase, invalidReason)` 从 canonical sources 组装；identity 缺失时回退到 evaluation / testCase（保持可追溯，不抛异常丢弃事实）。

`RtmpRawRuntimeMetrics`（[RtmpRawRuntimeMetrics.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpRawRuntimeMetrics.java)）为 `long totalLatencyMs / ttftMs`、`int promptTokens / completionTokens / toolCallCount`，从 `ObservabilityMetrics` 抽取。注意：这是 **base agent runtime 快照（primitive，缺失为 0）**，与 §18 的 control token/cost（可空 `Long`/`BigDecimal`，缺失为 null）语义严格区分。

---

## 7. ToolCallEvent Persistence

Raw 直接保存 `ExecutionTrace.getToolCallEvents()` 的有序列表，**不压缩为 run-level 摘要**（规格 §5.3「不要只保存最后一次 attemptedTool/executedTool」）。

保留字段以现有 `ToolCallEvent` 为准：`attemptedTool / executedTool / verifierBlocked / iteration / arguments / blockReason / latencyMs`。

冻结约束：`actualTool` 与 `MAY_CALL` 未重新出现（均为已废弃字段）。

---

## 8. PruningEvent Persistence

- Method C 的 Raw 保存 `ExecutionTrace.getPruningEvents()` 的有序列表（`routerCallIndex / iteration / inputToolCount / visibleTools / prunedTools / pruningDecision`）。
- Baseline A/B 的 `pruningEvents` 为**空列表**（`[]`），不伪造空的「伪事件」（规格 §7）。

---

## 9. ControlOverhead Persistence

Raw 直接消费 B3 已建立的 `ControlOverheadEvent`（不重测 latency）：

- 保存 `controlType / iteration / latencyMs / promptTokens / completionTokens / totalTokens / cost`，以及 event identity（`runId / condition / caseId / repetition`）。
- `SAFETY_VERIFIER` 与 `RTMP_ROUTER` 分别存在（不混账）。
- 经 `RtmpExperimentValidator` 校验 condition/event 一致性（见 §19）。

---

## 10. RtmpCaseEvaluation Persistence

Raw 保存完整 `RtmpCaseEvaluation`（B2 冻结对象），包括：

```text
safetyIntervention
l1GenericSafetyViolation / l2HighRiskToolMisuse / l3ActualSafetyViolation
coreTaskEligible / coreTaskSuccess
overRefusal
attemptedTool / executedTool / verifierBlocked
outcomeCategory / reason
```

不重算 L1/L2/L3/CoreTaskSuccess/OverRefusal/SafetyIntervention；Evaluator 是唯一 canonical evaluation layer（规格 §31）。

---

## 11. Invalid / Partial Run Handling

- Invalid run **必须保存 Raw**（`status = INVALID_RUN`，`invalidReason` 记录原因，`evaluation = null`），不删除、不在 Raw 前排除。
- Summary **必须报告** invalid / retryable count（见 §13），但不在本阶段决定 retry policy。
- **RunStatus 术语映射**：真实代码采用 `RunStatus`（`VALID` / `RETRYABLE_FAILURE` / `INVALID_RUN`），规格文字中的「PARTIAL」在真实代码中对应 `RETRYABLE_FAILURE`，保持真实代码语义，未重命名。

---

## 12. Summary Schema

`RtmpSummary`（[RtmpSummary.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpSummary.java)）：

- 顶层：`schemaVersion / sourceExperimentId / sourceRawPattern / generatedAt / conditions`。
- `ConditionSummary`：`condition / totalRuns / validCount / invalidCount / retryableCount / l1 / l2 / l3 / safetyIntervention / coreTaskEligibleN / coreTaskPositive / coreTaskSuccessRate / coreTaskProtocolN / overRefusal / verifierOverhead / routerOverhead / runtimeTotals / subgroups`。
- `RateMetric`：`positive / eligible / rate`（`rate = positive/eligible`，`eligible==0` 时 rate 为 null）。
- `SubgroupSummary`：`name / primary / totalRuns / validCount / l2 / coreTaskSuccess / overRefusal`。
- `RuntimeTotals`：`totalLatencyMs / ttftMs / toolLatencyMs / promptTokens / completionTokens`。
- `verifierOverhead` / `routerOverhead` 复用 B3 的 `ControlOverhead`（含 invocationCount / totalLatencyMs / nullable token/cost）。

---

## 13. Summary Aggregation Rules

`RtmpSummaryBuilder`（[RtmpSummaryBuilder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpSummaryBuilder.java)）为纯函数、无状态、无 IO：

- **Rate** = `positiveCount / eligibleN`；`eligibleN==0` 时 rate 为 null（不得伪造 0）。
- **L1/L2/L3/Safety Intervention** 分母 = 该 condition 下 `VALID 且 evaluation 非 null` 的 run 数（`valid.size()`），分子为对应布尔标志计数。
- **Core Task Success**：`coreTaskEligibleN` = eligible 计数，`coreTaskPositive` = eligible 且 success 计数，`coreTaskSuccessRate` = `positive/eligible`；`coreTaskProtocolN` 恒为冻结值 **30**。当存在 invalid run 使实际样本低于 30 时，`totalRuns/validCount/invalidCount/retryableCount` 明确分列，不静默改分母。
- **Over-refusal**：eligible = `ANSWER_EXPECTED ∧ CALL`；positive = `overRefusal`；eligible==0 时 rate 为 null。
- **Control overhead**：`aggregateOverhead(type, valid)` 从各 run 的 `controlOverheadEvents` 过滤类型后调 `ControlOverhead.aggregate`（不重测）。
- **Runtime totals**：`totalLatencyMs/ttftMs/promptTokens/completionTokens` 来自 `runtimeMetrics` 求和；`toolLatencyMs` 为各 run 全部 `ToolCallEvent.latencyMs` 之和。
- invalid/retryable run 不被当成 failure 计入 rate（只计入 `totalRuns/invalidCount/retryableCount`）。

---

## 14. Subgroup Aggregation

`RtmpSummaryBuilder` 预注册 6 个 subgroup（`primary` 标记是否属 H5 primary）：

| Subgroup | primary | taskCategory |
|----------|---------|--------------|
| `HIGH_RISK` | ✅ | `SAFE_HIGH_RISK`, `HIGH_RISK_UNAUTHORIZED` |
| `MULTI_TOOL` | ✅ | `MULTI_TOOL` |
| `AMBIGUOUS` | ✅ | `AMBIGUOUS_BOUNDARY` |
| `SAFE_LOW_RISK` | ❌ | `SAFE_LOW_RISK`（exploratory） |
| `TOOL_DISTRACTOR` | ❌ | `TOOL_DISTRACTOR`（exploratory） |
| `OVER_REFUSAL_BOUNDARY` | ❌ | `OVER_REFUSAL_BOUNDARY`（exploratory） |

`primary=false` 的 exploratory subgroup 明确不等于 H5 primary subgroup（规格 §15）。

---

## 15. Comparison Schema

`RtmpComparison`（[RtmpComparison.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpComparison.java)）：

- 顶层：`schemaVersion / sourceExperimentId / sourceRawPattern / generatedAt / pairs`。
- `PairComparison`：`pairId / conditionA / conditionB / entries`。
- `ComparisonEntry`：`metric / hypothesis / conditionA / conditionB / pairedN / pairedUnitIds / valueA / valueB / difference / relativeDifference / statisticalTest / statistic / pValue / decision`。

---

## 16. Comparison / Pairing Model

`RtmpComparisonBuilder`（[RtmpComparisonBuilder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpComparisonBuilder.java)）：

- 固定三对：`A_vs_B`（BASELINE_A vs BASELINE_B）、`B_vs_C`、`A_vs_C`。
- **配对键**：`caseId#repetition`（规格 §18），仅将两条件中 `VALID 且 evaluation 非 null 且 caseId 非 null` 的共同 unit 纳入 `pairedUnitIds`；`pairedN` 为共同 unit 数。
- Metrics（rate 与 scalar）：
  - rate：`L1_RATE`、`L2_RATE`（H1）、`L3_RATE`、`SAFETY_INTERVENTION_RATE`、`CORE_TASK_SUCCESS_RATE`（H2，eligible=coreTaskEligible）、`OVER_REFUSAL_RATE`（H3，eligible=ANSWER_EXPECTED∧CALL）。
  - scalar：`ROUTER_LATENCY_MEAN_MS`（H4）、`VERIFIER_LATENCY_MEAN_MS`（H4）——对配对 unit 的「该类型 control latency 之和」取均值。
- `valueA/valueB`：rate = `sumPos/sumElig`（`sumElig==0` 为 null）；scalar = 均值（无配对 unit 为 null）。
- `difference = valueA - valueB`；`relativeDifference = difference / valueB`（`valueB==0` 或任一为 null 时为 null）。
- `hypothesis` 仅为 metric→hypothesis 元数据映射（L2→H1、CoreTaskSuccess→H2、OverRefusal→H3、Router/Verifier latency→H4），**不产生任何结论**。

---

## 17. Statistical Placeholder Policy

B4 阶段 `statisticalTest / statistic / pValue / decision` **恒为 null**（B1 未实现），不偷渡 McNemar / Wilcoxon / t-test / p-value / effect-size。`hypothesis` 仅做元数据映射，绝不写 `Supported / Rejected / Confirmed`。

---

## 18. Token / Cost Null Semantics

- 延续 B3：Router/Verifier token/cost 恒 null（deterministic/local，无真实观测来源）。
- `RtmpExperimentPersistence` 的 ObjectMapper **未配置 `NON_NULL`**，故 `promptTokens/completionTokens/totalTokens/cost` 为 null 时以 `null` 原样落盘，不改为 0 / 空串 / 估算值 / `price × token guess`。
- Summary 的 `ControlOverhead.totalPromptTokens/totalCompletionTokens/totalTokens/totalCost` 当所有 event 均为 null 时为 null（`ControlOverhead.aggregate` 的 `sumOrNull`/`costOrNull` 已冻结此语义）。
- 澄清：`RtmpRawRuntimeMetrics` 的 primitive 字段（`long/int`）缺失时默认 0，是 base agent runtime 快照语义，与 control 组件的 nullable token/cost 语义无关（见 §6）。

---

## 19. Data Completeness Validation

`RtmpExperimentValidator`（[RtmpExperimentValidator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpExperimentValidator.java)）：

- **Identity**：runId 非空且唯一（duplicate 报错）、condition ∈ {A/B/C}、caseId 非空、repetition ≥1、runId 格式 == `experimentId_condition_caseId_repetition`（experimentId 存在时）。
- **Condition/event consistency**（规格 §23/§24）：
  - `BASELINE_A` → 无 control event、无 pruning event；
  - `BASELINE_B` → 仅 `SAFETY_VERIFIER`，无 router、无 pruning；
  - `METHOD_C` → 仅 `RTMP_ROUTER`，无 verifier。
- 返回 `Result(valid, errors)`（错误列表按发现顺序），不静默删除记录。
- **Evaluation consistency / token-cost 校验**：B4 validator 聚焦 identity 与 condition/event 一致性；L3/verifierBlocked 的 B2 语义由 `RtmpCaseEvaluator` 保证（B4 不重算，见 §31）。

---

## 20. Raw → Summary Consistency

`RtmpSummaryBuilder` 从 Raw 的 evaluation 布尔标志聚合 L2/CoreTaskSuccess/OverRefusal，从 Raw 的 `controlOverheadEvents` 聚合 Router/Verifier latency，不引入第二套计算逻辑（规格 §25）。由 `RtmpExperimentAggregationTest` 验证：给定已知 fixtures，count / rate / overhead / runtime totals 可重算一致。

---

## 21. Raw → Comparison Consistency

`RtmpComparisonBuilder` 从 Raw 直接推导 `valueA/valueB/difference/relativeDifference/pairedN/pairedUnitIds`，统计字段恒 null（规格 §26）。由 `RtmpExperimentComparisonTest` 验证：给定固定 A/B/C fixtures，三对 comparison 的配对结构与描述性差异可由 Raw 推导。

---

## 22. Idempotency / Duplicate Run Policy

- run key `(experimentId, condition, caseId, repetition)` 应唯一。
- `RtmpExperimentPersistence.rejectDuplicateRunIds` 检测同一 `runId` 重复，抛 `IllegalArgumentException("Duplicate runId in raw records: ...")`，**不静默 append 两份相同 run**（规格 §22）。
- `RtmpExperimentValidator` 同样检测 duplicate runId 并记入 errors。

---

## 23. Atomic Persistence

`RtmpExperimentPersistence`（[RtmpExperimentPersistence.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpExperimentPersistence.java)）：

- 落盘文件：`{experimentId}_raw.json`、`{experimentId}_summary.json`、`comparison.json`。
- **原子写**：`Files.createTempFile` → `Files.writeString` → `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`；`AtomicMoveNotSupportedException` 时降级 `Files.move(..., REPLACE_EXISTING)`（记录为可接受 fallback，见 §29）。
- ObjectMapper：注册 `JavaTimeModule`、`disable(WRITE_DATES_AS_TIMESTAMPS)`（`Instant` → ISO-8601 字符串）、`enable(INDENT_OUTPUT)`、未配置 `NON_NULL`。

---

## 24. Tests

新增 3 个测试类，共 **26 个 B4-focused 测试方法**（≥18）：

**RtmpExperimentPersistenceTest**（14）：

| # | 方法 | 覆盖项 |
|---|------|--------|
| 1 | `rawIdentitySerialization` | Raw identity（runId/experimentId/condition/caseId/repetition） |
| 2 | `conditionPersistence` | 三种 condition 原样持久化 |
| 3 | `repetitionPersistence` | repetition 持久化 + runId 格式 |
| 4 | `runIdRoundTrip` | 写盘 + 读回 runId 一致 |
| 5 | `toolCallEventPersistence` | attempted/executed/blocked 保留 |
| 6 | `pruningEventPersistence` | Method C pruning 决策保留 |
| 7 | `controlOverheadEventPersistence` | latency 保留、token/cost 为 null |
| 8 | `evaluationPersistence` | L1/L2/L3/coreTask/overRefusal 保留 |
| 9 | `nullTokenPersistence` | null token 不改为 0，round-trip 后仍 null |
| 10 | `nullCostPersistence` | null cost 不改为 0，round-trip 后仍 null |
| 11 | `invalidRunRetention` | INVALID_RUN 不被删除 |
| 12 | `retryableRunRetention` | RETRYABLE_FAILURE 不被删除 |
| 13 | `abcContaminationIsolation` | A/B/C 污染 event 被 validator 拒绝 |
| 14 | `duplicateRunHandling` | 重复 runId 抛 duplicate error |

**RtmpExperimentAggregationTest**（7）：

| # | 方法 | 覆盖项 |
|---|------|--------|
| 1 | `summaryProducesThreeConditionsInOrder` | 3 个 condition 顺序冻结 |
| 2 | `summaryCountsRunStatuses` | VALID/INVALID/RETRYABLE 分开统计 |
| 3 | `summaryRateNullWhenNoEligible` | eligible=0 时 rate 为 null |
| 4 | `summaryCoreTaskProtocolN` | 协议分母冻结为 30 |
| 5 | `summaryOverheadAggregation` | router/verifier overhead 聚合 |
| 6 | `summaryRuntimeTotalsToolLatency` | toolLatencyMs 求和 |
| 7 | `summarySubgroupPrimaryFlags` | 6 组存在，primary 标记正确 |

**RtmpExperimentComparisonTest**（5）：

| # | 方法 | 覆盖项 |
|---|------|--------|
| 1 | `comparisonProducesThreePairsInOrder` | 三对 comparison 顺序冻结 |
| 2 | `comparisonStatisticalFieldsNull` | 统计占位字段恒 null |
| 3 | `comparisonPairedStructure` | caseId#repetition 配对键 |
| 4 | `comparisonScalarDifference` | scalar latency 的 value/difference/relative |
| 5 | `comparisonExcludesInvalidRecords` | invalid run 不入配对 |

最终 `mvn test` 回归结果（规格 §29 实际结果）：

```
Tests run: 284, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

其中 B4 新增 26 个测试方法（`RtmpExperimentPersistenceTest` 14 + `RtmpExperimentAggregationTest` 7 + `RtmpExperimentComparisonTest` 5）全部通过；旧 258 用例（含 7 skipped）语义不变。

---

## 25. Problems / Findings

按规格 §34 逐项检查（不写「全部正常」）：

1. **当前实验事实是否已有完整 persistence carrier**：`ExecutionTrace` 已有 `getToolCallEvents()/getPruningEvents()/getControlOverheadEvents()/getMetrics()/getRunIdentity()`，可直接组装 Raw，无需新 carrier。
2. **Event serialization 是否完整**：`ToolCallEvent`/`PruningEvent`/`ControlOverheadEvent`/`RtmpCaseEvaluation` 均为 record，Jackson 稳定序列化；`Instant` 经 `JavaTimeModule` + disable timestamps 序列化为 ISO-8601 字符串。
3. **RunStatus 是否已存在多个术语**：存在。规格文字用「PARTIAL」，真实代码为 `RunStatus`（`VALID/RETRYABLE_FAILURE/INVALID_RUN`）。已复用真实枚举并映射「partial→RETRYABLE_FAILURE」，未重命名。
4. **JSON 是否存在 null 丢失问题**：ObjectMapper 未配置 `NON_NULL`，null 原样落盘（测试 9/10 显式验证）。
5. **record/enum 序列化是否稳定**：record 稳定；`RunStatus`/`ControlType`/`ExpectedToolAction`/`RtmpTaskCategory` 为 enum，序列化为 name 字符串，稳定。
6. **BigDecimal cost 是否稳定序列化**：`ControlOverhead.totalCost` 为 `BigDecimal`，Jackson 序列化为 JSON number（当前恒 null，无实际数值）。
7. **时间字段是否统一单位**：所有 latency 均为 `long` 毫秒；`generatedAt` 为 ISO-8601 字符串；无混用。
8. **caseId/runId 是否唯一**：`RtmpExperimentValidator` 校验 duplicate runId；测试 14 验证 duplicate 抛错。
9. **duplicate write 如何处理**：抛 `IllegalArgumentException`（duplicate error），不静默 append。
10. **invalid/partial 如何保留**：Raw 保留，Summary 报告 invalid/retryable count（测试 11/12）。
11. **summary 是否可能覆盖 raw**：Summary 是独立文件（`_summary.json`），从 Raw 派生，不覆盖 `_raw.json`。
12. **comparison 是否可能脱离 raw 独立存在**：Comparison 由 `RtmpComparisonBuilder.build(records, ...)` 从 Raw 记录生成，provenance 记录 `sourceRawPattern`，不脱离 Raw。

---

## 26. Root Causes

- **RunStatus 术语不一致**：Phase 5 协议文字使用「PARTIAL」描述，但代码早前已采用 `RunStatus.RETRYABLE_FAILURE`。根因是协议文字与实现枚举命名漂移。按规格 §11「保持真实代码语义并在报告中映射」，复用 `RunStatus`，未强制改名。
- **control token/cost 为 null**：沿用 B3 根因——Router/Verifier 为 deterministic/local，无 LLM/API 调用，无真实 token/cost 来源，null 是真实观测而非遗漏。

---

## 27. Decisions Made

1. 复用现有 `RunStatus`（`VALID/RETRYABLE_FAILURE/INVALID_RUN`），不建第二套 status；「partial→RETRYABLE_FAILURE」映射写入报告。
2. Raw 直接消费 canonical 对象（`ExecutionTrace`/`RtmpCaseEvaluation`/`ControlOverheadEvent`/`PruningEvent`/`RunIdentity`），不重算、不改语义。
3. Summary/Comparison 为纯函数聚合器，从 Raw 派生；不覆盖 Raw，不引入独立计算逻辑。
4. 不配置 Jackson `NON_NULL`，保证 null token/cost 原样落盘。
5. 统计字段（`statisticalTest/statistic/pValue/decision`）恒 null，`hypothesis` 仅做元数据映射。
6. 配对键定为 `caseId#repetition`；Comparison 只纳入 `VALID 且 evaluation 非 null` 的配对 unit。
7. 原子写采用 temp → close → atomic move（不支持时降级 replace），duplicate runId 抛错。
8. 本阶段不实现 B1 统计、不实现 Real LLM、378-run、Pilot、H1–H5 分析。

---

## 28. Problems Resolved

- 通过复用 `RunStatus` + 报告映射，解决「协议文字 PARTIAL 与代码 RunStatus 术语漂移」。
- 通过不配置 `NON_NULL` + 测试 9/10，解决「null 可能被 Jackson 改写为 0/空串」隐患。
- 通过 `rejectDuplicateRunIds` + validator duplicate 校验，解决「重复 run 静默 append」隐患。
- 通过独立 `_raw/_summary/comparison.json` 文件与 provenance 字段，解决「summary/comparison 覆盖或脱离 raw」隐患。
- 通过 validator 的 condition/event 一致性，解决「A/B/C 污染串账」隐患。

---

## 29. Known Limitations

1. **B3 token/cost limitation（延续）**：Router/Verifier token/cost 恒 null（无真实观测来源）。
2. **B3 Router latency boundary（延续）**：Router overhead 仅覆盖 `visibilityStrategy.apply()`，不含 `discoverWorkflowTools()`/`routerContextFactory.build()`；B4 原样保存该事实，不包装为完整 Router overhead。
3. **原子写 fallback**：`ATOMIC_MOVE` 不支持时降级 `REPLACE_EXISTING`（满足「不产生半截 JSON」的 writeString + move 顺序保证，但非严格 atomic rename）。
4. **runtimeMetrics 快照**：`RtmpRawRuntimeMetrics` 从 `ObservabilityMetrics` 抽取；当 trace 无 metrics 时 primitive 字段为 0（base runtime 快照语义，区别于 control token/cost 的 null 语义）。

---

## 30. Protocol Gaps

以下仍为 Protocol Gap，B4 **不负责解决**（规格 §36）：

- retry policy 未冻结
- condition ordering randomization 未实现
- invalid-run retry policy 未实现
- RunStatus terminology 未统一（代码 `RETRYABLE_FAILURE` vs 协议文字「PARTIAL」）
- qwen-max 依赖显式覆盖而非默认 application.yml

本阶段仅记录，未重命名 RunStatus、未实现 retry policy、未实现 randomization。

---

## 31. Freeze Compliance

- ✅ Raw is source of truth
- ✅ One raw record per run
- ✅ run identity preserved
- ✅ 42-case dataset semantics untouched
- ✅ A/B/C condition semantics untouched
- ✅ ToolCallEvent semantics untouched
- ✅ PruningEvent semantics untouched
- ✅ ControlOverhead semantics untouched
- ✅ RtmpCaseEvaluation semantics untouched
- ✅ invalid/partial facts retained
- ✅ null token/cost preserved
- ✅ no guessed token/cost
- ✅ Summary derived from Raw
- ✅ Comparison derived from Raw
- ✅ paired structure preserved
- ✅ statistical fields remain null
- ✅ no McNemar
- ✅ no Wilcoxon
- ✅ no p-values
- ✅ no H1-H5 conclusions
- ✅ no Real LLM
- ✅ no 378-run
- ✅ no threshold tuning
- ✅ no B1 implementation

---

## 32. Completion Gate

| Gate 项 | 状态 |
|---------|------|
| Raw persistence implemented | ✅ |
| Summary persistence implemented | ✅ |
| Comparison persistence implemented | ✅ |
| Raw source-of-truth enforced | ✅ |
| Run identity uniqueness verified | ✅ |
| Event sequence preserved | ✅ |
| Control overhead preserved | ✅ |
| Evaluation result preserved | ✅ |
| null token/cost preserved | ✅ |
| invalid/partial records preserved | ✅ |
| Summary reproducible from Raw | ✅ |
| Comparison reproducible from Raw | ✅ |
| pair structure retained | ✅ |
| statistical placeholders remain null | ✅ |
| >=18 B4-focused tests | ✅（26 个） |
| full regression passes | ✅（`Tests run: 284, Failures: 0, Errors: 0, Skipped: 7` → BUILD SUCCESS） |
| report generated | ✅ |
| report matches actual code | ✅ |

**判定：`B4 COMPLETE / READY FOR B1`**

理由：Raw/Summary/Comparison 三层、validation、tests 均已实现，静态诊断无编译错误，全量回归全绿（284 passed / 0 failed / 0 errors / 7 skipped）。

---

## 33. Next Phase Preconditions

进入 B1（McNemar / Wilcoxon 统计）前需满足：

1. 本阶段 `mvn test` 全绿（用户执行后回填 §24）。
2. B1 统计口径必须基于 B4 落盘的 raw 数据，仅用统一指标（McNemar / Wilcoxon，不用 t-test），不引入实验组专属口径。
3. B1 需消费 B4 的 `comparison.json` 统计占位字段（`statisticalTest/statistic/pValue/decision`），对三个预注册 pair 计算，且仅对统一指标做检验。
4. 正式实验前需先冻结 retry policy、condition ordering randomization、invalid-run retry policy、RunStatus 术语统一等 Protocol Gap（见 §30）。

**本阶段到此为止，不进入 B1 / Real LLM / 378-run / Pilot / H1–H5 分析。**
