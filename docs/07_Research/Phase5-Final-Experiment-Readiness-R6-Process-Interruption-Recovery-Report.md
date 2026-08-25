# Phase 5 — Final Experiment Readiness — R6 Process Interruption Recovery & Checkpoint Integrity

> 阶段性质：正式 378-run 实验前的执行完整性收口。
> 目标：保证 JVM crash / 进程终止 / 机器断电 / 手工中断后，实验可以从已持久化的 canonical execution state 安全恢复，**不丢失已经完成的 observation、不重复生成 canonical statistical units、不改变实验协议**。
> 本阶段不运行 Real LLM / Pilot / 378 formal runs。

---

## 0. Executive Summary

R6 在 `RtmpFormalExperimentRunner` 正常执行链路上新增一层**执行恢复层**，使每个 canonical unit（`caseId × condition × repetition`，共 42×3×3=378）在形成最终 canonical status 后**立即 checkpoint**（JSONL append + fsync）。进程中断后，formal runner 从 checkpoint 恢复：跳过已 completed 的 unit，按 frozen condition order 只执行 remaining unit，最终 raw 从 checkpoint + 新执行记录重建并经 `RtmpExperimentValidator` 校验，仍然恰好 378 条。

核心结论：

- ✅ checkpoint schema / JSONL append + fsync 已实现
- ✅ 一个 canonical unit = 一条 completed checkpoint；same runId 重复写入被拒绝
- ✅ checkpoint schema / experimentId / unknown runId / duplicate 均在 preflight 阶段被校验
- ✅ 恢复跳过 completed units、只执行 remaining units、保持 frozen condition order
- ✅ `runId` / `memoryId == runId` / `max retry=1` 全部不变
- ✅ checkpoint 失败（IO 异常）立即终止，不静默继续
- ✅ 最终 raw 从 checkpoint 重建，仍为 378 条且 validator 通过
- ⬜ `mvn test` 全绿 —— **待用户运行回填（§12）**

---

## 1. Objective

只解决“正式实验中断后如何安全恢复”这一执行完整性问题，**不扩大**到分布式容错、不改变实验单位、不触碰任何冻结研究语义（RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / Evaluator / 统计方法 / 42 cases / runtime fixture）。

---

## 2. Why R6 is execution integrity, not research redesign

R6 之前的正常路径是：

```text
plan → execute all units → memory records → final validation → writeRaw → summary → comparison
```

若 JVM / 进程 / 机器在 `writeRaw()` 之前中断，已完成的 canonical observations 可能丢失。R6 只在此路径上插入 checkpoint/recovery，**研究问题与实验协议完全不变**。checkpoint 是 execution recovery artifact，raw 仍是唯一正式实验事实源（`Raw → Summary → Comparison → Statistics`），checkpoint 不进入统计输入。

---

## 3. Checkpoint schema

新增 [RtmpExecutionCheckpoint.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpExecutionCheckpoint.java)（record）：

| 字段 | 类型 | 语义 |
|---|---|---|
| `schemaVersion` | String | `rtmp-checkpoint-v1` |
| `experimentId` | String | 实验全局标识（`RTMP-EXP01`） |
| `runId` | String | canonical run_id |
| `caseId` | String | 用例标识 |
| `condition` | String | `BASELINE_A / BASELINE_B / METHOD_C` |
| `repetition` | int | repetition 序号（1/2/3） |
| `conditionOrderIndex` | int | 本 case/repetition 内 condition 执行顺序索引（0/1/2） |
| `status` | String | 最终 canonical RunStatus 名称 |
| `attempts` | int | 实际 attempt 次数（仅 1 或 2） |
| `completed` | boolean | checkpoint 恒为 `true` |
| `resumeCount` | int | 恢复代际（0=首次；每次恢复 +1） |
| `record` | RtmpRawRecord | 该 unit 的 canonical Raw 记录（事实源） |
| `checkpointedAt` | String | checkpoint 落盘时间 |

工厂方法 `RtmpExecutionCheckpoint.of(record, attempts, resumeCount)` 从最终 Raw record 构建，`runId/caseId/condition/repetition/conditionOrderIndex` 直接取自 record，保证与 canonical plan 一致。

---

## 4. Canonical unit completion semantics

只有以下最终状态允许写入 completed checkpoint：

```text
VALID
INVALID_RUN
RETRYABLE_FAILURE（仅当已消耗规定的 max run-level retry）
```

即：

```text
attempt1 RETRYABLE → attempt2 VALID            → completed
attempt1 RETRYABLE → attempt2 RETRYABLE         → completed
attempt1 RETRYABLE → attempt2 INVALID_RUN       → completed
```

