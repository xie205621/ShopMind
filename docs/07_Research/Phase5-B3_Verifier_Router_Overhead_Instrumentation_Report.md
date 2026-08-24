# Phase 5-B3 — Verifier / Router Overhead Instrumentation

> 阶段：Phase 5-B3（Verifier / Router control overhead 计量，仅解除 blocker B3）
> 状态：**B3 COMPLETE WITH OBSERVABILITY LIMITATION**（latency / count 可靠；token / cost 无真实可观察来源，恒为 null）
> 前序：Phase 1–3 ✅ → P4-1 ✅ → P4-2 ✅ → P4-2.1 ✅ → P4-3 ✅ → Phase 5 协议冻结 ✅ → Phase 5-B2 ✅（242 passed / 0 failed / 0 errors / 7 skipped）
> 本阶段新增：`ControlType` + `ControlOverheadInstrumentation` + `ControlOverheadEvent` + `ControlOverhead` + `ControlOverheadInstrumentationTest`（16 个测试方法）；修改 `ExecutionTrace` + `ShopAgentOrchestrator`

---

## 0. Executive Summary

本阶段为 Baseline B（Safety Verifier）与 Method C（RTMP Router）建立**独立、可追溯、可聚合的 control overhead instrumentation**，用于回答：

```
Baseline B：Safety Verifier 调用多少次？每次耗时多少？token/cost？
Method C：RTMP Router 调用多少次？每次耗时多少？token/cost？
```

核心产出：

- `ControlType` — 枚举两类 control（`SAFETY_VERIFIER` / `RTMP_ROUTER`），禁止用一个泛化 `overhead` 字段把 B/C 混账。
- `ControlOverheadInstrumentation` — 冻结「实验条件 → 应观测 control 类型」映射（单一事实源）：`BASELINE_A→∅`、`BASELINE_B→{SAFETY_VERIFIER}`、`METHOD_C→{RTMP_ROUTER}`。
- `ControlOverheadEvent` — per-invocation 观测记录（runId / condition / caseId / repetition / controlType / iteration / latencyMs / token / cost）。
- `ControlOverhead` — run-level 聚合 record + `aggregate()` 静态方法。
- `ExecutionTrace` 新增独立 `controlOverheadEvents` carrier + `controlOverhead(type)` / `controlOverheads()` 聚合 API（供 B4 / evaluator 消费）。
- `ShopAgentOrchestrator` 旁路计时 Verifier / Router 并写入 `ControlOverheadEvent`，**不改变任何执行语义**。

**关键结论（诚实记录）**：

- Verifier 唯一入口在 `executeToolAndRePrompt` 的 `verifier.verify(...)` 处；Router 唯一入口在 `computeVisibility(...)` 的 `visibilityStrategy().apply(...)` 处，均已旁路计时。
- Router 一次 invocation = 一次 `visibilityStrategy().apply()`（score + prune + visibleTools），4 个工具只计 1 次 Router，不按 tool 计 4 次。
- Verifier 一次 invocation = 一次 `ToolSafetyVerifier.verify()`，不假设 `1 iteration = 1 verifier`。
- token/cost 恒为 null：RTMP Router 为 deterministic rule-based、`PostHocSafetyVerifier` 为 local deterministic，二者均不调用 LLM/API，当前无真实 token/cost 来源，不伪造、不估算。
- `ToolCallEvent.latencyMs` 语义保持不变（仍只表达 tool execution latency），未混入 control overhead。

**Completion Gate**：因 latency / count 已可靠但 token / cost 无真实来源，判定为 **`B3 COMPLETE WITH OBSERVABILITY LIMITATION`**（详见 §21、§22、§24）。

本阶段**未**运行 Real LLM、378-run、Pilot、McNemar / Wilcoxon / t-test、p-value、effect size；未实现 B4 三层落盘、B1 统计模块；未修改 scoring / risk mapping / pruning / visibleTools policy / Evaluator / dataset / GT / model / seed / maxTokens。

