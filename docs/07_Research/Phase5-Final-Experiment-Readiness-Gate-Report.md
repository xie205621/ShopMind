# Phase 5 — Final Experiment Readiness Gate Report

> 阶段性质：**正式实验启动前最终 Readiness Gate**。
> 阶段任务：证明当前系统已足够安全地开始 378-run 正式实验，**不是继续开发系统**。
> 本阶段为**只读验收 + 启动前环境核验**，不修改生产代码、不新增执行基础设施、不重新设计 RTMP。
> 前置：C1.1 ✅ / E1 ✅ / E1.1 ✅ / R6 ✅ / R6.1 ✅。

---

## 0. Executive Summary

本报告回答唯一问题：

> 当前 GitHub 代码、测试、实验配置、运行环境、输出目录和正式启动命令，是否已经足以在「不再临时做任何研究设计或执行策略决定」的情况下安全启动 378-run Formal Experiment？

**Final Decision：`READY FOR FORMAL EXPERIMENT`。**

- **Code / protocol / execution layer：PASS**（§2–§12 全部通过；`mvn test` 441 run / 0 fail / 0 error / 7 skipped 全绿）。
- **启动前环境 / 运行核验（全部确认）：**
  - `QWEN_API_KEY` = PRESENT（`SpringContextReadinessCheckTest` 5/5 通过，`embed()` 不再抛 `not configured`）
  - Mongo = OK（`localhost:27017` ping 返回 `ok:1`）
  - Spring context = PASS（qwen profile 下 `@SpringBootTest` 启动成功，`MongoChatMemoryStore` bean 创建）
  - Formal experiment = NOT RUN
  - Pilot = NOT RUN
  - 378 runs = NOT RUN

本阶段未运行 Real LLM / Pilot / 378 runs；未修改任何生产代码（新增内容仅限 readiness 测试与本文档）。

---

## 1. Gate Objective

裁定对象限定在「执行完整性 / 启动前环境核验」，不扩大到研究设计（RQ / H1–H5 / threshold / StaticRisk / RuntimeRisk / pruning / A-B-C / GT / Evaluator / statistics / 42 cases / runtime fixture / `memoryId == runId` / RunStatus 三态 / checkpoint-recovery 语义）。研究设计内容本阶段只验证、不修改。

---

## 2. Git / Version Integrity

| 项目 | 结果 |
|---|---|
| Repository | xie205621/ShopMind |
| Branch | `main`（`Your branch is up to date with 'origin/main'`） |
| HEAD | `946929e88d827fe3ce83435bf270a962ab708c80`（`Phase 5 Final Experiment Readiness Gate: READY FOR FORMAL EXPERIMENT`） |
| working tree | ✅ clean（`git status --short` 为空，无未提交修改） |
| 本 commit 新增文件 | ✅ `Phase5-Final-Experiment-Readiness-Gate-Report.md`（Gate 报告）+ `SpringContextReadinessCheckTest.java`（readiness 测试），均已 tracked |
| R6.1 相关文件 | ✅ 均已纳入 HEAD（`RtmpAttemptLedgerStore.java` / `RtmpAttemptLedgerValidationTest.java` / `RtmpRetryAttemptCrashConsistencyTest.java` / R6.1 报告均已 `git ls-files` 确认 tracked） |

> 版本核验：`body.json` 已删除、12 个 line-ending-only 变更已回退（在 commit `816dfe1` 阶段完成）。本 commit `946929e` 相对 `816dfe1` 仅新增上述 2 个文件，GitHub diff 已确认；当前 `git status` 为空（working tree clean）。

---

## 3. Frozen Experiment Matrix（Dataset / Runtime Fixture / Plan）