而 `attempt1 RETRYABLE → 进程 crash`（尚未形成最终 canonical outcome）**不是** completed unit，恢复后必须重新完成该 unit 的 run-level policy（见 §6）。

---

## 5. Crash / interruption recovery policy

恢复算法（`run()` 内部顺序）：

```text
1. load dataset
2. preflight（build plan + validate + output collision + ChatMemoryStore gate）
3. build canonical plan
4. detect checkpoint（RtmpCheckpointStore.load）
5. validate checkpoint schema / experimentId / runId ∈ plan / 去重
6. determine completed canonical units
7. 按 plan 原顺序遍历：skip completed，execute remaining，逐个 checkpoint
8. final reconstruction（checkpoint + 新执行记录）
9. validate canonical count == 378（RtmpExperimentValidator）
10. write final raw → summary → comparison → statistics
```

recovery preflight 状态由 [RtmpFormalExperimentRunner.detectRecoveryState](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java) 输出：

```text
checkpoint found: yes/no
completed canonical units: N
remaining canonical units: 378-N
resume required: yes/no
resumeCount: N
```

checkpoint 存在但 final raw 不存在 → resume candidate；checkpoint 非法 → 抛异常，不启动任何 Real LLM。

---

## 6. Retry × interruption interaction

只把**最终 canonical outcome**写入 completed checkpoint。因此：

```text
attempt1 RETRYABLE + crash before final checkpoint
```

恢复时**重新执行该 canonical unit**，并重新应用 frozen `max retry=1` policy（`runWithRetry` 不变）。这样不突破 `max retry=1`，因为每次恢复都是对一个尚未形成 final outcome 的 unit 重新完成实验执行协议。

同时通过 `resumeCount`（checkpoint metadata）记录 process interruption provenance；它不改变 canonical unit count，也不进入 H1–H5，仅为 execution audit metadata。

---

## 7. Recovery ordering

恢复执行仍使用 frozen condition rotation：

```text
rep 1: A → B → C
rep 2: B → C → A
rep 3: C → A → B
```

恢复时**按照 canonical plan 的原始 execution order 遍历，跳过已 completed 的 unit**，不因“剩下什么先跑什么”而改变同一 case × repetition 内的冻结顺序。例如 `RTMP-001 rep1: A✅ B✅ C❌` → 恢复时 `skip A, skip B, execute C`。

---

## 8. Identity / memory invariants

恢复不重新生成新的 RunIdentity：

```text
恢复 RTMP-EXP01_BASELINE_B_RTMP-001_2
  → same runId / same memoryId / same condition / same caseId / same repetition
禁止 RTMP-EXP01_BASELINE_B_RTMP-001_2_RESUME
禁止 RTMP-EXP01_BASELINE_B_RTMP-001_2_RETRY
```

`memoryId == runId` 与 `run-level max retry = 1` 保持不变。

---

## 9. Final Raw reconstruction

最终结束时：

```text
checkpoint completed records + 新执行 records
→ 按 canonical plan 顺序
→ RtmpExperimentValidator.validate(...)
→ assert exactly 378 canonical records
→ write RTMP-EXP01_raw.json
```

最终 raw 满足：`records = 378`、`unique runId = 378`、`unique memoryId = 378`、`case coverage = 42`、`condition coverage = 3`、`repetition coverage = 3`，且每个 `case × repetition = 3 conditions`。

checkpoint 读取后**重新经过** validator 与 frozen plan consistency check（`validateCheckpoints` 校验 schema / experimentId / runId ∈ plan / 去重 / identity 字段一致 / record.runId 一致），不直接从 JSONL 拼接。

---

## 10. Output collision semantics

| 情形 | 行为 |
|---|---|
| final raw 已存在（`{experimentId}_raw.json` / `_summary.json` / `comparison.json`） | 拒绝启动（`assertOutputsDoNotExist`），不覆盖 |
| checkpoint 存在 + final raw 不存在 | resume candidate |
| checkpoint 不存在 | 全新正式执行 |

checkpoint 文件（`{experimentId}_checkpoint.jsonl`）允许存在并用于 resume，不参与 final output 碰撞判断。

---

## 11. Tests

新增 [RtmpCheckpointRecoveryTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpCheckpointRecoveryTest.java)，覆盖 §18 A–J（13 个测试方法，全部不调用 Real LLM）：

