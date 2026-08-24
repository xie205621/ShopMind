# P4-3 实现与评审报告：Final Tool Visibility & Dual-Entry Integration

> 阶段：Phase 4 — RTMP Router（执行 P4-3）
> 状态：COMPLETED
> 前序：P4-1（RouterContext）✅ → P4-2（确定性评分）✅ → P4-2.1（tool-context risk 修订）✅
> 本阶段把 P4-2.1 已冻结的 `ToolScoreResult → KEEP_CANDIDATE / PRUNE_CANDIDATE` 落实为 canonical `visibleTools`，并保证 System Prompt 与 Function Calling 双入口完全一致。

---

## 0. Executive Summary

P4-3 已完成。在 P4-2.1 冻结的确定性评分层之上，实现了**单向评分-裁剪链**的落地：

```
RtmpScoringEngine
    ↓
ToolScoreResult (KEEP_CANDIDATE / PRUNE_CANDIDATE)
    ↓
ToolMenuPruner
    ↓
visibleTools (canonical, 单一事实源)
    ↓
System Prompt 的【可用工具】  ==  Function Calling 的 tools 参数
```

核心交付：

- **`ToolMenuPruner`**：唯一允许的 `candidate → visible` 落实点，`KEEP_CANDIDATE → visible`、`PRUNE_CANDIDATE → hidden`，不重新计算 risk/relevance。
- **`RtmpVisibility`**：Method C 的 `ToolVisibilityStrategy` 正式实现（`RtmpScoringEngine → ToolMenuPruner`）。
- **`VisibilityResult`**：canonical 裁剪结果载体（`visibleTools` / `prunedTools` / `pruningDecision`）。
- **Router 集成**：`ShopAgentOrchestrator` 在 `discoverTools()` 之后、Prompt 组装与 Function Calling 之前，接入一次性 `computeVisibility`，两个入口由同一份 `visibleTools` 驱动（拆分渲染）。
- **`PruningEvent`**：每次 LLM 迭代产生一个有序 pruning observation，复用 `ToolScoreResult`（不引入第二套 scoring DTO）。

新增 4 个生产类 + 1 个测试类；修改 8 个生产类 + 2 个测试类。全量 `mvn test` **229 通过 / 0 失败 / 0 错误 / 7 Skipped**（= 216 原有 + 13 新增 RtmpVisibilityTest；7 个 Real LLM 测试 `@Disabled`）。严格止步于 visibility / 双入口同步，未进入 Real LLM / threshold calibration / 统计检验 / H1-H5 假设检验。

---

## 1. Why This Phase Exists

P4-2.1 冻结了「评分 → 候选结论」，但候选结论本身并不改变 LLM 实际看到的工具。若候选结论不落到最终工具集合，RTMP 裁剪就只是「算出来但没用上」。本阶段存在的唯一理由是：**把候选结论转化为真实可见性，并保证两个入口同步**。

1. **候选 ≠ 可见**：`ToolScoreResult` 的 `candidate` 是评分结论，`visibleTools` 是 LLM 实际可调用的工具集合。二者是两个概念，必须显式落地而非混为一谈。
2. **双入口是 RTMP 公平性的前提**：System Prompt 的【可用工具】与 Function Calling 的 `tools` 参数若不一致，会引入与裁剪无关的变量，破坏三 baseline 可比性。
3. **empty-tool-set 是有效结果**：0 个可见工具是 RTMP 的合法决策，不能被 fallback 绕过；必须保证 `tools=[]` 不使 orchestrator 崩溃。

---

## 2. Scope

本阶段只实现以下内容（A–F）：

| 项 | 内容 | 落地位置 |
|----|------|----------|
| A | `ToolMenuPruner` 消费 `List<ToolScoreResult>`，产生 `visibleTools` / `prunedTools` / `pruningDecision` | `ToolMenuPruner` |
| B | `RtmpVisibility` 实现 `ToolVisibilityStrategy` | `RtmpVisibility` |
| C | Router 集成：`executeWithToolLoop()` / `discoverTools()` 之后接入 RTMP pruning | `ShopAgentOrchestrator` |
| D | Canonical `visibleTools`（单一事实源 runtime carrier） | `OrchestrationContext` / `VisibilityResult` |
| E | 双入口同步：同一份 `visibleTools` 驱动 Prompt 与 Function Calling | `WorkflowRendererImpl` + `ShopAgentOrchestrator` |
| F | `pruningDecision` instrumentation（消费 P4-2.1 的 scoring 信息） | `PruningEvent` + `ExecutionTrace` |

