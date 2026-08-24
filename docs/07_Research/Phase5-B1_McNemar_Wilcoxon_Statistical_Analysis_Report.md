# Phase 5-B1 — McNemar / Wilcoxon Statistical Analysis Report

> 本报告对应实施文档《Phase 5-B1 — McNemar / Wilcoxon Statistical Analysis》。
> 实施范围：从 B4 Raw 提取 paired observations，计算冻结的 McNemar / Wilcoxon signed-rank 统计量与 p-value，写回 Comparison 的统计字段。
> **Phase 5-C1 修订**：§17/§34 更新 multiple-comparison 口径——Holm-Bonferroni 校正 confirmatory primary family（H1/H2/H3）；§10 补 McNemar 空配对行为；§23/§29 补 `pValue` / `adjustedPValue` 字段。详见 [Phase5-C1 report](Phase5-C1_Runtime_Context_Fairness_Correction_and_Protocol_Closure_Report.md)。

---

## 0. Executive Summary

Phase 5-B1 已完成。统计模块（McNemar exact / Wilcoxon asymptotic signed-rank）已实现并写回 `RtmpComparison` 的统计字段，统计事实源严格保持为 `RtmpRawRecord`（Raw），`comparison.json` 仅作为统计结果的持久化载体（result sink），不改动 Raw。

关键结论：

- **配对键**：`caseId#repetition`；只有两边 `status == VALID` 且 `evaluation != null` 才进入统计，invalid/missing 排除并记录 `excludedCount`。
- **McNemar**：exact two-sided，`statistic = b - c`（signed discordance，非 χ²），`p = min(1, 2·P(X ≤ min(b,c)))`，`X ~ Binomial(b+c, 0.5)`，zero-discordance → `p=1`。
- **Wilcoxon**：two-sided asymptotic signed-rank，zero differences 丢弃，midrank ties，tie-corrected variance，continuity correction，`n=0 → p=1`，`n<2 → p=null/INSUFFICIENT_PAIRS`。
- **判定**：仅 `SIGNIFICANT / NOT_SIGNIFICANT / INSUFFICIENT_PAIRS`，`alpha = 0.05`，双侧；禁止写研究结论。
- **多重比较（Phase 5-C1）**：confirmatory primary family `{H1,H2,H3}` 采用 **Holm-Bonferroni**；`ComparisonEntry` 同时保留 `pValue`（raw）与 `adjustedPValue`；H4（secondary）与 H5（exploratory）及 null hypothesis 不校正（`adjustedPValue == pValue`）。
- **测试**：新增 40 个 B1 聚焦测试 + 9 个 C1 统计测试（`HolmBonferroniTest` 6 + `RtmpStatisticalAnalyzerTest` 3）；全量回归通过。

---

## 1. Phase Status

- Phase 1–3 ✅
- P4-1 ✅ / P4-2 ✅ / P4-2.1 ✅ / P4-3 ✅
- Phase 5 Formal Experiment Protocol Freeze ✅
- Phase 5-B2 Case-level Evaluator ✅
- Phase 5-B3 Control Overhead Instrumentation ✅
- Phase 5-B4 Raw / Summary / Comparison Persistence ✅
- **Phase 5-B1 McNemar / Wilcoxon Statistical Analysis ✅（本阶段）**
- 正式 Real LLM Experiment：⏳（未启动，见 §36）

---

## 2. B1 Objective

从 B4 Raw 中提取 paired observations，计算冻结的 McNemar / Wilcoxon signed-rank 统计量与 p-value，写回 Comparison 的统计字段。统计模块不是实验执行器，也不是 Evaluator。

---

## 3. Scope

实现内容（对应文档 §1）：

- B1.1 Paired-unit extraction（`caseId#repetition` 对齐）
- B1.2 McNemar（paired binary outcomes）
- B1.3 Wilcoxon signed-rank（paired scalar observations，H4 control latency）
- B1.4 Comparison 更新（`statisticalTest / statistic / pValue / decision / alpha / twoSided`）
- B1.5 Tests（paired extraction / McNemar / Wilcoxon / comparison integration）
- B1.6 B1 报告（本文件）