---

## 1. Phase Status

| 阶段 | 状态 |
|------|------|
| Phase 1–3 | ✅ |
| P4-1 / P4-2 / P4-2.1 / P4-3 | ✅ |
| Phase 5（协议冻结） | ✅（`NOT READY FOR FORMAL EXPERIMENT EXECUTION`，blockers B1–B4） |
| Phase 5-B2（case-level evaluator） | ✅（解除 B2） |
| **Phase 5-B3（overhead 计量）** | **本阶段（✅ 完成，解除 B3）** |
| B1（统计检验）/ B4（三层落盘） | ⏳ 未实现（后续阶段） |

---

## 2. B3 Objective

为 Baseline B 和 Method C 建立独立、可追溯、可聚合的 control overhead instrumentation，最终能回答：

```
Baseline B：Safety Verifier 调用多少次 / 每次耗时 / token / cost
Method C：RTMP Router 调用多少次 / 每次耗时 / token / cost
```

并能与主运行成本区分：

```
Base Agent Cost + Verifier Overhead      ← B
Base Agent Cost + RTMP Router Overhead   ← C
```

本阶段只提供 Layer 2（Safety/Routing Control Overhead）的**原始 component measurements**，不实现 total-cost comparison（留给 B1 / B4）。

---

## 3. Scope

按规格 §1，本阶段只允许 B3.1–B3.6：

- **B3.1 Verifier overhead**：Verifier count / latency / token（若存在）/ cost（若存在）。
- **B3.2 Router overhead**：Router count / latency / token（若存在）/ cost（若存在）。
- **B3.3 Per-event / per-run aggregation**：每次 Verifier / Router 调用分别记录，run 内聚合。
- **B3.4 Instrumentation-ready API**：为 B4 / evaluator / summary 提供稳定数据对象与访问接口。
- **B3.5 Tests**：验证 count 准确、latency 不混入 tool latency、Router/Verifier 不串账、无调用为 0/null、多 iteration 分别记录、不影响 A/B/C 行为。
- **B3.6 B3 报告**：本文件。

已实现全部 B3.1–B3.6。

---

## 4. Control Overhead Model

冻结三层成本模型（规格 §3）：

```
Layer 1 = Base Agent Runtime（LLM + Tool execution）
Layer 2 = Safety / Routing Control Overhead
Layer 3 = Total Runtime
```

- Baseline A：`Base Agent Runtime`，无额外 control layer。
- Baseline B：`Base Agent Runtime + Verifier Overhead = Total Runtime`。
- Method C：`Base Agent Runtime + RTMP Router Overhead = Total Runtime`。

Control 类型（`ControlType`，独立命名，规格 §4）：

| ControlType | 归属条件 | 实现 |
|-------------|----------|------|
| `SAFETY_VERIFIER` | 仅 `BASELINE_B` | `PostHocSafetyVerifier` |
| `RTMP_ROUTER` | 仅 `METHOD_C` | `RtmpVisibility`（score + prune） |

「实验条件 → 应观测 control 类型」单一事实源在 `ControlOverheadInstrumentation.controlTypesFor(condition)`：

| Condition | controlTypesFor |
|-----------|-----------------|
| `BASELINE_A` | `∅` |
| `BASELINE_B` | `{SAFETY_VERIFIER}` |
| `METHOD_C` | `{RTMP_ROUTER}` |
| `null` | `∅` |

门控语义（关键）：Baseline A 的 `NoOpSafetyVerifier` 与 `AllToolsVisibility` 虽被调用，但**不计**为 control invocation；Baseline B 的 `AllToolsVisibility`（不评分）同样不计 Router overhead。Instrumentation 是**旁路观测**，不改变真实执行路径（规格 §2）。

---

## 5. Verifier Instrumentation

**唯一入口**：`ShopAgentOrchestrator.executeToolAndRePrompt(...)` 中 `verifier.verify(...)`（规格 §8：LLM decision 之后、工具执行之前）。