**本阶段绝对禁止**（全部遵守）：

- ❌ 修改 Relevance / RuntimeContextRisk / StaticRisk / EffectiveRisk 公式
- ❌ 修改 `theta_relevance=0.5` / `theta_risk=0.75`
- ❌ 修改 Authorization / Batch / Ambiguous 映射
- ❌ 恢复「忽略指令」模式
- ❌ 修改 `RouterContext` 字段定义
- ❌ 引入 LLM semantic router / embedding
- ❌ GT 注入 / 修改 Ground Truth
- ❌ 修改 Baseline A / Baseline B Verifier / NoOpSafetyVerifier
- ❌ 修改模型 / 改成 DeepSeek / Seed≠null / Max Tokens≠null
- ❌ Real LLM 实验 / 统计检验 / threshold calibration / GT training
- ❌ 在 P4-3 重新计算 risk/relevance（只消费 P4-2.1 结果）

---

## 3. Candidate → visibleTools Data Flow

单向链（禁止出现第二份 `score()` / `prune()` / `filter()` / `risk()` / `relevance()`）：

```
ShopAgentOrchestrator.computeVisibility(ctx, runtime)
    │  1. discoverWorkflowTools()          → 全量候选工具（已按 workflow toolRules 过滤）
    │  2. RouterContextFactory.build()     → RouterContext（GT-free）
    │  3. condition.visibilityStrategy().apply(allTools, routerContext)
    ▼
RtmpVisibility.apply(tools, context)
    │  RtmpScoringEngine.score(context)    → List<ToolScoreResult>（P4-2.1 冻结）
    │  ToolMenuPruner.prune(tools, scores) → VisibilityResult
    ▼
VisibilityResult
    ├─ visibleTools     → 双入口（System Prompt + Function Calling）
    ├─ prunedTools      → 观测
    └─ pruningDecision  → PruningEvent（复用 ToolScoreResult）
```

关键语义区分：

- **`candidate`**：`ToolScoreResult.candidate()`，来自 P4-2.1 评分层（KEEP_CANDIDATE / PRUNE_CANDIDATE）。
- **`visibleTools`**：`VisibilityResult.visibleTools()`，是 LLM 最终可见集合。
- `ToolMenuPruner` 是二者之间**唯一**的转换点，不在此处引入任何新的 risk/relevance 逻辑。

---

## 4. ToolMenuPruner

`com.shopmind.experiment.ToolMenuPruner`

```java
public VisibilityResult prune(List<ToolSpecification> tools, List<ToolScoreResult> scores) {
    Set<String> keepNames = scores == null ? Set.of()
            : scores.stream()
                    .filter(ToolScoreResult::keepCandidate)
                    .map(ToolScoreResult::toolName)
                    .collect(Collectors.toSet());
    List<ToolSpecification> visible = new ArrayList<>();
    List<ToolSpecification> pruned = new ArrayList<>();
    for (ToolSpecification tool : tools != null ? tools : List.<ToolSpecification>of()) {
        if (keepNames.contains(tool.getToolName())) visible.add(tool);
        else pruned.add(tool);
    }
    return new VisibilityResult(visible, pruned, scores);
}
```

要点：

- **Final visibility rule 冻结**：`KEEP_CANDIDATE → visible`、`PRUNE_CANDIDATE → hidden`，即 `visibleTools = { tool | candidate(tool) == KEEP_CANDIDATE }`。
- 只消费 `ToolScoreResult.keepCandidate()`，不重新计算 risk/relevance。
- 工具与评分按 `toolName` 对齐；`tools` 或 `scores` 为 null 时安全返回空集合（防御性，不改变业务语义）。
- **Multi-tool 约束**：每个工具独立判定，多个 `KEEP_CANDIDATE` 全部保留，不做 Top-1。

---

## 5. RtmpVisibility

`com.shopmind.experiment.RtmpVisibility`

```java
public final class RtmpVisibility implements ToolVisibilityStrategy {
    private final RtmpScoringEngine scoringEngine = new RtmpScoringEngine();
    private final ToolMenuPruner pruner = new ToolMenuPruner();

    @Override
    public VisibilityResult apply(List<ToolSpecification> tools, RouterContext context) {
        List<ToolScoreResult> scores = scoringEngine.score(context);
        return pruner.prune(tools, scores);
    }
}
```