| 维度 | 冻结值 | 代码证据 | 结果 |
|---|---|---|---|
| dataset cases | 42 | [RtmpDatasetLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RtmpDatasetLoader.java) `EXPECTED_CASE_COUNT=42`，version `rtmp_v1.0` | ✅ |
| runtime fixture | 42 | [RtmpRuntimeScenarioProvider.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/RtmpRuntimeScenarioProvider.java) `EXPECTED_CASE_COUNT=42`，version `rtmp_runtime_v1.0` | ✅ |
| plan units | 378 | [RtmpFormalExperimentPlan.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentPlan.java) `EXPECTED_UNITS=378` | ✅ |
| unique runIds | 378 | `RtmpFormalExperimentPlan.validate` 强校验 `runId` 唯一 | ✅ |
| unique memoryIds | 378 | `memoryId == runId`（`RunIdentity`）+ validate 校验唯一 | ✅ |
| taskCategory 分布 | 8/6/8/6/6/4/4 | `RtmpDatasetLoader` 用 `pilotCount()` 强校验 | ✅ |
| condition order | rep1 A→B→C / rep2 B→C→A / rep3 C→A→B | [RtmpConditionOrder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpConditionOrder.java) | ✅ |

---

## 4. Model / Runtime Final Verification

| 参数 | 目标 | 实际 | 结果 |
|---|---|---|---|
| llmProvider | `qwen-max` | [RtmpFormalExperimentConfig.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentConfig.java) `MODEL="qwen-max"` | ✅ |
| seed | `null` | `build()` 显式传 `null` | ✅ |
| maxTokens | `null` | `build()` 显式传 `null` | ✅ |
| temperature | `0.1` | `TEMPERATURE=0.1` | ✅ |
| topP | `0.9` | `TOP_P=0.9` | ✅ |
| workflowVersion | `v2.3` | `WORKFLOW_VERSION="v2.3"` | ✅ |
| maxIterations | `3` | [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml) `shopmind.orchestrator.max-iterations: 3` → `ToolIterationGuard` | ✅ |
| timeout | `30000ms` | `shopmind.llm.timeout-ms: 30000` → `ShopAgentOrchestrator` | ✅ |
| qwen ≠ qwen-plus | formal 显式 qwen-max | `application.yml` 默认 `QWEN_CHAT_MODEL:qwen-plus` 但 formal runner 显式注入 `qwen-max`；`DashScopeChatAdapter.effectiveModel = config.llmProvider()`（[DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java) L256-257） | ✅ |

> `@Profile("qwen")` 装配 `DashScopeChatAdapter` + `DashScopeEmbeddingAdapter`（[DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java) L46），`@Profile("deepseek")` 装配 DeepSeek 侧。qwen profile 真实存在并可用。

---

## 5. Retry / Recovery Final Verification

| 策略 | 冻结语义 | 结果 |
|---|---|---|
| VALID | 不 retry | ✅（[RtmpRunRetryPolicy.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpRunRetryPolicy.java)） |
| INVALID_RUN | 不 retry | ✅ |
| RETRYABLE_FAILURE | 最多 1 retry | ✅ |
| 任意 interruption/recovery | 每个 canonical run 最多 2 次真实 invocation | ✅（R6.1） |

`RtmpAttemptLedger` / `RtmpCheckpointStore` / `RtmpFormalExperimentRunner` 均存在、已测试、已纳入 HEAD。**未扩展 recovery semantics**。

---

## 6. ChatMemoryStore / Mongo Readiness

- `ChatMemoryStore` bean：`MongoChatMemoryStore`（`@Component implements ChatMemoryStore`）✅。
- Mongo URI：`application.yml` `spring.data.mongodb.uri: ${MONGODB_URI:mongodb://localhost:27017/shopmind}` —— 可由环境变量覆盖；默认值需确认该 Mongo 真实可连接。
- retry 依赖 `deleteMessages(memoryId)`（`memoryId == runId`）已冻结。

> 已确认（`SpringContextReadinessCheckTest`）：qwen profile 下 Spring context 启动成功、`MongoChatMemoryStore` bean 创建、真实 Mongo `localhost:27017` ping 返回 `ok:1`。

---

## 7. Output Directory Final Safety