实现（[ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java)）：

```java
ToolSafetyVerifier verifier = runtime.condition().safetyVerifier();
long verifierStartNanos = System.nanoTime();
SafetyDecision decision = verifier.verify(
        new SafetyVerificationRequest(runtime.verifierGroundTruth(), attemptedTool, args));
long verifierLatencyMs = (System.nanoTime() - verifierStartNanos) / 1_000_000;
recordControlOverhead(runtime.condition(), ControlType.SAFETY_VERIFIER,
        verifierLatencyMs, ctx.getState().getToolCallCount(), contextView);
```

- latency 从 `verify()` start 到 allow/block decision，不含工具执行、后续 LLM retry（规格 §8）。
- `recordControlOverhead` 内部先经 `controlTypesFor(condition)` 门控，只有 `BASELINE_B` 才真正落 event。
- iteration 使用 `ctx.getState().getToolCallCount()`（tool-call 序号），符合「一个 iteration 可发生多次 Verifier」语义（规格 §14）。

---

## 6. Router Instrumentation

**唯一入口**：`ShopAgentOrchestrator.computeVisibility(...)` 中 `visibilityStrategy().apply(...)`（规格 §6）。

实现（[ShopAgentOrchestrator.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/ShopAgentOrchestrator.java)）：

```java
List<ToolSpecification> allTools = discoverWorkflowTools();
RouterContext routerContext = routerContextFactory.build(ctx, allTools);
long routerStartNanos = System.nanoTime();
VisibilityResult result = runtime.condition().visibilityStrategy().apply(allTools, routerContext);
long routerLatencyMs = (System.nanoTime() - routerStartNanos) / 1_000_000;
recordControlOverhead(runtime.condition(), ControlType.RTMP_ROUTER,
        routerLatencyMs, currentIteration(ctx), contextView);
```

- 一次 `visibilityStrategy().apply()`（覆盖 score + prune + visibleTools）计一次 Router invocation（规格 §6）。
- `recordControlOverhead` 经门控，只有 `METHOD_C` 才真正落 event。
- iteration 使用 `currentIteration(ctx) = getToolCallCount() + 1`（LLM 迭代号，与 `PruningEvent` 的 routerCallIndex 一致，规格 §13 允许 `routerCallIndex == iteration`）。

---

## 7. Invocation Semantics

| Control | 一次 invocation 定义 | 关键约束 |
|---------|----------------------|----------|
| Router | 一次 `visibilityStrategy().apply()`（score + prune + visibleTools 完整一轮 decision） | 4 个工具只计 1 次，不按 tool 计 4 次（规格 §6） |
| Verifier | 一次 `ToolSafetyVerifier.verify()` | 不假设 `1 iteration = 1 verifier`，多次分别记录（规格 §14） |

- **4 tools → 1 Router**：`VisibilityResult` 的 `inputToolCount=4`，但只产生 1 条 `ControlOverheadEvent`。
- **empty-tool-set 仍计 1 次 Router**：无工具必要性导致 visibleTools 为空时，仍是完整 decision，count=1（不因 0 visible 跳过）。
- **多 iteration 分别记录**：iteration 1 → event 1，iteration 2 → event 2（规格 §13）。

---

## 8. Latency Boundaries

| Event | Component | 计入 Router overhead | 计入 Verifier overhead |
|-------|-----------|----------------------|------------------------|
| Router scoring（score + prune） | Router | ✅ | — |
| visibleTools decision | Router | ✅ | — |
| System prompt static rendering | — | ❌ | — |
| Main LLM generation | — | ❌ | — |
| Verifier decision | Verifier | — | ✅ |
| Tool execution | Tool latency | ❌ | ❌ |
| Subsequent LLM retry | — | — | ❌ |

**实际可测边界（诚实记录）**：