要点：

- Method C 的正式实现，复用 P4-2.1 的 `RtmpScoringEngine`，自身不含评分/裁剪逻辑。
- 只依赖 `RouterContext`（query/history/intent/toolMetadata），**不读取任何 GT**。
- 与 `AllToolsVisibility`（Baseline A/B）共享同一 `ToolVisibilityStrategy` 接口。

---

## 6. Empty-tool-set Policy

**冻结策略**：若 `visibleTools = ∅`，则保持 `∅`，不强制恢复任何工具。

```
RTMP → 0 visible tools → LLM 收到 tools=[] → No-Tool Answer / Clarification 路径
```

**明确禁止**：

- ❌ `∅ → 自动恢复 refund`
- ❌ `∅ → 自动恢复 queryOrder`
- ❌ 保留「最低风险工具」作为隐式 fallback
- ❌ 因为 empty-tool-set 而从 GT 查询 `expectedTool` 作为 fallback

**原因**：empty-tool-set 本身就是 RTMP 的有效决策结果，不应通过 fallback 绕过 pruning。

**安全性**：`VisibilityResult` 保证 `visibleTools`/`prunedTools`/`pruningDecision` 均非 null（`List.copyOf` / `List.of()`）；`ToolMenuPruner` 对 null 输入返回空集合。`ShopAgentOrchestrator` 在 `tools=[]` 时仍正常调用 `chatModelPort.stream(messages, [])`，orchestrator 不崩溃（见 §18 测试 `emptySetDoesNotCrash` / `allPrunedEmptySet`）。

---

## 7. Canonical visibleTools

单一事实源载体有两处（一处是策略返回值，一处是请求内 carrier）：

1. **`VisibilityResult`**（`com.shopmind.experiment.VisibilityResult`）：`ToolVisibilityStrategy` 的正式输出，记录 `visibleTools` / `prunedTools` / `pruningDecision`（有序、不可变）。
2. **`OrchestrationContext`**（`com.shopmind.orchestrator.domain.OrchestrationContext`）：请求内 canonical carrier，新增：
   - `List<ToolSpecification> visibleTools`
   - `List<ToolScoreResult> pruningDecision`
   - `StringBuilder toolObservations`（独立 SystemMessage 反哺 LLM，避免重渲染 System Prompt 时丢失工具执行结果）

`computeVisibility` 一次计算 → `ctx.setVisibleTools()` → 后续 Prompt 与 Function Calling 都从 `ctx.getVisibleTools()` / 同一个 `visibility.visibleTools()` 派生，**不做二次计算**。

---

## 8. System Prompt / Function Calling Synchronization

采用**拆分渲染（Split Rendering）**方案：

1. `WorkflowRenderer` 新增 `render(instance, visibleToolRules)`；`render(instance)` 委托到 `render(instance, instance.definition().toolRules())`（保持 legacy 兼容）。
2. `WorkflowRendererImpl.render(instance, visibleToolRules)` 的【可用工具】段只渲染传入的 `visibleToolRules`。
3. `ShopAgentOrchestrator` 用 `visibleToolRules(visibleTools)` 把 canonical `visibleTools` 映射回 workflow 声明的 `ToolRule` 子集，再调用 `render(instance, visibleToolRules)`。
4. Function Calling 侧直接传 `visibility.visibleTools()`。

结果：

```
Tools_prompt  ==  Tools_functionCalling  ==  visibleTools
```

两个入口**只共享同一份** `visibleTools`，没有各自调用 `ToolVisibilityStrategy` 进行二次计算。

---

## 9. Outer / Inner Iteration Behavior

- **Outer Loop**：`stream()` 的 prelude 不再提前 `assemblePrompt`；Prompt 组装推迟到 `executeWithToolLoop` 内，在 `computeVisibility` 之后进行。
- **Inner Loop**：每次 LLM 迭代（`executeWithToolLoop` 首调用 + `executeToolAndRePrompt` 重新调用）都**重新**执行 `computeVisibility → setVisibleTools → recordPruningEvent → assemblePrompt → buildMessages`。

```
iteration 1 → Router → visibleTools_1 → LLM
LLM tool call → execute
iteration 2 → Router → visibleTools_2 → LLM
```

**允许**每一次 iteration 重新产生 pruning decision，**不**将第一次 Router decision 永久复用。当前架构本身即每次迭代调用一次 Router，loop semantics 未改变（未擅自修改循环语义）。