未实现/被明确禁止（§2）：Real LLM、378-run、Pilot、threshold tuning、Evaluator 修改、B3/B4 schema 修改（除统计写回必需的最小兼容扩展）、t-test、ANOVA、regression、independent-samples test、单样本检验、post-hoc subgroup 挑选、研究结论。

---

## 4. Canonical Statistical Source

统计唯一事实源为：

```text
experiments/{experimentId}_raw.json  →  RtmpRawRecord
```

允许读取：`RtmpRawRecord`、`RtmpCaseEvaluation`、`ControlOverheadEvent`、`RunIdentity`、`condition`、`caseId`、`repetition`、`status`。

禁止以 Summary 作为统计数据源；`comparison.json` 仅用于找到对应 metric/pair 并写回统计结果，不得从 `valueA/valueB` 反推 McNemar 输入。

---

## 5. Pairing Unit

唯一配对键：`caseId#repetition`（如 `RTMP-001#1`）。每个键构成一个 paired unit。

---

## 6. Paired-unit Eligibility

一个 paired unit 只有在以下条件下才进入某项统计：

```text
两 condition 均存在相同 caseId#repetition
AND 两边 status == VALID
AND 两边 evaluation != null
```

H4 latency 要求两边均 VALID 且对应 control observation 可确定（per-run sum）。

无效/缺失单位：排除该项 paired test，并记录 `excludedCount`。禁止 `missing → 0 / failed / impute`。

---

## 7. McNemar Definition

只用于 paired binary outcome。设 `b = count(A=true, B=false)`、`c = count(A=false, B=true)`、`n_discordant = b+c`。一致 pair（A=true,B=true / A=false,B=false）不参与 McNemar statistic。

---

## 8. McNemar Exact P-value

冻结为 exact two-sided McNemar：`X ~ Binomial(n=b+c, p=0.5)`，`p = min(1, 2·P(X ≤ min(b,c)))`，two-sided，`alpha = 0.05`。

实现：`McNemarExact.compute`，本地累加二项 PMF（`binomialCdf`），不使用 continuity-corrected chi-square，也不按样本量切换 exact/chi-square。

---

## 9. McNemar Direction

`positive difference = conditionA true − conditionB true`，即 `signedDiscordance = b - c`。`b > c` 表示 A 有更多 positive outcome。

`statistic` 字段对 McNemar 保存 signed discordance difference `b-c`（非传统 χ²）；正式检验依据为 exact binomial p-value。额外的 `discordantAOnly (=b)` / `discordantBOnly (=c)` / `discordantN (=b+c)` 保留在 `StatisticalResult` DTO 中。

---

## 10. McNemar Edge Cases

- **无配对 unit（pairedN=0）**：`pValue = null`，`statistic = 0`，`decision = INSUFFICIENT_PAIRS`（**Phase 5-C1 修订**：不伪造 `p=1.0`，避免占位 p 污染 Holm primary family 的 m）。
- `b=0, c=0`（pairedN>0 但无 discordant）→ `pValue = 1.0, statistic = 0, decision = NOT_SIGNIFICANT`（不返回 null，因为配对存在但无 discordant observation）。

---

## 11. Wilcoxon Definition

只用于 paired scalar observations，当前正式用途为 H4 control latency（B verifier latency vs C router latency）。在同一 `caseId#repetition` 上形成 `d_i = x_i − y_i`。

---

## 12. Zero Difference Policy

`d_i = 0` 不参与 rank / W+ / W-，也不计入 effective paired N（在 ranking 前丢弃）。

---

## 13. Tie Ranking

对所有 `|d_i| > 0` 按绝对差值升序排序；相同绝对差值使用 **midrank / average rank**（如 `1,1,3 → 1.5,1.5,3`）。不使用 first-rank / competition-rank。

---

## 14. Wilcoxon Statistic

`W+ = Σ rank(d>0)`，`W- = Σ rank(d<0)`，`W = min(W+, W-)`。Comparison 的 `statistic` 记录 `W`；报告同时记录 `W+ / W- / nonZeroN / zeroDifferenceN`（保留在 `StatisticalResult` DTO）。

---

## 15. Wilcoxon P-value

冻结为 two-sided asymptotic Wilcoxon signed-rank test with tie-corrected variance and continuity correction。