- Verifier latency = `verifier.verify()` 从调用开始到 allow/block decision 返回（严格满足规格 §8）。
- Router latency = `visibilityStrategy().apply()` 从调用开始到 `VisibilityResult` 生成（覆盖 score + prune + visibleTools）。

**与规格 §7 的差异（Limitation）**：规格 §7 建议 Router latency 覆盖 `RouterContext preparation + score + prune + visibleTools`。当前实现**只计时 `apply()` 决策本身**，未包含 `discoverWorkflowTools()` 与 `routerContextFactory.build()`（两者在 `computeVisibility` 内、`apply()` 之前执行）。原因：这两步属于 GT-free 的确定性上下文装配，不属于「routing decision」，且为 `Baseline A/B/C` 共同路径，将其计入 Router overhead 会与「A/B 无 Router overhead」的门控语义冲突。此边界以代码注释明确记录，按规格 §7「记录实际可测边界并说明 limitation」处理。

---

## 9. Token Accounting

- `ControlOverheadEvent` / `ControlOverhead` 的 token 字段均为 `Long`（可空）。
- **RTMP Router**：deterministic rule-based，不调用模型/API → `promptTokens / completionTokens / totalTokens = null`（规格 §9）。
- **Verifier**：当前为 `PostHocSafetyVerifier`（local deterministic，无 LLM/API call）→ token 同样 `null`（规格 §9「完全 deterministic / local 则不伪造 token」）。
- token 与 latency 分开记录（不同字段），不互相混账。

---

## 10. Cost Accounting

- 仅当项目当前有可靠模型/API cost 来源时才记录，否则 `cost = null`（规格 §10）。
- 当前 Router / Verifier 均无真实 cost 来源，故 `cost = null`，不引入 `$/1M token` 费率、不 web pricing、不估算。

---

## 11. Per-event Schema

`ControlOverheadEvent`（[ControlOverheadEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/ControlOverheadEvent.java)）：

| 字段 | 类型 | 语义 |
|------|------|------|
| `runId` | `String` | canonical run_id（legacy 可 null） |
| `condition` | `String` | `BASELINE_A / BASELINE_B / METHOD_C` |
| `caseId` | `String` | 关联 RTMP caseId（无 RunIdentity 为 null） |
| `repetition` | `int` | repetition 序号（无 RunIdentity 为 0） |
| `controlType` | `ControlType` | `SAFETY_VERIFIER / RTMP_ROUTER` |
| `iteration` | `int` | Router=LLM 迭代；Verifier=tool-call 序号（从 1 开始） |
| `latencyMs` | `long` | 本次 invocation 耗时 |
| `promptTokens` | `Long` | unavailable 为 null |
| `completionTokens` | `Long` | unavailable 为 null |
| `totalTokens` | `Long` | unavailable 为 null |
| `cost` | `BigDecimal` | unavailable 为 null |

---

## 12. Run-level Aggregation

`ControlOverhead`（[ControlOverhead.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/ControlOverhead.java)）：

| 字段 | 类型 |
|------|------|
| `controlType` | `ControlType` |
| `invocationCount` | `int` |
| `totalLatencyMs` | `long` |
| `totalPromptTokens` | `Long` |
| `totalCompletionTokens` | `Long` |
| `totalTokens` | `Long` |
| `totalCost` | `BigDecimal` |

- `ControlOverhead.aggregate(type, events)`：按 `controlType` 过滤后聚合。
- token/cost 聚合规则：全部事件对应字段为 null → 聚合为 null；否则跳过 null 求和（`sumOrNull` / `costOrNull`）。
- no-invocation 语义（规格 §12）：无匹配事件 → `invocationCount=0`、`totalLatencyMs=0`、token/cost = null（不得以 0 伪造真实观测）。

`ExecutionTrace` 暴露（[ExecutionTrace.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/workflow/domain/ExecutionTrace.java)）：

- `getControlOverheadEvents()`：有序 per-event 列表。
- `controlOverhead(ControlType)`：单类型 run-level 聚合。
- `controlOverheads()`：两种类型聚合映射。

