# ShopMind P0 工程基线最终验收报告

> P0-4 阶段：工程基线最终验收（真实性 / 核心链路 / 核心配置 / 安全 / 启动 / 测试 / 结论）
> 验收日期：2026-08-16

---

## 1. 当前系统真实定位

**ShopMind 是一个电商客服智能体（customer-service）的软件工程底座与可运行演示项目**，而非已接入真实交易系统的生产部署。

- 技术定位：基于 DDD 端口-适配器 + 六引擎微内核架构，Spring WebFlux 全链路异步非阻塞。
- 核心能力：版本化工作流编排、RAG 检索、MCP 工具调用、滑动窗口记忆、LLM-as-Judge 评测。
- 数据现状：业务数据为**内存态示例数据**（订单 `ORD20240722001` 等、会员 `USER1001` 等），未接真实订单/会员数据库。
- 目标定位：达到"可作为求职项目稳定展示"的工程基线——可运行、可演示、可评测、文档与代码一致。

## 2. 已完成能力

| 能力 | 说明 |
|------|------|
| SSE 对话 | `POST /api/chat` 真实流式，前端经 Vite 代理直连，无 Mock 层 |
| MCP 工具 | 4 个业务工具：`queryOrder` / `refund` / `queryPoints` / `queryCoupons`（示例数据） |
| RAG 检索 | 30 chunks 知识库 + 向量检索（qwen 真实 embedding / mock embedding） |
| 记忆 | MongoDB 滑动窗口（20 条） |
| 工作流 | 版本化 YAML DSL，customer-service v2.0–v2.3 + finance + sales + ablation，共 8 版本 |
| 评测 | Rule-Based + LLM-as-Judge，3 项实验（Matrix / Ablation / Retrieval） |
| LLM 切换 | Mock / DeepSeek / Qwen 三 profile 切换 |
| 前端 | React 聊天界面 + 评测看板 |

## 3. 已验证能力（P0-3 实测，qwen profile）

5 个真实场景全部通过：

| 场景 | 结果 |
|------|------|
| 普通知识问答 | ✅ |
| RAG 知识检索 | ✅（召回 `aftersales_001`，正确回答退货政策） |
| Tool Calling | ✅（`queryOrder` 返回订单状态） |
| 多轮 Memory | ✅（第二轮记住订单号，判定"不可退"） |
| RAG + Tool 联合 | ✅（订单状态 + 退货政策综合判断） |

详见 [P0-3_Acceptance_Report.md](../04_Evaluation/P0-3_Acceptance_Report.md)。

## 4. 当前限制

1. 工具返回硬编码示例数据，未接真实订单/会员数据库。
2. `deepseek` profile 下 embedding 仍为 Mock（SHA-256 哈希，非语义），RAG 检索不可用。
3. 真实 LLM 评测基线偏低：意图准确率 ~50%、任务成功率 ~10-18%（主因为"知识未找到-正确拒答" + 意图/工具选择错误）。
4. 前端无自动化测试。
5. HTTP 层（`POST /api/chat`）无 `@WebFluxTest` 直测。

## 5. 真实性风险

| 表述 | 判定 | 处理 |
|------|------|------|
| "真实 MCP 工具 / 真实业务工具" | ⚠️ 数据为示例，非生产数据 | 已改为"业务工具（示例数据）" |
| "生产级 / 企业级"（Enterprise_README 定位） | 定位性表述，指工程底座而非生产部署 | 保留，需按定位理解 |
| "全链路零幻觉" | ✅ 有支撑（Benchmark 幻觉率 0.0%） | 保留 |
| "Hit@3 = 100%" | ✅ 有支撑（RAG 检索评测） | 保留 |
| "Task Success 提升至 80%"（PROJECT_METRICS） | ⚠️ 来自消融"正常业务"子集，非全量 | 保留，标注为子集口径 |

## 6. 文档修正

- `README.md`：「MCP Tools（真实业务工具）」→「MCP Tools（业务工具）」（示例数据已在同节说明）。
- `Enterprise_README.md`：「4 个真实 MCP 工具」→「4 个 MCP 业务工具，数据为内存态示例」。
- `Enterprise_README.md`：「内置 4 个真实 @McpTool」→「内置 4 个 @McpTool 业务工具（数据为内存态示例业务数据）」。

## 7. 安全检查结果

| 检查项 | 结果 |
|--------|------|
| API Key 进入 Git | ✅ `.gitignore` 已忽略 `.env` / `.env.local` / `.env.*.local` |
| `.env` 误提交 | ✅ 仓库无 `.env` 文件 |
| 日志打印完整 Key | ✅ 脱敏（仅打印前 4-5 位 + `***`） |
| 前端暴露敏感配置 | ✅ 无硬编码 key / 敏感配置 |

## 8. 测试结果

| 测试项 | 结果 |
|--------|------|
| `mvn clean test` | ✅ 88 run / 0 fail / 0 error / 7 skip |
| 7 个 skip | 均为 `RealLlmBenchmarkTest`（需 deepseek profile + API key，预期跳过） |
| P0-3 真实场景 | ✅ 5/5 通过 |
| Benchmark | ✅ [benchmark_matrix_20260816-000221.md](../../reports/benchmark_matrix_20260816-000221.md)（deepseek-v4-flash，126 例，幻觉率 0%） |

三者记录一致：单元/集成测试全绿，P0-3 场景全通过，Benchmark 产出与 `PROJECT_METRICS` 的"零幻觉"结论一致。

## 9. 最终结论：是否建议冻结 P0

**✅ 建议冻结 P0。**

- 核心链路可运行、可演示、可评测，文档与代码基本一致（已修正"真实工具"措辞）。
- 无阻塞性代码问题；安全项全部通过。
- 剩余项（接真实数据、优化评测指标、前端测试、deepseek embedding）均为**后期改善项**，不阻塞 P0 冻结。

**P0 验收结论：通过。**