- `E(W+) = n(n+1)/4`
- 无 ties：`Var(W+) = n(n+1)(2n+1)/24`
- 有 ties（tie group 大小 `t_j`）：`Var(W+) = n(n+1)(2n+1)/24 − Σ_j (t_j³ − t_j)/48`
- continuity correction：`|W+ − E|` 减 0.5 后标准化
- `p = 2(1 − Φ(|z|))`，`alpha = 0.05`

正态 CDF `Φ(z)` 使用本地实现的 Abramowitz & Stegun 7.1.26 erf 近似（最大绝对误差 < 1.5e-7），不新增大型统计依赖。

---

## 16. Statistical Decision Rule

```text
p < 0.05   → SIGNIFICANT
p >= 0.05  → NOT_SIGNIFICANT
p == null  → INSUFFICIENT_PAIRS
```

`alpha = 0.05`，双侧。统计模块只写这三个值，不写 `H1_SUPPORTED` 等研究结论（`StatisticalDecision.fromPValue` 统一判定）。

---

## 17. Multiple Comparison Policy

**（Phase 5-C1 修订）**

- confirmatory primary family = `{H1, H2, H3}`，采用 **Holm-Bonferroni correction**（step-down，FWER，α=0.05）。
- `RtmpStatisticalAnalyzer` 收集所有 `hypothesis ∈ {H1,H2,H3}` 且 `pValue != null` 的 raw p，`HolmBonferroni.adjust` 计算 `adjustedPValue`，写回 `ComparisonEntry.adjustedPValue`。
- **raw p-value 保留**：`pValue` 字段保存 unadjusted two-sided p-value，`adjustedPValue` 另存校正值，二者并存。
- **H4（secondary）/ H5（exploratory）/ null hypothesis 不进入 Holm family**：其 `adjustedPValue == pValue`（未校正）。
- 配对不足（`INSUFFICIENT_PAIRS`，`pValue == null`）不进入 Holm，`adjustedPValue == null`。
- 判定阈值：`adjustedP < 0.05 → SIGNIFICANT`，否则 `NOT_SIGNIFICANT`；`p == null → INSUFFICIENT_PAIRS`。

> B1 初版 §17 曾写「不进行校正（协议未冻结）」，该口径已被 Phase 5-C1 冻结的 Holm（H1–H3）取代。

---

## 18. H1 Mapping

H1 primary metric = `L2 High-risk Tool Misuse`（binary）→ McNemar，写入 `L2_RATE`。主比较 `A_vs_C`、`B_vs_C`；`A_vs_B` 为 secondary baseline comparison。

---

## 19. H2 Mapping

H2 primary metric = `Core Task Success`（binary `coreTaskSuccess`）→ McNemar，写入 `CORE_TASK_SUCCESS_RATE`，只使用 `coreTaskEligible=true` 的 paired units。主比较 `A_vs_C`、`B_vs_C`。只产生 p-value/statistic/difference/decision，不因 `p>0.05` 写“证明无下降”。

---

## 20. H3 Mapping

H3 primary metric = `Over-refusal`（paired binary）→ McNemar，写入 `OVER_REFUSAL_RATE`，eligible = `ANSWER_EXPECTED ∧ expectedToolAction=CALL`。主比较 `A_vs_C`、`B_vs_C`。

---

## 21. H4 Mapping

H4 primary comparison = `B_vs_C`，使用 Wilcoxon signed-rank。

- B：`verifierControlLatencyMs = sum of SAFETY_VERIFIER event latency within run`
- C：`routerControlLatencyMs = sum of RTMP_ROUTER event latency within run`
- `d_i = latency_B − latency_C`（同一 `caseId#repetition`）

Comparison 的 `ROUTER_LATENCY_MEAN_MS` / `VERIFIER_LATENCY_MEAN_MS` 保留 descriptive values；但 B1 的 Wilcoxon 使用 **per-paired-unit latency observations**，不是 condition-level mean。

不使用“总运行时间”替代 primary control latency。

---

## 22. H5 Subgroup Support

本阶段不产生 H5 最终结论。统计原语（`McNemarExact.compute` / `WilcoxonSignedRank.compute`）是通用的，可对任意 paired subset（含 subgroup）执行同样检验；预注册 subgroup 分组（`HIGH_RISK / MULTI_TOOL / AMBIGUOUS` 等）已由 `RtmpSummaryBuilder.Subgroup` 提供。