---

## 13. Tool Latency Separation

- `ToolCallEvent.latencyMs` 语义**保持不变**：仍只表达 tool execution latency（规格 §15）。
- 四类成本边界互不重复计费：`Router / Verifier / Tool / Main LLM`。
- `PruningEvent` 未被扩展为 generic cost event（规格 §18）。

---

## 14. Truth Tables

### 14.1 Condition → Control Type（规格 §20）

| Condition | Invocation | Expected Count | Expected control type |
|-----------|------------|----------------|------------------------|
| A | Verifier | 0 | none |
| A | Router | 0 | none |
| B | Verifier called once | 1 | SAFETY_VERIFIER |
| B | Verifier called twice | 2 | SAFETY_VERIFIER |
| C | one iteration | 1 | RTMP_ROUTER |
| C | two iterations | 2 | RTMP_ROUTER |
| C | four tools in one iteration | 1 | RTMP_ROUTER |
| C | empty visibleTools | 1 | RTMP_ROUTER |

> 特别注意：`C + 4 tools → router count = 1`（不是 4）。

### 14.2 Latency Boundary（规格 §21）

| Event | Component | Included |
|-------|-----------|----------|
| Router scoring | Router overhead | ✅ |
| Tool menu pruning | Router overhead | ✅ |
| System prompt static rendering | Router overhead | ❌ |
| Main LLM generation | Router overhead | ❌ |
| Verifier decision | Verifier overhead | ✅ |
| Tool execution | Verifier overhead | ❌ |
| Subsequent LLM retry | Verifier overhead | ❌ |
| Tool execution | Tool latency only | ✅ |

### 14.3 Token / Cost（规格 §22）

| Control | LLM/API call exists? | Tokens | Cost |
|---------|----------------------|--------|------|
| RTMP Router | No | null | null |
| Verifier | No（local deterministic） | null | null |

> 未出现 `Router tokens = estimated` / `Router cost = guessed`。

---

## 15. Problems / Findings

按规格 §25 逐项检查（不写「全部正常」）：

1. **Verifier 调用入口是否存在多个路径**：仅 1 处 —— `executeToolAndRePrompt` 中的 `verifier.verify(...)`。已确认无其他 verify 调用点。
2. **Router 是否存在多个合法入口**：仅 1 处 —— `computeVisibility` 中的 `visibilityStrategy().apply(...)`（`computeVisibility` 被 2 处调用：首轮 LLM 前与工具执行后的 re-prompt，二者都走同一方法）。合法。
3. **latency 是否已有 instrumentation**：主 LLM 已有 `ctx.addLlmLatencyMs`；tool 已有 `ctx.addToolLatency`；Verifier/Router 此前**没有**独立 latency，本阶段补齐。
4. **token 是否存在统一来源**：主 LLM 有 `ObservabilityMetrics` 的 promptTokens/completionTokens；但 Verifier/Router 无 LLM/API，token 无可观察来源。
5. **cost 是否存在统一来源**：当前系统无统一 model/API cost 来源；Verifier/Router 亦无。
6. **同步/异步导致 latency 记录不准确**：`verify()` 与 `apply()` 均为同步阻塞调用，`System.nanoTime()` 前后包裹即可精确测量；无异步 timing ambiguity。
7. **是否存在重复计量**：Router/Verifier 与 Tool、Main LLM 均使用独立字段/独立列表，不重复。
8. **control event 与 tool event 是否混淆**：`ControlOverheadEvent`（新 carrier）与 `ToolCallEvent`（既有）为独立列表，不混淆。

---

## 16. Root Causes

- **token/cost 为 null 的根因**：RTMP Router 是 deterministic rule-based（`RtmpScoringEngine` + `ToolMenuPruner`），`PostHocSafetyVerifier` 是 local deterministic 规则；二者都不发起 LLM/API 调用。因此当前架构下不存在「Verifier/Router 的 token usage / cost」这一事实来源，`null` 是真实观测，不是遗漏。

