# Phase 5 — R6.1 — Retry Attempt Crash Consistency Closure

> 阶段性质：R6（Process Interruption Recovery & Checkpoint Integrity）的执行完整性补丁。
> 范围：只修复 R6 中「attempt-level retry accounting 在 process interruption 后可能重置」这一具体问题。
> 本阶段不运行 Real LLM / Pilot / 378 formal runs；不修改 RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / Evaluator / statistics / 42 cases / runtime fixture / `memoryId == runId` / RunStatus 三态。

---

## 0. Executive Summary

R6 只对 **canonical final completion** 做了 checkpoint，没有对 **attempt 级执行事实**做持久化。因此当 `attempt1 RETRYABLE` 之后、canonical checkpoint 落盘之前发生进程中断，resume 会从 `attempt1` 重新计数，导致同一个 canonical run 实际产生 **3 次真实 invocation**，违反 run-level `max retry = 1`。

R6.1 新增 crash-safe attempt ledger（`{experimentId}_attempt-ledger.jsonl`），在每次真实 invocation 前持久化 `STARTED`、完成后持久化 `COMPLETED + status`，并在恢复时据此重建「哪些 attempt 已被消耗」，只执行 remaining attempt。最终证明：**任何 process interruption / recovery 路径下，一个 canonical run 最多 2 次真实 invocation。**

核心结论：

- ✅ 新增 [RtmpAttemptLedgerEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerEvent.java)（STARTED / COMPLETED + status）
- ✅ 新增 [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java)（JSONL append + fsync，与 checkpoint 同语义）
- ✅ [RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java) 接入 attempt ledger 与恢复重建
- ✅ 恢复时：已 completed unit → skip；STARTED 无 COMPLETED 的 attempt → 已消耗；只执行 remaining attempt
- ✅ 不新增 `RunStatus` 枚举值；不改变 `runId` / `memoryId`
- ✅ retry attempt 不产生新的 statistical unit；canonical final record 仍是 Raw 唯一正式事实源
- ✅ 测试 A–F 全部通过；`mvn test` 全绿（420 / 0 / 0 / 7 skipped）

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
| [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java) | JSONL append + fsync；`consumedAttempts` / `lastCompletedStatus` 聚合 |

ledger 文件为 `{experimentId}_attempt-ledger.jsonl`，是 recovery provenance artifact，不是统计输入；canonical final Raw 仍由 checkpoint 承载，Raw 是唯一正式事实源。

---

## 4. Recovery state machine

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
2. load attempt ledger
3. buildAttemptRecoveryMap（对每个未完成 unit 计算 nextAttempt）
4. 遍历 plan：
   - completed unit → skip（用 checkpoint record）
   - 未完成 unit → executeUnitDetailedRecorded(nextAttempt)
5. 每次真实 invocation：append STARTED(attempt) → execute → append COMPLETED(attempt, status)
6. 完成 unit 后写 canonical checkpoint（仍为唯一 Raw 事实源）
```

`nextAttempt == 2` 的恢复执行会先清理同一 `memoryId`（与正常 retry 语义一致），随后只执行 `attempt2`，绝不回头执行 `attempt1`，也绝不 `attempt3`。

---

## 5. 不变量（Invariants）

- 一个 canonical run 在任何 crash/recovery 场景下最多 2 次真实 invocation。
- 不新增 `RunStatus` 枚举值（仍为 `VALID / RETRYABLE_FAILURE / INVALID_RUN`）。
- 不改变 `runId` / `memoryId`（`memoryId == runId` 不变）。
- retry attempt 不产生新的 statistical unit。
- canonical final record（checkpoint 的 `record` 字段）继续保持 Raw 的唯一正式事实源。
- attempt ledger 是 recovery provenance artifact，不进入统计输入。

---

## 6. Code changes

| 文件 | 变更 |
|---|---|
| [RtmpAttemptLedgerEvent.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerEvent.java) | 新增 record：`STARTED` / `COMPLETED + status` 事件工厂 |
| [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java) | 新增 JSONL append + fsync、`load`、`consumedAttempts`、`lastCompletedStatus` |
| [RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java) | `run()` 接入 ledger 加载与 `buildAttemptRecoveryMap`；新增 `runWithRetryRecorded` / `executeAttemptRecorded` / `executeUnitDetailedRecorded` / `buildAttemptRecoveryMap` / `buildRecord`；原 `runWithRetry` / `executeUnitDetailed` 保持不变（纯逻辑路径） |

---

## 7. Tests（A–F）

新增 [RtmpRetryAttemptCrashConsistencyTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpRetryAttemptCrashConsistencyTest.java)（6 个测试方法，全部不调用 Real LLM）：

| # | 测试 | 断言 |
|---|---|---|
| A | attempt1 RETRYABLE → crash → resume | `nextAttempt==2`；resume 只执行 attempt2（`calls==1`）；`attempts==2`；final VALID |
| B | attempt1 STARTED → crash → resume → attempt2 VALID | `attempts==2`；final VALID |
| C | attempt1 STARTED → crash → resume → attempt2 RETRYABLE | `attempts==2`；final RETRYABLE_FAILURE；无 attempt3（`calls==1`） |
| D | attempt1 STARTED → crash → resume → attempt2 INVALID_RUN | `attempts==2`；final INVALID_RUN；无 attempt3 |
| E | same runId / memoryId throughout | final record `runId == unit.runId == unit.memoryId`；4 条 ledger 事件全程同一 runId |
| F | canonical record count exactly one per unit | crash+resume 后 raw 仍 378 条；unit0 真实调用 1 次；STARTED attempts == `{1,2}`（无重复 attempt1）；raw 中 unit0 恰好 1 条 |

测试 A–D 通过 stub `OutcomeSource` 记录真实 invocation 次数，直接证明「resume 只执行 remaining attempt，不重跑已消耗的 attempt」。测试 F 通过 full-run（stub `BenchmarkRunner`）证明 attempt ledger 未出现重复 attempt1，且 final raw 每个 unit 恰好一条 canonical record。

---

## 8. mvn test result

```text
Tests run: 420, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

（R6 阶段为 414，新增 R6.1 测试 A–F 后为 420；7 skipped 保持不变。）

全量测试以 stub / fake 驱动，无真实 Qwen 调用；未运行 Real LLM / Pilot / 378 formal runs。

---

## 9. Final Decision

R6.1 的代码实现与测试已就绪，完成 gate 全部满足：

- ✅ 为 canonical run 建立 crash-safe attempt execution provenance
- ✅ attempt 开始前持久化 `STARTED`
- ✅ attempt 完成后持久化 `COMPLETED + status`
- ✅ canonical final completion 单独记录（checkpoint 不变）
- ✅ 恢复：已 completed unit → skip
- ✅ 恢复：STARTED 无 COMPLETED 的 attempt → 已消耗
- ✅ 恢复：只执行一次 remaining attempt
- ✅ 任何 crash/recovery 场景下 canonical run 最多 2 次真实 invocation
- ✅ 不新增 `RunStatus` 枚举值
- ✅ 不改变 `runId` / `memoryId`
- ✅ retry attempt 不产生新的 statistical unit
- ✅ checkpoint final record 仍是 Raw 唯一正式事实源
- ✅ 测试 A–F 已编写并通过
- ✅ `mvn test` 全绿（420 / 0 / 0 / 7 skipped）
- ✅ 未运行 Real LLM / Pilot / 378 formal runs
- ✅ R6.1 report generated

**裁定：R6.1 COMPLETE —— 「任何 process interruption / recovery path 下，一个 canonical run 最多 2 次真实 invocation」得证，R6 COMPLETE 维持成立。**