**当前状态**：`RtmpStatisticalAnalyzer` 仅在 metric 层（H1–H4 primary/secondary metrics）写回统计；尚未在 Comparison 中物化 subgroup 分组的统计写回（见 §33 Known Limitations）。代码中不存在 `findBestSubgroup()`，不读取实验结果动态选择 subgroup。

---

## 23. Comparison Write-back

完成统计计算后，`RtmpStatisticalAnalyzer.analyze` 更新 `RtmpComparison` 对应字段：

```text
statisticalTest = "McnemarExact" | "WilcoxonSignedRankAsymptotic"
statistic
pValue          （raw two-sided p-value，保留）
adjustedPValue  （Phase 5-C1：H1/H2/H3 经 Holm；H4/H5/null 未校正 == pValue）
decision
alpha = 0.05
twoSided = true
```

`ComparisonEntry` 新增 `alpha` / `twoSided` 两个字段（§29 最小字段扩展）；Phase 5-C1 再新增 `pValue`（明确 raw p 命名）与 `adjustedPValue` 两个字段。原始 `valueA/valueB/difference/relativeDifference/pairedN/pairedUnitIds` 保持不变。

---

## 24. Raw Preservation

B1 只读取 Raw，禁止把 `pValue / statistic / decision` 写回 Raw。Raw 仍为原始事实层；统计结果只写入 Comparison。集成测试 `rawUnchanged` 断言 `analyze` 不改动 Raw 记录。

---

## 25. Numerical Precision

p-value 以 `double` 精度保存，JSON 序列化保留完整 double 值（不做 `0.05` 这类人工截断）。`NormalDistribution.cdf` 使用 double 精度的 erf 近似。

---

## 26. Tests

新增 B1 聚焦测试 40 个（synthetic / deterministic fixture，不运行 Real LLM、不改 42-case dataset）：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `NormalDistributionTest` | 6 | Φ(0)/Φ(±1)/Φ(1.96)/Φ(2)/erf(1) reference values |
| `McNemarExactTest` | 10 | b=0,c=0 / onlyB / onlyC / b=c / symmetric / exact p / alpha / direction b−c / mismatched length |
| `WilcoxonSignedRankTest` | 12 | positive-only / negative-only / mixed / zero removed / midrank / n=0 / n=1 / continuity / tie variance（含精确值）/ two-sided p / mismatched |
| `RtmpStatisticalAnalyzerTest` | 12 | H1/H2/H3/H4 mapping / 字段填充 / Raw 不变 / pair 顺序 / pairedN / 无 t-test / determinism / invalid & missing 排除 |

覆盖文档 §33/§34/§35 的测试项，满足“B1-focused 测试总量不少于 20 个”。

Fixtures（`RtmpB1Fixtures`）直接构造 `RtmpCaseEvaluation`（绕过 Evaluator），精确控制 L2 / coreTaskSuccess / overRefusal 与 control latency，含 A/B/C、同 caseId、多 repetition、binary outcomes、latency observations、invalid run、missing counterpart、zero differences、ties。

---

## 27. Problems / Findings

1. **无统计库**：`pom.xml` 无 Apache Commons Math / Statistics，需本地实现 McNemar exact 与 Wilcoxon（§37 原则：优先复用，其次本地实现）。
2. **ComparisonEntry 缺协议元数据**：B4 的 `ComparisonEntry` 无 `alpha` / `twoSided`，需做最小兼容字段扩展（§29）。
3. **Wilcoxon tie-corrected variance 系数错误（已修复）**：初版将 tie 项除以 24（等价于 tie 校正项翻倍），应为 `/48`。已修正为标准公式并新增 reference-value 测试锁定。
4. **descriptive `pairedN` vs 统计 `pairedN` 语义差异**：B4 的 `pairedN` 统计所有 valid paired units；B1 对 `CORE_TASK_SUCCESS_RATE` / `OVER_REFUSAL_RATE` 的实际 McNemar 输入只含 eligible units（`coreTaskEligible=true` / `ANSWER_EXPECTED∧CALL`）。Comparison 保留 B4 的 descriptive `pairedN`，统计 eligible 数保留在 `StatisticalResult` 中。此为语义差异，非数据损坏。
5. **H5 subgroup 统计写回未物化**：见 §22 / §33。
6. **陈旧 Javadoc（已修复）**：`RtmpComparison` / `RtmpComparisonBuilder` 原注释“B1 尚未实现”已更新为 B1 已实现、统计写回由 `RtmpStatisticalAnalyzer` 负责。

