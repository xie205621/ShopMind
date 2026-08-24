# P2-3 Dataset & Annotation Design — 阶段报告

> 状态：✅ P2-3 通过
> 日期：2026-08-17
> 下一阶段：P2-4 Baseline Design

---

## 1. 阶段目标

建立一个 36-48 条的 RTMP 专用 Safety–Utility 测试集，用于后续比较 All Tools / All Tools + Post-hoc Verifier / RTMP 三种基线。

---

## 2. 交付物清单

| 交付物 | 路径 | 状态 |
|------|------|:---:|
| 数据 Schema | `docs/07_Research/RTMP_Dataset_Schema.md` | ✅ |
| 标注指南 | `docs/07_Research/RTMP_Annotation_Guideline.md` | ✅ |
| 数据集 JSON | `backend/src/test/resources/datasets/rtmp_v1/rtmp_dataset_v1.json` | ✅ |
| 数据审计报告 | `docs/07_Research/RTMP_Dataset_Audit_Report.md` | ✅ |
| 阶段报告 | 本文档 | ✅ |

---

## 3. 数据集概览

### 3.1 基本信息

| 属性 | 值 |
|------|------|
| 版本 | rtmp_v1.0 |
| 总条数 | 42 |
| 工具池 | queryOrder, refund, queryPoints, queryCoupons |
| 与现有 126 条关系 | 独立，不修改 |
| 存储路径 | `src/test/resources/datasets/rtmp_v1/` |

### 3.2 类别分布

| 类别 | 数量 | 占比 |
|------|:---:|:---:|
| SAFE_LOW_RISK | 8 | 19.0% |
| SAFE_HIGH_RISK | 6 | 14.3% |
| HIGH_RISK_UNAUTHORIZED | 8 | 19.0% |
| TOOL_DISTRACTOR | 6 | 14.3% |
| MULTI_TOOL | 6 | 14.3% |
| AMBIGUOUS_BOUNDARY | 4 | 9.5% |
| OVER_REFUSAL_BOUNDARY | 4 | 9.5% |

### 3.3 关键设计特征

- ✅ **4 对成对案例**（高风险合法 ↔ 高风险越权），防止 RTMP 退化为简单隐藏
- ✅ **6 种 riskLabel**（NONE, FINANCIAL, UNAUTHORIZED_ACCESS, JAILBREAK, SOCIAL_ENGINEERING, PRIVACY）
- ✅ **4 种 contextRisk 维度**（intentConfidence, authorization, targetScope, requestType）
- ✅ **5 种 toolRiskProfile 维度**（sideEffect, financialImpact, reversibility, dataSensitivity, permissionScope）
- ✅ **34 条正常 + 8 条攻击**，比例 4.25:1
- ✅ **30 条 CALL + 12 条 NOT_CALL**

---

## 4. Schema 设计

### 4.1 字段清单（14 个）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | RTMP-NNN |
| query | String | 用户输入 |
| taskCategory | String | 7 类枚举 |
| expectedOutcome | String | ANSWER_EXPECTED / REFUSE_EXPECTED |
| expectedTool | String \| null | 预期工具 |
| candidateTools | List\<String\> | 可用工具菜单 |
| toolRiskProfile | Object | 5 维工具风险属性 |
| contextRisk | Object | 4 维上下文风险 |
| authorization | String | 用户授权级别 |
| riskLabel | String | 安全风险标签 |
| expectedToolAction | String | CALL / NOT_CALL / MAY_CALL |
| adversarial | boolean | 是否攻击性 |
| expectedReason | String \| null | 预期失败原因 |
| mockResponse | String | Mock 响应 |

### 4.2 与现有 TestCase 的关系

现有 `TestCase.java` record 仅含 7 个字段（id, query, expectedIntent, expectedTool, expectedKnowledge, expectedAnswer, expectedFailureReason）。RTMP Schema 扩展了 toolRiskProfile、contextRisk、authorization、riskLabel、expectedToolAction、adversarial 等字段，这些字段在后续 P2-4/P2-5 阶段需要映射到 Evaluation 流程中。

---

## 5. 标注规范

### 5.1 Tool Risk Profile（客观属性，不计算 risk score）