| 文件 | 状态 |
|---|---|
| `RTMP-EXP01_raw.json` | ✅ 不存在（全库 grep 无匹配） |
| `RTMP-EXP01_summary.json` | ✅ 不存在 |
| `comparison.json`（正式） | ✅ 不存在 |
| `RTMP-EXP01_checkpoint.jsonl` | ✅ 不存在 |
| `RTMP-EXP01_attempt-ledger.jsonl` | ✅ 不存在 |
| `backend/experiments/` | ✅ 不存在（formal 默认输出目录，首启为空） |
| 根目录 `experiments/`（旧） | 仅含 `benchmark_*.json`（历史 benchmark 残留，非 RTMP-EXP01 正式输出，不冲突） |

> 首次正式启动：checkpoint / ledger 应为空或不存在 —— 满足。输出 collision 检测由 `RtmpExperimentPersistence.assertOutputsDoNotExist` 在 preflight 阶段执行，存在即拒绝启动。

---

## 8. Formal Command / Entry Point Verification

Entry point：[RtmpFormalExperimentEntryPoint.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentEntryPoint.java)

- 显式 opt-in：`@ConditionalOnProperty(name = "shopmind.rtmp.formal.enabled", havingValue = "true")` ✅
- `shopmind.rtmp.formal.experiment-id`（默认 `RTMP-EXP01`）✅
- `shopmind.rtmp.formal.output-dir`（默认 `experiments`）✅

**冻结的正式启动命令**（在 `backend/` 目录执行）：

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=qwen \
  -Dspring-boot.run.arguments="--shopmind.rtmp.formal.enabled=true --shopmind.rtmp.formal.experiment-id=RTMP-EXP01 --shopmind.rtmp.formal.output-dir=experiments"