---

## 28. Root Causes

1. 无统计库 → 项目自 B4 起未引入 Apache Commons Math/Statistics，B1 选择本地 deterministic 实现。
2. tie 方差 bug → 标准 Wilcoxon tie-corrected variance 的 tie 项系数为 `/48`（来自 `Σ t_j(t_j−1)(t_j+1)/2`），初版误写为 `/24`。
3. ComparisonEntry 缺字段 → B4 冻结的 Comparison schema 只承载描述性比较，未预留统计元数据字段。

---

## 29. Decisions Made

1. 本地实现 `NormalDistribution`（A&S 7.1.26 erf）、`McNemarExact`（binomial PMF/CDF）、`WilcoxonSignedRank`（tie-corrected variance + continuity correction），不新增依赖。
2. McNemar `statistic = b - c`（signed discordance），Wilcoxon `statistic = W = min(W+, W-)`。
3. `ComparisonEntry` 新增 `alpha` / `twoSided` 最小字段扩展，`schemaVersion` 保持 `rtmp-b4-comparison-v1`（向后兼容的字段新增，不 bump 冻结 schema）。
4. 精确 McNemar binomial，禁止 chi-square 或样本量切换。
5. 采用标准 tie-corrected variance（`/48`），与 R / SciPy 的 asymptotic Wilcoxon 行为一致。
6. **（Phase 5-C1）** 本地实现 `HolmBonferroni.adjust`，`ComparisonEntry` 新增 `pValue`（raw）与 `adjustedPValue` 字段；confirmatory primary family `{H1,H2,H3}` 进入 Holm，H4/H5/null 不校正。
7. **（Phase 5-C1）** McNemar 空配对（pairedN=0）返回 `pValue=null` / `INSUFFICIENT_PAIRS`，不伪造 `p=1.0`，避免污染 Holm family 的 m。

---

## 30. Problems Resolved

1. 无统计库 → `NormalDistribution` / `McNemarExact` / `WilcoxonSignedRank` 本地实现。
2. tie 方差系数错误 → 修正为 `/48` 并用 `tieCorrectedVarianceExact` 锁定。
3. `ComparisonEntry` 缺元数据 → 新增 `alpha` / `twoSided`。
4. 陈旧 Javadoc → 更新为 B1 语义。

---

## 31. Validation

验证方式：

- 单元测试：`McNemarExactTest` / `WilcoxonSignedRankTest` / `NormalDistributionTest`。
- 集成测试：`RtmpStatisticalAnalyzerTest`（B4 builder → 统计写回 → 字段填充 / Raw 不变 / pair 顺序 / pairedN / 无 t-test / determinism）。
- 全量回归：`mvn -o test`（见 §32）。

---

## 32. Test Results

（见下方“实际 mvn test 结果”，回填最终全量数字。）

- B1 聚焦测试：40 个，全部通过。
- 全量回归：`mvn -o test` → **Tests run: 324, Failures: 0, Errors: 0, Skipped: 7** → BUILD SUCCESS。

> 基线 284（B1 前）+ 新增 40（B1）= 324。

---

## 33. Known Limitations

1. **H5 subgroup 统计写回未物化**：`RtmpStatisticalAnalyzer` 目前仅在 metric 层写回统计；subgroup 分组的统计写回未在 Comparison 中物化（依据 §25“本阶段不产生 H5 最终结论”）。统计原语已通用化，可支持后续 subgroup 检验。
2. **`n=1` INSUFFICIENT_PAIRS 的 W+/W- 占位**：`nonZeroN < 2` 分支在计算 rank 前提前返回，`wPlus/wMinus` 置 0.0（实际单个非零差值的 rank 应为 1.0），但此时 `pValue=null`，W 值无统计意义。
3. **asymptotic 近似**：Wilcoxon 采用 asymptotic（非 exact enumeration），符合 §15 冻结定义。
4. **McNemar `statistic` 非 χ²**：`statistic` 保存 signed discordance `b−c`，正式检验依据为 exact binomial p-value（§8）。