`currentIteration(ctx)` = `ctx.getState().getToolCallCount() + 1`（第一次 LLM 调用为 1，之后每执行一次工具 +1）。

---

## 10. pruningDecision Instrumentation

`PruningEvent`（`com.shopmind.workflow.domain.PruningEvent`）记录字段：

| 字段 | 来源 |
|------|------|
| `runId` | `trace.getRunId()`（legacy 无 RunIdentity 时为 null） |
| `caseId` | `runIdentity.caseId()`（无 RunIdentity 时为 null） |
| `condition` | `runtime.condition().name()` |
| `routerCallIndex` | 本次 Router 调用序号 |
| `iteration` | 所属 LLM 工具循环迭代号 |
| `inputToolCount` | `visibility.inputToolCount()`（visible + pruned） |
| `visibleTools` | `visibility.visibleTools()` 的 toolName 列表 |
| `prunedTools` | `visibility.prunedTools()` 的 toolName 列表 |
| `pruningDecision` | `visibility.pruningDecision()`（复用 `ToolScoreResult`） |

要点：

- **复用 `ToolScoreResult`** 作为 `pruningDecision`，不引入第二套 scoring DTO。
- `ExecutionTrace` 新增 `List<PruningEvent> pruningEvents` + `addPruningEvent()` + `getPruningEvents()`，追加顺序即 Router 调用顺序。
- **Baseline A/B 不产生 PruningEvent**：`recordPruningEvent` 中 `if (visibility.pruningDecision().isEmpty()) return;`（`AllToolsVisibility` 返回空 pruningDecision）。
- **诚实记录（limitation）**：当前架构下每次 LLM 迭代恰好调用一次 Router，故 `routerCallIndex` 与 `iteration` 数值相同；两个字段保留以支持未来「单迭代多次 Router 调用」的演进，但当前实现未引入独立计数器。

---

## 11. Modified Files

### 新增（生产类）

| 文件 | 职责 |
|------|------|
| `experiment/VisibilityResult.java` | canonical 裁剪结果载体（visibleTools / prunedTools / pruningDecision） |
| `experiment/ToolMenuPruner.java` | candidate → visible 唯一落实点 |
| `experiment/RtmpVisibility.java` | Method C 的 ToolVisibilityStrategy 正式实现 |
| `workflow/domain/PruningEvent.java` | pruning decision runtime observation |

### 修改（生产类）

| 文件 | 修改内容 |
|------|----------|
| `experiment/ToolVisibilityStrategy.java` | 接口签名改为 `apply(List<ToolSpecification>, RouterContext)`，返回 `VisibilityResult` |
| `experiment/AllToolsVisibility.java` | 适配新签名，恒等映射返回 `VisibilityResult(tools, [], [])` |
| `experiment/ExperimentCondition.java` | `METHOD_C(new RtmpVisibility(), new NoOpSafetyVerifier())` |
| `workflow/port/WorkflowRenderer.java` | 新增 `render(instance, visibleToolRules)` |
| `workflow/pipeline/WorkflowRendererImpl.java` | 拆分渲染：工具段只渲染 visibleToolRules |
| `orchestrator/domain/OrchestrationContext.java` | 新增 visibleTools / pruningDecision / toolObservations |
| `workflow/domain/ExecutionTrace.java` | 新增 pruningEvents 列表 + add/get |
| `orchestrator/ShopAgentOrchestrator.java` | Router 集成、双入口同步、pruning instrumentation |

### 测试

| 文件 | 修改内容 |
|------|----------|
| `experiment/RtmpVisibilityTest.java`（新增） | 13 个测试，覆盖 §13 核心行为 |
| `evaluation/RtmpPhase2BaselineABTest.java` | METHOD_C 断言由 `AllToolsVisibility` → `RtmpVisibility` |
| `evaluation/RtmpPhase3MatrixIntegrationTest.java` | METHOD_C 断言 + 三条件公平性测试重写（C 裁剪行为） |

---

## 12. Truth Tables

### 12.1 静态风险（P4-2 冻结，未改）

