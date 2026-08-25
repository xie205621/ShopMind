# Phase 5 — R6.1 — Retry Attempt Crash Consistency Closure

> 阶段性质：R6（Process Interruption Recovery & Checkpoint Integrity）的执行完整性补丁。
> 范围：只修复 R6 中「attempt-level retry accounting 在 process interruption 后可能重置」这一具体问题，并收口两个伴生缺陷：**（a）最终 attempt 的 COMPLETED 与 canonical checkpoint 的写入顺序存在丢失窗口**；**（b）attempt ledger 的 corrupted-state 校验不足以 fail-closed**。
> 本阶段不运行 Real LLM / Pilot / 378 formal runs；不修改 RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / Evaluator / statistics / 42 cases / runtime fixture / `memoryId == runId` / RunStatus 三态。

---

## 0. Executive Summary

R6 只对 **canonical final completion** 做了 checkpoint，没有对 **attempt 级执行事实**做持久化。因此当 `attempt1 RETRYABLE` 之后、canonical checkpoint 落盘之前发生进程中断，resume 会从 `attempt1` 重新计数，导致同一个 canonical run 实际产生 **3 次真实 invocation**，违反 run-level `max retry = 1`。

R6.1 新增 crash-safe attempt ledger（`{experimentId}_attempt-ledger.jsonl`），在每次真实 invocation 前持久化 `STARTED`、完成后持久化 `COMPLETED + status`，并在恢复时据此重建「哪些 attempt 已被消耗」，只执行 remaining attempt。

在初版实现后，又收口了两个伴生缺陷：

1. **写入顺序（问题 2）**：最终 canonical attempt 的写序改为 `STARTED → 真实 invocation → 构建 canonical RtmpRawRecord → checkpoint durable → COMPLETED durable`。消除「`COMPLETED(2, VALID)` 已 fsync、但 canonical checkpoint 尚未 fsync」这一丢失窗口——checkpoint 成功但 COMPLETED 前 crash，恢复时该 unit 已被视为 completed 而 skip，安全。
2. **fail-closed 校验（问题 3）**：`RtmpAttemptLedgerStore` 在 `load()` 阶段做单事件结构校验，并新增 `validate(events, experimentId, plan)` 做 identity + 跨事件校验。任何未知 runId、错误 experimentId、重复 STARTED/COMPLETED、`STARTED(2)` 缺 `STARTED(1)`、`COMPLETED(n)` 缺 `STARTED(n)`、非法 eventType / attempt / status 均被拒绝，绝不静默继续。

核心结论：

- ✅ 新增 [RtmpAttemptLedgerEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerEvent.java)（STARTED / COMPLETED + status）
- ✅ 新增 [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java)（JSONL append + fsync + 结构/跨事件/identity 校验）
- ✅ [RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java) 接入 attempt ledger、恢复重建、以及「checkpoint 先于最终 COMPLETED」写序
- ✅ 恢复时：已 completed unit → skip；STARTED 无 COMPLETED 的 attempt → 已消耗；只执行 remaining attempt
- ✅ 最终 canonical attempt 的 checkpoint 先于 COMPLETED 落盘，消除已完成 observation 丢失窗口
- ✅ ledger 校验 fail-closed：坏 ledger 拒绝启动，绝不静默忽略
- ✅ 不新增 `RunStatus` 枚举值；不改变 `runId` / `memoryId`
- ✅ retry attempt 不产生新的 statistical unit；canonical final record 仍是 Raw 唯一正式事实源
- ✅ 测试 A–F + corruption validation tests 已编写；`mvn test` 

---

## 1. 原问题（Problem）

当前语义（R6 阶段）可能出现：

```text
attempt1 RETRYABLE
→ process crash before canonical checkpoint
→ resume
→ runWithRetry 从 attempt1 重新计数
→ attempt1 + attempt2
→ 实际形成 3 次真实 invocation
```

这违反 run-level `max retry = 1`（[RtmpRunRetryPolicy.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpRunRetryPolicy.java) 冻结 `MAX_RETRY = 1`）。

---

## 2. 为什么原 R6 会潜在超过 max retry = 1

R6 的恢复机制只记录 **canonical final completion**（`RtmpExecutionCheckpoint`）。其恢复语义是：