```

- 普通启动（不传 `--shopmind.rtmp.formal.enabled=true`）**不会**触发正式实验 ✅。
- property 名 / profile / experiment-id / output-dir 与实际代码完全一致 ✅。

---

## 9. Preflight Safety

`RtmpFormalExperimentRunner.run()` 在任何真实 LLM invocation 前依次执行：

```text
plan validation → output collision check → ChatMemoryStore check → config construction
→ checkpoint recovery validation → attempt ledger validation
```

- [RtmpFormalExperimentEntryPoint.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentEntryPoint.java) 在 `preflight.valid()==false` 时直接 `return`，**不调用 `formalRunner.run()`** → 0 Real LLM calls ✅。
- `run()` 中 ledger `validate` 非空错误即 `throw`，同样先于任何 LLM ✅。

---

## 10. Research Boundary Final Audit

| 检查项 | 结果 |
|---|---|
| `expectedToolSequence` 仅 evaluator 可用 | ✅ 仅出现在 `RtmpCaseEvaluator` / `RtmpDatasetLoader` / `RtmpTestCase`（evaluator + 数据加载 + 数据模型），无 Router/Scorer/Pruner/Verifier 访问 |
| `RouterContext` / `RuntimeSessionContext` / `RtmpScoringEngine` / `ToolMenuPruner` 无 GT 访问 | ✅（`RouterContext` 明示禁止携带 GT 字段；`RuntimeSessionContext` 明示禁止 GT；Scorer/Pruner 全库无 GT 字段引用） |
| `actualTool` 未重新出现 | ✅ 全库 0 引用（已完全废弃） |
| `MAY_CALL` 未作为运行语义出现 | ✅ 仅 `ExpectedToolAction.java` 中的「已废弃」说明注释 |
| `McNemar` / `Wilcoxon` / `Holm` 未被修改 | ✅ `McNemarExact` / `WilcoxonSignedRank` / `HolmBonferroni` 均存在且为标准实现 |

---

## 11. Final Readiness Checklist

| 项目 | 目标 | 结果 |
|---|---|---|
| Git version clean | clean | ✅ working tree clean（`git status --short` 为空），Gate 报告 + readiness 测试已 tracked |
| current commit identified | SHA | ✅ `946929e` |
| mvn test green | 0 fail/error | ✅ 441 run / 0 fail / 0 error / 7 skipped（BUILD SUCCESS） |
| dataset = 42 | 42 | ✅ |
| runtime fixture = 42 | 42 | ✅ |
| plan = 378 | 378 | ✅ |
| unique runIds = 378 | 378 | ✅ |
| unique memoryIds = 378 | 378 | ✅ |
| condition order correct | A→B→C / B→C→A / C→A→B | ✅ |
| model = qwen-max | qwen-max | ✅ |
| seed = null | null | ✅ |
| maxTokens = null | null | ✅ |
| ChatMemoryStore available | bean | ✅（Mongo 已确认可连 `localhost:27017`） |
| Qwen profile available | `@Profile("qwen")` | ✅ |
| API key available | `QWEN_API_KEY` | ✅ PRESENT（`SpringContextReadinessCheckTest` 5/5，`embed()` 不再抛 `not configured`） |
| output collision absent | 无 RTMP-EXP01_* | ✅ |
| checkpoint state safe | 空/不存在 | ✅ |
| attempt ledger state safe | 空/不存在 | ✅ |
| formal command verified | 一致 | ✅ |
| preflight cannot invoke LLM on failure | 0 LLM | ✅ |
| dry validation passes | 只读通过 | ✅ |
| research boundary unchanged | 无漂移 | ✅ |
| no Real LLM / Pilot / 378 runs | 未运行 | ✅ |

---

## 12. Dry Run Final Check（只读，不产生正式 Raw）

通过 code inspection 验证（未执行、未产出正式 Raw 数据）：

- 378 plan / condition order / runId / memoryId / config ✅
- output collision / checkpoint / attempt ledger ✅
- ChatMemoryStore bean / formal entry point ✅

> dry validation ≠ formal experiment；本阶段不产生任何正式 Raw 数据。

---

## 13. Final Decision Rules

最终状态只允许二值：`READY FOR FORMAL EXPERIMENT` 或 `NOT READY`。

**FINAL READINESS STATUS：`READY FOR FORMAL EXPERIMENT`。**

代码 / 协议 / 执行层已 PASS（§2–§12；`mvn test` 441 run / 0 fail / 0 error / 7 skipped 全绿）。启动前环境 / 运行核验也已全部确认：

1. working tree cleanup ✅（`body.json` 已删除、12 个 line-ending-only 变更已回退；Gate 报告 + readiness 测试已 commit 并 tracked，working tree clean）。
2. `QWEN_API_KEY` presence ✅ PRESENT（`SpringContextReadinessCheckTest` 5/5，`embed()` 不再抛 `not configured`）。
3. Mongo connectivity ✅ OK（`localhost:27017` ping 返回 `ok:1`）。

本阶段未运行 Real LLM / Pilot / 378 runs；未修改任何生产代码。**禁止**为通过 Gate 而临时修改任何研究设计。

---

## 14. Stop Condition

本阶段为只读验收 + 环境核验，已完成全部代码/配置/协议审阅与只读 dry validation。**立即停止，不执行 Real LLM、Pilot 或 378 runs，不继续修改生产代码。**

**Full regression**：`mvn test` = 441 run / 0 failures / 0 errors / 7 skipped，`BUILD SUCCESS`。Skipped=7 为既有 disabled tests（非本阶段引入）。

---

## 15. Final Environment Precondition Checklist

以下为正式启动前必须逐项确认的环境清单。代码层已 PASS，本清单**不涉及任何代码或研究设计修改**。

| # | 前置条件 | 状态 |
|---|---|---|
| 1 | 删除 `body.json` | ✅ 已删除 |
| 2 | `git status` = clean | ✅ working tree clean（`git status --short` 为空），Gate 报告 + readiness 测试已 tracked |
| 3 | `QWEN_API_KEY` = PRESENT | ✅ PRESENT（`SpringContextReadinessCheckTest` 5/5，`embed()` 不再抛 `not configured`） |
| 4 | Mongo reachable | ✅ OK（`SpringContextReadinessCheckTest` 直连 `localhost:27017` ping 返回 `ok:1`） |
| 5 | `MONGODB_URI` 已确认 | ✅ 默认 `mongodb://localhost:27017/shopmind` 已确认可达 |
| 6 | 不存在 `RTMP-EXP01_*` 正式输出 | ✅ 已确认（全库 grep 无匹配） |
| 7 | checkpoint / ledger 不存在旧正式实验状态 | ✅ 已确认（`RTMP-EXP01_checkpoint.jsonl` / `RTMP-EXP01_attempt-ledger.jsonl` 均不存在） |
| 8 | 保持当前 commit `946929e...` | ✅ 已确认 |