| 工具 | sideEffect | financialImpact | reversibility | dataSensitivity | permissionScope | staticRisk |
|------|-----------|-----------------|---------------|-----------------|-----------------|------------|
| queryOrder | NONE 0.0 | NONE 0.0 | N_A 0.0 | MEDIUM 0.5 | OWN_DATA 0.0 | **0.10** |
| refund | WRITE 0.5 | HIGH 1.0 | PARTIAL 0.5 | MEDIUM 0.5 | OWN_DATA 0.0 | **0.50** |
| queryPoints | NONE 0.0 | NONE 0.0 | N_A 0.0 | LOW 0.25 | OWN_DATA 0.0 | **0.05** |
| queryCoupons | NONE 0.0 | NONE 0.0 | N_A 0.0 | LOW 0.25 | OWN_DATA 0.0 | **0.05** |

### 12.2 Runtime Context Risk（P4-2.1 冻结，未改）

| 可疑域 | queryOrder | refund | queryPoints | queryCoupons |
|--------|-----------|--------|-------------|--------------|
| Authorization（他人订单/冒充/越权） | 1.0 | 1.0 | 0.0 | 0.0 |
| Batch（批量） | 0.0 | 1.0 | 0.0 | 0.0 |
| Ambiguous（别人/他人/朋友/同事/家人/亲戚） | 0.5 | 0.5 | 0.5 | 0.5 |
| 无 | 0.0 | 0.0 | 0.0 | 0.0 |

### 12.3 Candidate → visibleTools（本阶段）

| 场景 | candidate 结果 | Expected visibleTools | 实测 |
|------|----------------|-----------------------|------|
| 正常 queryOrder | queryOrder KEEP | 含 queryOrder | ✅ |
| 正常 refund | refund KEEP | 含 refund | ✅ |
| 合法高风险 refund（static 0.5 + runtime 0 → effective 0.5） | refund KEEP | 含 refund | ✅ |
| 他人订单（Authorization） | queryOrder PRUNE + refund PRUNE | 不含二者 | ✅ |
| 他人订单 + 无关低风险工具 KEEP | points/coupons KEEP | 保留对应工具 | ✅ |
| 批量退款（Batch） | refund PRUNE | 不含 refund | ✅ |
| MULTI_TOOL 正常任务 | 多个 KEEP | 全部保留 | ✅ |
| 全部 PRUNE | 全部 PRUNE | visibleTools = ∅ | ✅ |
| 无工具必要性 | 全部相关性不足 | visibleTools = ∅ | ✅ |

> 注：合法高风险 refund 的 `effective=0.5 < theta_risk=0.75`，故 `KEEP_CANDIDATE`，不退化为「高风险工具永远隐藏」。

---

## 13. Problems / Findings

1. **旧测试断言漂移（发现并修复）**：`RtmpPhase2BaselineABTest` / `RtmpPhase3MatrixIntegrationTest` 中 METHOD_C 仍断言 `AllToolsVisibility`，与 P4-3 的 `RtmpVisibility` 不符；已更新。
2. **三条件公平性测试假设过时（发现并修复）**：原公平性测试要求三条件 Function Calling 工具池全部一致，忽略了 Method C 的裁剪本质；已重写为「A/B 共享 AllToolsVisibility、C 使用 RtmpVisibility 且 C 工具池是全量工具子集」。
3. **`routerCallIndex` 与 `iteration` 数值相同**：当前架构每次 LLM 迭代恰好调用一次 Router，二者在当前实现下相等；已如实记录为 limitation，未引入独立计数器（避免过度工程）。
4. **工具执行结果重渲染丢失风险**：引入 `toolObservations` 独立 SystemMessage，避免 inner loop 重渲染 System Prompt 时丢失已执行工具的结果。

---

## 14. Root Causes

- **旧断言漂移**：`ExperimentCondition.METHOD_C` 在 P4-2 阶段仍指向 `AllToolsVisibility`（因为当时尚未实现 RtmpVisibility），相关测试沿用旧断言；P4-3 落成 `RtmpVisibility` 后未同步。
- **公平性测试假设过时**：Phase 3 阶段「三条件框架公平」的验证聚焦于 config/verifier 层面，Method C 的 visibility 尚未实现，故当时断言三条件工具池一致是合理的；P4-3 引入裁剪后该假设不再成立。

---

## 15. Decisions Made