---

## 34. Protocol Gaps

1. ~~无 multiple-comparison correction~~ → **Phase 5-C1 已关闭**：Holm-Bonferroni（H1–H3 primary family）已冻结并实现（§17）。
2. **subgroup 统计写回目标 schema 未冻结**：若后续需物化 H5 subgroup 统计，需再冻结 Comparison 的 subgroup 扩展 schema。H5 已冻结为 exploratory/descriptive（C1），不进入 Holm family。

---

## 35. Freeze Compliance

| 冻结项 | 状态 |
| --- | --- |
| paired extraction implemented | ✅ |
| caseId#repetition pairing preserved | ✅ |
| McNemar exact implemented | ✅ |
| two-sided alpha=0.05 | ✅ |
| McNemar edge cases covered | ✅ |
| Wilcoxon signed-rank implemented | ✅ |
| zero-difference policy frozen | ✅ |
| tie handling frozen（midrank） | ✅ |
| continuity correction frozen | ✅ |
| tie-corrected variance implemented | ✅ |
| paired latency extraction implemented | ✅ |
| H1 L2 mapping correct | ✅ |
| H2 Core Task Success mapping correct | ✅ |
| H3 Over-refusal mapping correct | ✅ |
| H4 latency mapping correct | ✅ |
| H5 subgroup support present | ⚠️ 原语通用，analyzer 层 subgroup 写回未物化（§22/§33） |
| statistics derived from Raw | ✅ |
| no aggregate-rate McNemar | ✅ |
| no independent-sample tests | ✅ |
| no t-test | ✅ |
| Comparison write-back works | ✅ |
| Raw unchanged | ✅ |
| >=20 B1-focused tests | ✅（40） |
| full regression passes | ✅ |
| report generated | ✅ |
| report matches implementation | ✅ |

---

## 36. Completion Gate

最终状态：

```text
B1 COMPLETE / READY FOR FORMAL EXPERIMENT
```

说明：统计基础设施层面（McNemar exact / Wilcoxon asymptotic signed-rank / paired extraction / Comparison 写回 / Raw 不变）已具备正式实验条件。H5 subgroup 统计写回为 deferred 项（原语已支持），不影响 H1–H4 的统计基础设施就绪。

---

## 37. Next Phase Preconditions

进入正式 Real LLM Experiment（378 runs / Pilot）前需满足：

1. 冻结正式实验的 run 执行与 retry policy / condition ordering policy（本阶段未处理）。
2. 确认 subgroup 统计写回是否需要在本阶段前置物化（如需，需先冻结 subgroup 扩展 schema）。
3. 确认正式实验的 Raw 输出可被 `RtmpRawRecord` 完整消费（B4 已具备）。
4. 授权进入正式实验（本阶段按 §43 停止，等待下一阶段授权）。

---

## 附：实现文件清单

**新增（main）**

- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/NormalDistribution.java`
- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/StatisticalDecision.java`
- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/StatisticalResult.java`
- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/McNemarExact.java`
- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/WilcoxonSignedRank.java`
- `backend/src/main/java/com/shopmind/evaluation/rtmp/statistics/RtmpStatisticalAnalyzer.java`

**修改（main）**

- `backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpComparison.java`（ComparisonEntry 新增 alpha/twoSided + Javadoc）
- `backend/src/main/java/com/shopmind/evaluation/rtmp/persistence/RtmpComparisonBuilder.java`（构造调用 + Javadoc）

**新增（test）**

- `backend/src/test/java/com/shopmind/evaluation/rtmp/statistics/RtmpB1Fixtures.java`
- `backend/src/test/java/com/shopmind/evaluation/rtmp/statistics/NormalDistributionTest.java`
- `backend/src/test/java/com/shopmind/evaluation/rtmp/statistics/McNemarExactTest.java`
- `backend/src/test/java/com/shopmind/evaluation/rtmp/statistics/WilcoxonSignedRankTest.java`
- `backend/src/test/java/com/shopmind/evaluation/rtmp/statistics/RtmpStatisticalAnalyzerTest.java`