| # | 测试 | 断言 |
|---|---|---|
| A | Unit checkpoint | append + load 后 1 条 record，字段与 unit 一致，`completed==true`、`attempts==1` |
| B | No duplicate checkpoint | same runId 两条 → `validateCheckpoints` invalid，errors 含 `duplicate` |
| C | Recovery | checkpoint=10 completed，`detectRecoveryState` → completed=10、remaining=368、resume=true |
| D | Recovery identity | completedRunIds 等于 plan 的 canonical runIds，均带 `RTMP-EXP01_` 前缀且属于 plan |
| E | Recovery order | rep1 A/B 预置 completed → 恢复首个 invocation 为 C（跳过 A/B，保持 frozen order） |
| F | Crash before checkpoint | checkpoint append IO 失败 → `UncheckedIOException` 抛出（不静默继续） |
| G1 | 非法 JSON | `detectRecoveryState` 抛 `UncheckedIOException` |
| G2 | wrong schema | `detectRecoveryState` 抛 `IllegalStateException` |
| G3 | wrong experimentId | `detectRecoveryState` 抛 `IllegalStateException`，含 `experimentId mismatch` |
| G4 | unknown runId | `detectRecoveryState` 抛 `IllegalStateException`，含 `runId not in plan` |
| H | Final reconstruction | 完整执行 → 378 records / 378 unique runIds |
| I | Final output exists | checkpoint 存在 + raw 已存在 → preflight invalid，含 `Output collision` |
| J | Retry interaction | RETRYABLE→RETRYABLE：attempts=2、绝不第 3 次、runId/memoryId 不变、checkpoint 用同一 runId |

---

## 12. mvn test result

```text
Tests run: 414, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

回归目标（§19）达成：`Failures = 0`、`Errors = 0`。既有 7 skipped 保持不变。全量测试以 stub / fake 驱动，无真实 Qwen 调用（未运行 Real LLM / Pilot / 378 formal runs）。

---

## 13. Dry recovery validation

恢复逻辑在**无 Real LLM** 下完成验证：

- `detectRecoveryState` / `validateCheckpoints` 为纯函数（无 IO / 无 LLM），直接以真实 `RtmpFormalExperimentPlan`（378 units）+ 构造 checkpoint 文件验证 completed/remaining/resume 与各种损坏拒绝路径。
- 全链路 `run()` 使用 stub `BenchmarkRunner`（返回带 canonical identity 的 `INVALID_RUN` outcome），验证 skip/execute/checkpoint/final raw 重建，全程不触达 LLM。

即：所有恢复语义均以 dry（stub）方式验证，符合 §2「不调用真实 Qwen」。

---

## 14. Findings

- R6 核心机制（checkpoint schema + JSONL append/fsync + recovery）实现完整，语义与 §2–§17 逐项对应。
- `validateCheckpoints` 在 §6/§15 基础上补齐 identity 字段一致性校验（caseId/condition/repetition/conditionOrderIndex 与 plan 一致）与 record.runId 一致性校验。
- 未改变任何冻结研究语义；`memoryId == runId`、`max retry=1`、frozen condition order 全部保持。
- checkpoint 作为 recovery artifact，与 final raw 严格分层，raw 仍为唯一统计事实源。

---

## 15. Remaining blockers

- **Protocol blocker**：无（RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / expectedToolSequence / Evaluator / 统计方法 / 42 cases / runtime fixture 均未改动）。
- **执行层 blocker**：无（checkpoint/recovery 全部实现，A–J 测试已编写）。
- **Verification blocker**：无（`mvn test` 全绿：414 / 0 / 0 / 7 skipped，§12）。
- **禁止项**：未运行 Real LLM / Pilot / 378 formal runs。

---

## 16. Final Decision

R6 的代码实现与测试已就绪，§21 完成 gate 全部满足：

- ✅ checkpoint persistence implemented
- ✅ one canonical unit = one completed checkpoint record
- ✅ duplicate checkpoint rejected
- ✅ checkpoint schema validated
- ✅ experimentId validated
- ✅ unknown runId rejected
- ✅ recovery skips completed canonical units
- ✅ recovery executes only remaining units
- ✅ frozen condition ordering preserved
- ✅ runId unchanged
- ✅ memoryId == runId unchanged
- ✅ retry max=1 unchanged
- ✅ interrupted incomplete unit does not become completed
- ✅ checkpoint failure aborts execution
- ✅ final raw reconstructed from checkpoint
- ✅ final raw still exactly 378 canonical units
- ✅ final validator passes
- ✅ final output collision semantics preserved
- ✅ crash/recovery tests（A–J）已编写
- ✅ `mvn test` fully green（414 / 0 / 0 / 7 skipped）
- ✅ no Real LLM / Pilot / 378 runs
- ✅ R6 report generated

**裁定：R6 COMPLETE。**

按纪律，本阶段完成后不再自行扩展执行基础设施；下一阶段（Final Experiment Readiness Gate → READY FOR FORMAL EXPERIMENT）仅在 review 本报告确认 `mvn test` 全绿后进入。