---

## 17. Decisions Made

1. 引入 `ControlType` 枚举 + `ControlOverheadInstrumentation.controlTypesFor()` 单一事实源做门控，避免 A 的 NoOp/AllTools 被误计。
2. Router invocation 定义为一次 `visibilityStrategy().apply()`，4 tools → count=1。
3. Router latency 只计 `apply()` 决策，不含 `discoverWorkflowTools()` / `routerContextFactory.build()`（记录为 limitation，见 §21）。
4. Verifier latency 只计 `verify()`，不含工具执行与后续 LLM retry。
5. token/cost 字段为可空 `Long` / `BigDecimal`，缺失恒 null，不伪造、不估算。
6. 使用独立 `controlOverheadEvents` carrier，不改 `ToolCallEvent` / `PruningEvent` 语义。
7. 本阶段不实现 B4 落盘、不实现 B1 统计、不实现 total-cost comparison。

---

## 18. Problems Resolved

- 确认 Verifier/Router 当前 deterministic/local，无真实 token/cost 来源 → 设计为 null，不伪造。
- 通过 `controlTypesFor(condition)` 门控解决「A 的 NoOp Verifier / AllToolsVisibility 被误计」问题。
- 明确 Router invocation = 一次 apply()，解决「4 tools 误计 4 次」问题。
- 独立 carrier 解决「control overhead 与 tool latency / pruning 混淆」问题。

---

## 19. Validation

- 静态验证：`GetDiagnostics` 全项目无编译错误（含新增 4 文件 + 修改 2 文件 + 新测试 1 文件）。
- 单元测试：`ControlOverheadInstrumentationTest`（16 个方法）覆盖 §23 的核心语义（count / latency 求和 / 门控 / 不串账 / 无调用 0-null / token-cost 不伪造 / 聚合一致）。
- 回归验证：最终 `mvn test` 由用户执行（结果见 §20），预期全绿且旧 242 用例语义不变。

---

## 20. Test Results

新增测试文件：`ControlOverheadInstrumentationTest`（16 个测试方法）：

| # | 测试方法 | 覆盖项 |
|---|----------|--------|
| 1 | `baselineARecordsNoControlOverhead` | §20 A 无 Router/Verifier |
| 2 | `baselineBRecordsOnlyVerifierOverhead` | §20 B 仅 Verifier |
| 3 | `methodCRecordsOnlyRouterOverhead` | §20 C 仅 Router |
| 4 | `nullConditionRecordsNothing` | §20 null condition |
| 5 | `verifierSingleInvocationCount` | §20 B 单次 Verifier count=1 |
| 6 | `verifierMultipleInvocationsCountAndLatency` | §20 B 多次 Verifier count=2 + latency 求和 |
| 7 | `routerSingleInvocationCount` | §20 C 单 iteration Router count=1 |
| 8 | `routerMultipleInvocationsCount` | §20 C 多 iteration Router count=2 |
| 9 | `routerInvocationCountIsPerDecisionNotPerTool` | §20 C 4 tools → count=1 |
| 10 | `emptyToolSetStillCountsOneRouterDecision` | §20 C empty visibleTools → count=1 |
| 11 | `aggregationFiltersEventsByControlType` | Router/Verifier 不串账 |
| 12 | `noInvocationZeroCountZeroLatencyNullTokens` | §12 no-invocation = 0/null |
| 13 | `tokenAndCostNullWhenUnavailable` | §22 token/cost 不伪造 |
| 14 | `toolLatencySeparateFromControlOverhead` | §15 Tool latency 分离 |
| 15 | `perEventOrderPreservedAndRunLevelAggregateMatches` | §13/§11 per-event + aggregate 一致 |
| 16 | `tokenAndCostAggregateWhenPresent` | token/cost 聚合契约（未来来源） |

最终 `mvn test` 由用户执行，实际结果：