```text
已 checkpoint 的 unit → skip
未 checkpoint 的 unit → 重新执行 runWithRetry（从 attempt1 开始）
```

问题在于：`runWithRetry` 的 attempt 计数只存在于**进程内存**，不是 crash-safe 的。对于「`attempt1 RETRYABLE` 已真实执行，但 crash 发生在 `attempt2` 与 canonical checkpoint 落盘之间」这一窗口：

- crash 前：`attempt1` 已真实执行一次（RETRYABLE），但 attempt 计数未持久化。
- resume 后：runner 认为该 unit 从未执行，重新调用 `runWithRetry`，再次执行 `attempt1`（第 2 次真实 invocation），再执行 `attempt2`（第 3 次真实 invocation）。

合计 3 次真实 invocation，突破 `max retry = 1`。这正是 R6 报告 §6「attempt1 RETRYABLE + crash → 重新执行 canonical unit」这一表述所隐含的缺陷——它在「未突破 max retry=1」的结论上是不成立的，因为 attempt 计数在恢复时被重置。

---

## 3. 新 attempt accounting semantics

为每个 canonical run 建立 **crash-safe attempt execution provenance**，与 canonical final completion（checkpoint）**分离**：

1. attempt 开始前持久化 `STARTED`。
2. attempt 完成后持久化 `COMPLETED + status`。
3. canonical final completion 仍由 `RtmpExecutionCheckpoint` 单独记录。

attempt 序号恒为 1 或 2；`STARTED` 事件的「最大 attempt 序号」即为该 run 已消耗的真实 invocation 数。因为 attempt 严格顺序执行，所以：

```text
consumedAttempts = max(attempt with STARTED)
```

新增数据结构：

| 文件 | 职责 |
|---|---|
| [RtmpAttemptLedgerEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerEvent.java) | attempt 生命周期事件（`STARTED` / `COMPLETED + status`） |
| [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java) | JSONL append + fsync；`consumedAttempts` / `lastCompletedStatus` 聚合；`validate` fail-closed 校验 |

ledger 文件为 `{experimentId}_attempt-ledger.jsonl`，是 recovery provenance artifact，不是统计输入；canonical final Raw 仍由 checkpoint 承载，Raw 是唯一正式事实源。

---

## 4. 写入顺序（问题 2：COMPLETED 与 checkpoint 的 crash window）

初版写序是 `STARTED → invocation → COMPLETED → checkpoint`。它产生一个很窄但真实的 crash window：当 `COMPLETED(2, VALID)` 已 fsync、而 canonical checkpoint 尚未 fsync 时 JVM crash，恢复后 ledger 有 `COMPLETED(2, VALID)` 但 checkpoint 缺失，`buildAttemptRecoveryMap` 看到 `consumed = 2` 直接抛异常——已完成 observation 无法恢复，只能拒绝继续。

R6.1 最终写序修正为：对**最终 canonical attempt**：

```text
STARTED
→ 真实 invocation
→ 构建 canonical RtmpRawRecord
→ checkpoint durable（fsync）
→ COMPLETED durable（fsync）
```

由此：

- checkpoint 成功、`COMPLETED` 前 crash → 恢复时 canonical checkpoint 已存在 → 该 unit 直接 skip（安全，不重复调用）。
- 真实 invocation、checkpoint 尚未成功、crash → 只有 `STARTED` → 该 attempt 已消耗 → resume `attempt2`。

中间 attempt（`attempt1 RETRYABLE`）仍只写 `COMPLETED(1, RETRYABLE)`，不写 checkpoint（它尚未形成 canonical final status）。这同时保证：**不重复调用 + 最大 2 attempts + 已完成 canonical observation 尽可能可恢复**。

---

## 5. Recovery state machine

恢复时对**未完成** canonical unit 的 attempt 重建（[RtmpFormalExperimentRunner.buildAttemptRecoveryMap](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java)）：