1. **拆分渲染（Split Rendering）**：双入口同步通过 `WorkflowRenderer.render(instance, visibleToolRules)` 实现，而非让两个入口各自调用 `ToolVisibilityStrategy`。
2. **`VisibilityResult` 作为策略返回载体**：把 `ToolVisibilityStrategy.apply` 返回类型从 `List<ToolSpecification>` 改为 `VisibilityResult`，同时承载 visible/pruned/decision 三者。
3. **`PruningEvent` 复用 `ToolScoreResult`**：不引入第二套 scoring DTO。
4. **Baseline A/B 不产生 PruningEvent**：`AllToolsVisibility` 返回空 `pruningDecision`，`recordPruningEvent` 对空 decision 短路。
5. **empty-tool-set 不 fallback**：保持 ∅，进入 No-Tool Answer / Clarification 路径。

---

## 16. Problems Resolved

- METHOD_C 正式使用 `RtmpVisibility`（不再是 AllToolsVisibility）。
- `visibleTools` 成为单一事实源，Prompt 与 Function Calling 完全一致。
- empty-tool-set 可安全运行（`tools=[]` 不崩溃）。
- 多工具独立保留（禁止 Top-1）。
- 合法高风险 refund 不被默认裁剪。
- 越权 refund/queryOrder 被裁剪；无关低风险工具不因全局 risk 被机械裁剪。
- pruningDecision 可观测。
- Router 全链路 GT-free。

---

## 17. Validation

- **单元测试**：`RtmpVisibilityTest` 13 个测试（见 §18）。
- **集成测试更新**：`RtmpPhase2BaselineABTest` / `RtmpPhase3MatrixIntegrationTest` 断言更新为 `RtmpVisibility`。
- **全量回归**：`mvn test` 全量通过，覆盖 legacy + Phase 1–3 + P4-1 + P4-2 + P4-2.1 + P4-3。

---

## 18. Test Results

全量 `mvn test`：**229 通过 / 0 失败 / 0 错误 / 7 Skipped**（7 个 Real LLM 测试 `@Disabled`）。

`RtmpVisibilityTest` 覆盖的核心行为：

| 测试 | 覆盖行为 |
|------|----------|
| `normalQueryOrderVisible` | 正常 queryOrder → KEEP / visible |
| `normalRefundVisible` | 正常 refund → KEEP / visible |
| `normalQueryPointsVisible` | 正常 queryPoints → KEEP / visible |
| `normalQueryCouponsVisible` | 正常 queryCoupons → KEEP / visible |
| `legitimateHighRiskRefundKept` | 合法高风险 refund（static 0.5/runtime 0/effective 0.5）→ KEEP/visible |
| `thirdPartyRefundAndOrderPruned` | 第三方订单 refund + queryOrder → hidden |
| `unrelatedLowRiskToolNotPrunedByAuthorization` | 越权下 queryPoints 不因全局 risk 被机械裁剪 |
| `batchPrunesRefundButNotUnrelatedReadOnly` | 批量裁剪 refund，不机械裁剪 queryPoints |
| `multiToolAllKeptVisible` | multi-tool 多 KEEP 全部保留（禁止 Top-1） |
| `allPrunedEmptySet` | 全部 PRUNE → visibleTools = ∅ |
| `emptySetDoesNotCrash` | empty-tool-set 返回非 null 空集合，不崩溃 |
| `pruningDecisionObservable` | 每个工具都有 ToolScoreResult，无第二套 DTO |
| `routerContextHasNoGroundTruthFields` | RouterContext 不含任何 GT 字段 |

---

## 19. Ground Truth Leakage Audit

- `RtmpVisibility.apply` 只调用 `RtmpScoringEngine.score(context)`，而 `RtmpScoringEngine` 只读 `RouterContext.toolMetadata()`（query/history/intent/toolMetadata），不读取 GT。
- `RouterContext` record 字段经反射断言（`routerContextHasNoGroundTruthFields`）确认不含：`expectedTool` / `expectedOutcome` / `expectedToolAction` / `taskCategory` / `riskLabel` / `adversarial` / `expectedReason` / `mockResponse` / `candidateTools` / `toolRiskProfile` / `contextRisk` / `authorization` / `groundTruth`。
- `ToolMenuPruner` 只读 `ToolScoreResult`（评分结果）与 `ToolSpecification`（工具定义），不读取 GT。
- **empty-tool-set 无 GT fallback**：`ToolMenuPruner` 在 `visibleTools=∅` 时直接返回 ∅，不查询 `expectedTool`。
- 结论：Router 全链路 GT-free。

---

## 20. Experiment Impact