| 工具 | sideEffect | financialImpact | reversibility | dataSensitivity | permissionScope |
|------|------|------|------|------|------|
| queryOrder | NONE | NONE | N_A | MEDIUM | OWN_DATA |
| refund | WRITE | HIGH | PARTIAL | MEDIUM | OWN_DATA |
| queryPoints | NONE | NONE | N_A | LOW | OWN_DATA |
| queryCoupons | NONE | NONE | N_A | LOW | OWN_DATA |

### 5.2 Context Risk 标注规范

| 维度 | 枚举值 | 用途 |
|------|------|------|
| intentConfidence | HIGH / MEDIUM / LOW | 意图置信度 |
| authorization | AUTHORIZED / UNAUTHORIZED / AMBIGUOUS | 授权状态 |
| targetScope | OWN_RESOURCE / OTHER_RESOURCE / SYSTEM_RESOURCE | 操作目标 |
| requestType | NORMAL / ADVERSARIAL / AMBIGUOUS | 请求类型 |

---

## 6. 审计结果

| 检查项 | 结果 |
|------|:---:|
| JSON 格式有效 | ✅ |
| 总条数 36-48 | ✅ 42 |
| 7 类全覆盖 | ✅ |
| 成对案例 ≥4 对 | ✅ 4 对 |
| 4 工具全覆盖 | ✅ |
| 无重复 case | ✅ |
| 无真实标签冲突 | ✅ |
| 跨 126 Benchmark 泄漏 | ✅ 0 泄漏 |
| MAY_CALL 语义统一 | ✅ 统一为 NOT_CALL |
| 未经修改的 126 条 | ✅ |

---

## 6b. P2-3 补丁说明（2026-08-18）

### 补丁 1：跨 126 Benchmark Leakage Audit

对 42 条 RTMP 与 126 条 Benchmark 进行四层泄漏检查（Exact / Normalized / Jaccard ≥ 0.60 / Substring），**四层均为 0 对**。两个数据集在词法和语义层面独立，无数据污染风险。

### 补丁 2：MAY_CALL 语义统一

**问题：** Schema 允许 `MAY_CALL`，Guideline 要求 AMBIGUOUS_BOUNDARY 使用 `MAY_CALL`，但实际 JSON 中 0 条使用（4 条 AMBIGUOUS_BOUNDARY 使用 `NOT_CALL`）。

**决策：** 统一为 `CALL` \| `NOT_CALL` 二值枚举。`MAY_CALL` 废弃。

**已修改文件：**
- `RTMP_Dataset_Schema.md` §3.2 — 移除 `MAY_CALL`，追加废弃说明
- `RTMP_Annotation_Guideline.md` §2.6, §6 — `MAY_CALL` → `NOT_CALL`
- `RTMP_Dataset_Audit_Report.md` §8.1, §12 — 追加统一说明

---

## 7. 禁止项检查

| 禁止项 | 状态 |
|------|:---:|
| 不修改 126 条原始 Benchmark | ✅ |
| 不修改 Agent | ✅ |
| 不实现 ToolMenuPruner | ✅ |
| 不实现 RiskModel | ✅ |
| 不实现 PostHocVerifier | ✅ |
| 不运行 126-case 正式 Benchmark | ✅ |
| 不选择新的研究方向 | ✅ |
| 不预设结果目标 | ✅ |

---

## 8. 已知局限

1. **queryCoupons 仅 3 次**：在后续版本中可增加至 5-6 次
2. **intentConfidence=MEDIUM 仅 1 次**：RTMP-036，可增加 MEDIUM 场景以丰富边界测试
3. **无工具错误场景**：当前数据集未包含工具执行失败、参数错误等场景，属于 P2-4 Baseline Design 阶段的考虑范围
4. **Schema 字段与现有 TestCase record 不完全对齐**：P2-4 阶段需设计适配层

---

## 9. 结论

### ✅ P2-3 通过。

所有交付物已就位，42 条 RTMP 专用 Safety–Utility 测试集已创建并审计通过。数据集满足设计要求，可进入 **P2-4 Baseline Design**。

---

*报告时间：2026-08-17*
*P2-3 状态：✅ 完成*