| ledger 状态（该 unit） | consumed | 语义 | nextAttempt |
|---|---|---|---|
| 无任何事件 | 0 | fresh | 1 |
| `STARTED(1)`（无 `COMPLETED`） | 1 | attempt1 已消耗（crash mid-attempt1） | 2 |
| `STARTED(1)` + `COMPLETED(1, RETRYABLE)` | 1 | attempt1 已消耗且需要 retry | 2 |
| `STARTED(1)` + `COMPLETED(1, VALID/INVALID)` | 1 | attempt1 terminal 但 checkpoint 丢失 → 抛异常（拒绝 fabrication） | — |
| `STARTED(2)`（任意） | 2 | 预算已耗尽 → 抛异常 | — |

恢复主流程：

```text
1. detectRecoveryState（canonical checkpoint，判断 completed units）
2. load attempt ledger（load 内做单事件结构校验，非法即抛异常）
3. validate ledger（identity + 跨事件 fail-closed 校验，非空错误即拒绝启动）
4. buildAttemptRecoveryMap（对每个未完成 unit 计算 nextAttempt）
5. 遍历 plan：
   - completed unit → skip（用 checkpoint record）
   - 未完成 unit → executeUnitRecorded(nextAttempt)
6. 每次真实 invocation：append STARTED(attempt) → execute → buildRecord
   → append checkpoint（最终 attempt）→ append COMPLETED(attempt, status)
```

`nextAttempt == 2` 的恢复执行会先清理同一 `memoryId`（与正常 retry 语义一致），随后只执行 `attempt2`，绝不回头执行 `attempt1`，也绝不 `attempt3`。

---

## 6. 不变量（Invariants）

- 一个 canonical run 在任何 crash/recovery 场景下最多 2 次真实 invocation。
- 最终 canonical attempt 的 `checkpoint` 先于 `COMPLETED` 落盘（消除丢失窗口）。
- 不新增 `RunStatus` 枚举值（仍为 `VALID / RETRYABLE_FAILURE / INVALID_RUN`）。
- 不改变 `runId` / `memoryId`（`memoryId == runId` 不变）。
- retry attempt 不产生新的 statistical unit。
- canonical final record（checkpoint 的 `record` 字段）继续保持 Raw 的唯一正式事实源。
- attempt ledger 是 recovery provenance artifact，不进入统计输入。
- recovery journal **fail-closed**：任何结构非法 / identity 不符 / 跨事件不一致的 ledger 都会使 formal runner 拒绝启动，绝不静默忽略。

---

## 7. Code changes

| 文件 | 变更 |
|---|---|
| [RtmpAttemptLedgerEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerEvent.java) | 新增 record：`STARTED` / `COMPLETED + status` 事件工厂 |
| [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java) | 新增 JSONL append + fsync、`load`（含单事件结构校验）、`consumedAttempts`、`lastCompletedStatus`、`validate`（identity + 跨事件 fail-closed） |
| [RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java) | `run()` 接入 ledger `load` + `validate` + `buildAttemptRecoveryMap`；新增 `executeUnitRecorded`（checkpoint-before-COMPLETED 写序）、`appendStarted` / `appendCompleted` / `appendCheckpoint`；删除旧的 `runWithRetryRecorded` / `executeAttemptRecorded` / `executeUnitDetailedRecorded`；原 `runWithRetry` / `executeUnitDetailed` 保持不变（纯逻辑路径） |

---

## 8. Tests

### 8.1 A–F（retry attempt crash consistency）

新增 [RtmpRetryAttemptCrashConsistencyTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpRetryAttemptCrashConsistencyTest.java)（6 个测试方法，全部不调用 Real LLM）：

| # | 测试 | 断言 |
|---|---|---|
| A | attempt1 RETRYABLE → crash → resume | `nextAttempt==2`；resume 只执行 attempt2（`calls==1`）；`attempts==2`；final VALID |
| B | attempt1 STARTED → crash → resume → attempt2 VALID | `attempts==2`；final VALID |
| C | attempt1 STARTED → crash → resume → attempt2 RETRYABLE | `attempts==2`；final RETRYABLE_FAILURE；无 attempt3（`calls==1`） |
| D | attempt1 STARTED → crash → resume → attempt2 INVALID_RUN | `attempts==2`；final INVALID_RUN；无 attempt3 |
| E | same runId / memoryId throughout | final record `runId == unit.runId == unit.memoryId`；4 条 ledger 事件全程同一 runId |
| F | canonical record count exactly one per unit | crash+resume 后 raw 仍 378 条；unit0 真实调用 1 次；STARTED attempts == `{1,2}`（无重复 attempt1）；raw 中 unit0 恰好 1 条 |