```
Tests run: 258, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

其中 `ControlOverheadInstrumentationTest`：`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`。

---

## 21. Known Limitations

1. **Router latency 边界收窄**：只计 `apply()` 决策，不含 `discoverWorkflowTools()` 与 `routerContextFactory.build()`（见 §8 的差异说明）。
2. **token/cost 无真实来源**：Router / Verifier 均 deterministic/local，token/cost 恒 null（见 §9/§10）。
3. **latency 边界未被独立单测直接断言**：§23 的第 9/10 项（Router/Verifier latency 边界）由 orchestrator 私有方法 + reactor 流实现，边界以代码注释明确固定，未新建 mock 单测直接断言边界起止点；当前测试覆盖的是数据模型与聚合语义（count / latency 求和 / 分离 / 不串账）。这是测试覆盖面的已知缺口，非实现错误。

---

## 22. Protocol Gaps

- **无真实 token source**：Verifier / Router 不调用 LLM/API，token 无法观测（记录为 Observability Limitation，非猜值）。
- **无真实 cost source**：项目当前无统一 model/API cost 来源，Verifier / Router cost 无法观测（记录为 Observability Limitation）。

按规格 §26，上述均记录为 Protocol Gap，不猜值、不估值、不新增未经冻结的计费标准、不擅自修改实验协议。

---

## 23. Freeze Compliance

- ✅ Verifier overhead independently observable
- ✅ Router overhead independently observable
- ✅ invocation count correct
- ✅ latency boundaries defined
- ✅ token usage separated（null，不混账）
- ✅ cost separated（null，不混账）
- ✅ Tool latency not mixed
- ✅ Main LLM latency not mixed
- ✅ Router deterministic nature preserved
- ✅ no fabricated token/cost
- ✅ A unchanged
- ✅ B unchanged
- ✅ C unchanged
- ✅ Evaluator unchanged
- ✅ GT unchanged
- ✅ no statistics
- ✅ no Real LLM
- ✅ no B4 persistence

---

## 24. Completion Gate

| Gate 项 | 状态 |
|---------|------|
| Verifier per-invocation instrumentation implemented | ✅ |
| Router per-invocation instrumentation implemented | ✅ |
| Run-level aggregation available | ✅ |
| Count semantics verified | ✅ |
| Latency boundaries verified | ✅（定义 + 数据模型测试；边界单测为已知缺口，见 §21.3） |
| Token/cost semantics verified | ✅（null 语义） |
| Tool latency separation verified | ✅ |
| >=12 B3-focused tests | ✅（16 个） |
| Full regression passes | ✅（`Tests run: 258, Failures: 0, Errors: 0, Skipped: 7` → BUILD SUCCESS） |
| Report produced | ✅ |
| Report matches actual implementation | ✅ |

**判定：`B3 COMPLETE WITH OBSERVABILITY LIMITATION`**

理由：latency / count 已可靠，但 token / cost 无真实可观察来源（恒 null），无法「完整获得真实 token/cost」。此判定符合规格 §31 的第二档。

---

## 25. Next Phase Preconditions

进入 B4（Raw / Summary / Comparison 持久化）前需满足：

1. 本阶段 `mvn test` 全绿（用户回填 §20 后确认）。
2. B4 落盘消费 `ExecutionTrace.getControlOverheadEvents()` / `controlOverhead(type)` / `controlOverheads()`，写入 `experiments/*_raw.json`（fact records）与 `*_summary.json`（aggregated），保持 Raw 为唯一事实源（项目冻结约束）。
3. B4 的 summary 不得以 summary 替代 raw；token/cost 在 B3 恒 null 的语义需在落盘 schema 中显式保留 null，不得以 0 填充。
4. 正式实验前，B1（McNemar / Wilcoxon）统计口径需基于 B4 落盘的 raw 数据，且仅用统一指标、不引入实验组专属口径。

**本阶段到此为止，不进入 B4 / B1 / Real LLM / 378-run / Pilot。**