- **Condition A**：`AllToolsVisibility + NoOpSafetyVerifier` — 不变（恒等映射，无 pruning event）。
- **Condition B**：`AllToolsVisibility + PostHocSafetyVerifier` — 不变（恒等映射，无 pruning event；Verifier 只校验 tool call）。
- **Condition C**：`RtmpVisibility + NoOpSafetyVerifier` — 新增裁剪（KEEP→visible，PRUNE→hidden），NoOp Verifier 不变。

**公平性保证**：A/B 共享 `AllToolsVisibility`，C 使用 `RtmpVisibility`；A/B/C 的 system prompt 除「工具可见性」外完全一致；未给 Method C 添加任何额外 Safety Prompt / 拒答示例 / 模型改变。

---

## 21. Research Limitations

（诚实记录，不包装）

- **`runtimeAuthorization = Optional.empty()`**：RouterContext 的授权字段当前无真实 runtime 来源，恒为空。
- **`runtimeTargetScope = Optional.empty()`**：目标范围字段当前无真实 runtime 来源，恒为空。
- **Router 无法识别 query/history 未显式表达的真实授权状态**：RuntimeContextRisk 只依赖文本层面的可疑模式匹配，无法感知文本未表达的实际授权。
- **relevance 是 deterministic rule-based heuristic**：`RelevanceScorer` 基于手写 `ToolSemanticLexicon` + bigram 重叠，**不是** embedding / LLM semantic matching，不具备真正的语义理解。
- **`routerCallIndex` 与 `iteration` 数值相同**：当前架构每次 LLM 迭代恰好调用一次 Router，二者在当前实现下相等。

---

## 22. Retrospective

- **单向链清晰**：评分 → 裁剪 → visibleTools → 双入口，无第二份评分/裁剪逻辑，符合 spec「禁止重新计算」。
- **类型隔离有效**：`VisibilityResult` / `PruningEvent` 把「candidate」与「visibleTools」明确分离，避免概念混淆。
- **复用已有评分层**：P4-3 未重写任何评分/风险逻辑，严格消费 P4-2.1 结果，验证了 P4-1/P4-2 分层冻结的正确性。
- **可改进**：`routerCallIndex` 若未来需要与 `iteration` 解耦，应引入独立计数器；当前无此需求，不提前工程化。

---

## 23. Freeze Compliance

| 禁止项 | 状态 |
|--------|------|
| 不修改 Relevance/RuntimeContextRisk/StaticRisk/EffectiveRisk 公式 | ✅ 未改 |
| 不修改 theta_relevance/theta_risk | ✅ 未改 |
| 不修改 Authorization/Batch/Ambiguous 映射 | ✅ 未改 |
| 不恢复「忽略指令」模式 | ✅ 未改 |
| 不修改 RouterContext 字段 | ✅ 未改 |
| 不引入 LLM semantic router / embedding | ✅ 未引入 |
| 不 GT 注入 / 不改 Ground Truth | ✅ 未注入 |
| 不改 Baseline A / Baseline B Verifier / NoOpSafetyVerifier | ✅ 未改 |
| 不改模型 / 不 DeepSeek / Seed≠null / MaxTokens≠null | ✅ 未改 |
| 不 Real LLM / 统计检验 / threshold calibration / GT training | ✅ 未做 |
| 不在 P4-3 重新计算 risk/relevance | ✅ 只消费 P4-2.1 |

---

## 24. Completion Gate

- ✅ `ToolMenuPruner` implemented
- ✅ `RtmpVisibility` implemented
- ✅ Method C 真正使用 RTMP visibility
- ✅ `visibleTools` 成为 canonical source
- ✅ Prompt / Function Calling 完全一致
- ✅ empty-tool-set 可安全运行
- ✅ 多工具独立保留
- ✅ 合法 refund 不被默认裁剪
- ✅ unauthorized refund 被裁剪
- ✅ 无关低风险工具不因全局 risk 被机械裁剪
- ✅ pruningDecision 可观测
- ✅ Outer / Inner loop 正确
- ✅ Router 全链路 GT-free
- ✅ Baseline A unchanged
- ✅ Baseline B unchanged
- ✅ 全量测试通过（229/0/0/7）
- ✅ 报告与代码一致

---

## 25. Next Phase Preconditions

P4-3 完成后**立即停止**，不进入正式实验阶段。后续（另开阶段，需用户确认）才可能涉及：

- 正式 Real LLM Experiment
- Threshold tuning / Calibration
- Statistical analysis（McNemar / Wilcoxon，不用 t-test）
- H1–H5 hypothesis testing

进入下一阶段前，需用户显式确认方向与范围。
