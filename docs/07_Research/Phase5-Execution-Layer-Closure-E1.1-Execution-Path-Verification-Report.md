# Phase 5 — E1.1 Execution Path Verification Closure

> 阶段性质：只补齐 E1 最后 5 个执行层验收点。不重新设计 RTMP，不修改任何冻结研究语义。
> 本阶段不运行 Real LLM / Pilot / 378 formal runs。

---

## 0. 5 项问题与处理结论

| # | 问题 | 结论 |
|---|---|---|
| 1 | Retry memory fallback 需收紧（无 ChatMemoryStore 不得执行 retry） | 已收紧：formal preflight 新增 ChatMemoryStore gate，缺失即拒绝启动 |
| 2 | Adapter effective model 需证明（config → holder → adapter → body） | 已证明：新增不联网测试，断言 model=qwen-max / temperature=0.1 / top_p=0.9 / 无 seed / 无 max_tokens |
| 3 | Runner actual ordering 需证明（不只测 RtmpConditionOrder） | 已证明：stub `runRtmpCaseOutcome` 记录 invocation 顺序，验证 frozen rotation |
| 4 | Runner actual retry 需证明（不只测 RtmpRunRetryPolicy） | 已证明：stub `runRtmpCaseOutcome` 记录 invocation 次数与最终 status |
| 5 | Canonical formal command 需从真实代码确认 | 已确认（见 §4） |

---

## 1. 修改内容

### 1.1 `RtmpFormalExperimentRunner.java`（[源码](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java)）

- `preflight(...)` 新增 `ChatMemoryStore memoryStore` 参数；`memoryStore == null` 时写入 error 并返回 `valid=false`（#1）。
- 新增 package-private `defaultSource()`：生产默认 outcome source = `benchmarkRunner.runRtmpCaseOutcome(...).block()`，`run()` 与测试复用同一路径（#3/#4）。
- 新增 package-private `executePlan(plan, dataset, config, source)` 与 `executeUnit(unit, testCase, config, source)`，使 runner 的 order/retry 可经 stub 直接验证（#3/#4）。
- 未改变：`memoryId == runId`；未创建 `runId_retry` / `memoryId_retry`。retry 前仍清理同一 `memoryId`（`runWithRetry` 保持原逻辑）。

### 1.2 `RtmpFormalExperimentEntryPoint.java`（[源码](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentEntryPoint.java)）

- 提取 `ChatMemoryStore store = memoryStore.getIfAvailable()`，并将其传入 `preflight(...)`。`store == null` 时 preflight 失败，entry point 记录 error 并返回，不调用 Real LLM。

### 1.3 `DashScopeChatAdapter.java`（[源码](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java)）

- 仅将 `buildRequestBody(...)` 由 `private` 改为 package-private，供同包测试访问；**未改任何请求体构建逻辑**。`effectiveModel = config.llmProvider()`、`temperature`/`top_p`/`max_tokens`/`seed` 行为与 E1 完全一致。

### 1.4 新增测试

- [DashScopeChatAdapterEffectiveModelTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapterEffectiveModelTest.java)（#2）
- [RtmpExecutionPathVerificationTest.java](file:///d:/A_big/ShopMind/backend/src/test/java/com/shopmind/evaluation/rtmp/formal/RtmpExecutionPathVerificationTest.java)（#1/#3/#4）

---

## 2. 测试结果（真值表）

### #1 Retry memory fallback

| 用例 | 断言（预期） |
|---|---|
| ChatMemoryStore 缺失 | `preflight(...).valid() == false`，errors 含 `ChatMemoryStore` |
| ChatMemoryStore 存在 | `preflight(...).valid() == true` |
| retry 前清理 | `runWithRetry` 在 RETRYABLE 后调用 `deleteMessages(memoryId)`，且 `memoryId == runId` |

### #2 Adapter effective model

| 字段 | 断言（预期） |
|---|---|
| `body["model"]` | `qwen-max`（覆盖默认 `qwen-plus`） |
| `body["parameters"]["temperature"]` | `0.1` |
| `body["parameters"]["top_p"]` | `0.9` |
| `seed` | 不存在 |
| `max_tokens` | 不存在 |

### #3 Runner actual ordering（同一 case）

| repetition | 预期 invocation 顺序 |
|---|---|
| 1 | `BASELINE_A → BASELINE_B → METHOD_C` |
| 2 | `BASELINE_B → METHOD_C → BASELINE_A` |
| 3 | `METHOD_C → BASELINE_A → BASELINE_B` |

### #4 Runner actual retry

| 用例 | 预期 invocation 次数 | 预期最终 status |
|---|---|---|
| attempt1 RETRYABLE + attempt2 VALID | 2 | VALID |
| attempt1 RETRYABLE + attempt2 RETRYABLE | 2 | RETRYABLE_FAILURE |
| attempt1 RETRYABLE + attempt2 INVALID_RUN | 2 | INVALID_RUN |
| 永不第 3 次 | ≤ 2 | — |

### 回归目标

`mvn test` → `0 failures / 0 errors`。

**实际回归结果（2026-08-25）**：

| 指标 | 值 |
|---|---|
| Tests run | 401 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 7（既有，非执行层回归） |
| BUILD | SUCCESS |

新增执行路径测试：`DashScopeChatAdapterEffectiveModelTest`（1 方法）+ `RtmpExecutionPathVerificationTest`（8 方法）全部通过。

---

## 3. Canonical Formal Command（§5）

以下命令基于真实代码逐项核对（`@ConditionalOnProperty` / `@Value` / `@Profile("qwen")` / `MongoChatMemoryStore`）：

**Canonical formal command（Maven 形式，主）**

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=qwen \
  -Dspring-boot.run.arguments="--shopmind.rtmp.formal.enabled=true --shopmind.rtmp.formal.experiment-id=RTMP-EXP01 --shopmind.rtmp.formal.output-dir=experiments"
```

**Alternative（jar 形式）**

```bash
java -jar target/shopmind-enterprise-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=qwen \
  --shopmind.rtmp.formal.enabled=true \
  --shopmind.rtmp.formal.experiment-id=RTMP-EXP01 \
  --shopmind.rtmp.formal.output-dir=experiments
```

**运行环境要求**

| 项 | 说明 | 来源 |
|---|---|---|
| `QWEN_API_KEY` | formal experiment 必需 | `shopmind.llm.qwen.api-key`（`@Value`） |
| ChatMemoryStore bean | formal preflight 必需（缺失即拒绝启动） | `MongoChatMemoryStore` `@Component` |
| `MONGODB_URI` | 可选覆盖；默认 `mongodb://localhost:27017/shopmind` | `spring.data.mongodb.uri`（`${MONGODB_URI:...}`） |

**命令契约（与真实代码一致）**

- 显式 opt-in：`--shopmind.rtmp.formal.enabled=true` 才会装配 `RtmpFormalExperimentEntryPoint`（`@ConditionalOnProperty(havingValue="true")`）。
- 指定 experimentId：`--shopmind.rtmp.formal.experiment-id`（默认 `RTMP-EXP01`）。
- 使用 qwen profile：`--spring.profiles.active=qwen` 才装配 `DashScopeChatAdapter`（`@Profile("qwen")`）。
- 普通启动（不传 `--shopmind.rtmp.formal.enabled=true`）**不会**误触发 formal experiment。
- **dry / preflight command：不存在独立命令**。preflight 是 entry point 内部固定第一步，`run()` 中先 `preflight(...)`，失败即返回、不调用 Real LLM。代码中没有任何 dry-only CLI flag。

---

## 4. Remaining Blockers

- **Protocol blocker**：无（RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / expectedToolSequence / Evaluator / 统计方法 / 42 cases / runtime fixture 均未改动）。
- **执行层 blocker**：无（#1–#5 全部关闭）。
- **Regression: VERIFIED** — `mvn test`：Tests run = 401，Failures = 0，Errors = 0，Skipped = 7，BUILD SUCCESS。
- **Limitation（非 blocker）**：adapter effective model 为 config-path 验证（不联网），不通过 Real LLM 做端到端 model 验证（符合 §2「不调用真实 Qwen」要求）。

---

## 5. Final Decision

代码与测试层面的 5 项验收点均已关闭，未触碰任何冻结研究语义，未运行 Real LLM / Pilot / 378 runs。

- ✅ #1 Retry memory fallback 收紧（preflight gate + 同一 memoryId 清理）
- ✅ #2 Adapter effective model proof（不联网）
- ✅ #3 Runner actual ordering（stub invocation 顺序）
- ✅ #4 Runner actual retry（stub invocation 次数 + 最终 status + 永不第 3 次）
- ✅ #5 Canonical formal command 确认并写入本报告
- ✅ `mvn test` 全绿（Tests run 401, Failures 0, Errors 0, Skipped 7, BUILD SUCCESS）

**裁定：`EXECUTION LAYER CLOSURE E1 COMPLETE`。**

按纪律，本阶段完成后立即停止，不自行启动 Real LLM / Pilot / 378 runs；下一阶段仅在 review 本报告并明确允许后进入。