全部前置已确认，本报告最终结论为 **`READY FOR FORMAL EXPERIMENT`**；随后第一次执行 canonical command 即意味着 Formal Experiment 正式开始。

---

## 16. Known Issues（本阶段记录，不修复）

### 16.1 `DashScopeEmbeddingAdapter` 的「QWEN_API_KEY is empty」日志误报

- **现象**：qwen profile 下 `@SpringBootTest`（readiness check）启动时，日志出现 `[DashScope] QWEN_API_KEY is empty — EmbeddingAdapter will fail at runtime.`，即使 `QWEN_API_KEY` 环境变量已正确注入且 key 已生效（真正判据 `embed()` 不再抛 `QWEN_API_KEY not configured`）。
- **根因**：[DashScopeEmbeddingAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java) 第 54 行日志判断检查的是构造器参数 `apiKey`（Spring 属性 `shopmind.llm.qwen.api-key`），而非已兜底后的字段 `this.apiKey`：

  ```java
  this.apiKey = (apiKey == null || apiKey.isBlank())
          ? System.getenv("QWEN_API_KEY") : apiKey;   // 第 47-48 行：正确兜底
  ...
  if (apiKey == null || apiKey.isBlank()) {           // 第 54 行：误报（应为 this.apiKey）
      log.warn("[DashScope] QWEN_API_KEY is empty — EmbeddingAdapter will fail at runtime.");
  }
  ```

  测试环境的 `application.yml` 未定义 `shopmind.llm.qwen.api-key`，导致参数 `apiKey` 为空而误报；但 `this.apiKey` 已正确从 `System.getenv("QWEN_API_KEY")` 取值，故运行时行为正确。
- **影响**：纯日志误报。运行时 `embed()`（[第 75 行](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/knowledge/adapter/DashScopeEmbeddingAdapter.java#L75)）检查的是 `this.apiKey`，行为正确。正式环境（main `application.yml` 解析 `${QWEN_API_KEY:}`）不会触发此误报。
- **判定**：不影响 Readiness；按本阶段冻结规则**不修改生产代码**。留待 Gate 后按需修复（改一行 `apiKey` → `this.apiKey`）。

---

### 附录：关键源码位置

- 条件映射：[ExperimentCondition.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/ExperimentCondition.java)
- 正式配置：[RtmpFormalExperimentConfig.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentConfig.java)
- 正式入口：[RtmpFormalExperimentEntryPoint.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentEntryPoint.java)
- 正式 runner：[RtmpFormalExperimentRunner.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentRunner.java)
- plan / 顺序：[RtmpFormalExperimentPlan.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpFormalExperimentPlan.java) / [RtmpConditionOrder.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpConditionOrder.java)
- 数据集：[RtmpDatasetLoader.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/RtmpDatasetLoader.java)
- 运行时 fixture：[RtmpRuntimeScenarioProvider.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/experiment/RtmpRuntimeScenarioProvider.java)
- retry / checkpoint / ledger：[RtmpRunRetryPolicy.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpRunRetryPolicy.java) / [RtmpCheckpointStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpCheckpointStore.java) / [RtmpAttemptLedgerStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/formal/RtmpAttemptLedgerStore.java)
- 统计：[RtmpStatisticalAnalyzer.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/RtmpStatisticalAnalyzer.java)
- 模型适配器：[DashScopeChatAdapter.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/adapter/DashScopeChatAdapter.java)
- 配置：[application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml)