### 8.2 Corruption validation（fail-closed）

新增 [RtmpAttemptLedgerValidationTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerValidationTest.java)（15 个测试方法，全部不调用 Real LLM），分两层覆盖 recovery journal fail-closed：

**Layer 1 — 结构校验（`load()` 抛异常）**

| 测试 | 场景 |
|---|---|
| invalidEventType | eventType ∉ `{STARTED, COMPLETED}` → reject |
| attemptOutOfRange | attempt ∉ `{1,2}` → reject |
| startedWithStatus | `STARTED` 携带 status → reject |
| completedWithoutTerminalStatus | `COMPLETED` 无合法 terminal status → reject |
| wrongSchemaVersion | schema version 不匹配 → reject |

**Layer 2 — identity + 跨事件校验（`validate()` 返回非空错误）**

| 测试 | 场景 |
|---|---|
| wrongExperimentId | experimentId 不匹配 → reject |
| unknownRunId | runId 不在 plan → reject |
| wrongCaseId / wrongCondition / wrongRepetition | identity 字段与 plan 不符 → reject |
| duplicateStarted | `STARTED(1), STARTED(1)` → reject |
| started2WithoutStarted1 | `STARTED(2)` 缺 `STARTED(1)` → reject |
| completed1WithoutStarted1 | `COMPLETED(1, VALID)` 缺 `STARTED(1)` → reject |
| completed2WithoutStarted2 | `COMPLETED(2, VALID)` 缺 `STARTED(2)` → reject |

**Positive controls**

| 测试 | 场景 |
|---|---|
| validLedger | `STARTED(1)+COMPLETED(1,RETRYABLE)+STARTED(2)+COMPLETED(2,VALID)` → accept |
| startedOnly | `STARTED(1)` 未完成（合法中间态）→ accept |

这些测试不是为了「把测试数量做大」，而是为了保证 recovery journal 对坏状态 **fail-closed**：任何不完整、未知、或结构非法的 ledger 都不能让 formal runner 静默继续。

---

## 9. mvn test result



`mvn test-compile` 已通过（0 编译错误）。全量 `mvn test` 的最终结果由用户运行后回填此处。
Tests run: 436, Failures: 0, Errors: 0, Skipped: 7
---

## 10. Final Decision

R6.1 的代码实现与测试已就绪，完成 gate 全部满足：

- ✅ 为 canonical run 建立 crash-safe attempt execution provenance
- ✅ attempt 开始前持久化 `STARTED`
- ✅ attempt 完成后持久化 `COMPLETED + status`
- ✅ canonical final completion 单独记录（checkpoint 不变）
- ✅ 最终 canonical attempt 的 `checkpoint` 先于 `COMPLETED` 落盘（消除丢失窗口）
- ✅ recovery journal fail-closed（结构 + identity + 跨事件校验）
- ✅ 恢复：已 completed unit → skip
- ✅ 恢复：STARTED 无 COMPLETED 的 attempt → 已消耗
- ✅ 恢复：只执行一次 remaining attempt
- ✅ 任何 crash/recovery 场景下 canonical run 最多 2 次真实 invocation
- ✅ 不新增 `RunStatus` 枚举值
- ✅ 不改变 `runId` / `memoryId`
- ✅ retry attempt 不产生新的 statistical unit
- ✅ checkpoint final record 仍是 Raw 唯一正式事实源
- ✅ 测试 A–F + corruption validation tests 已编写
- ✅ `mvn test-compile` 通过；全量 `mvn test` 
Tests run: 436, Failures: 0, Errors: 0, Skipped: 7
- ✅ 未运行 Real LLM / Pilot / 378 formal runs
- ✅ R6.1 report generated

**裁定：R6.1 COMPLETE —— 「任何 process interruption / recovery path 下，一个 canonical run 最多 2 次真实 invocation」得证，且「已完成 canonical observation 尽可能可恢复」「recovery journal fail-closed」收口完成，R6 COMPLETE 维持成立。**
