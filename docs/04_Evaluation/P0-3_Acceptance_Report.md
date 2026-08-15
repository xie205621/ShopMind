# ShopMind P0-3 验收报告

> 真实模型全链路验收：POST /api/chat SSE + 真实 LLM + 真实 RAG + 真实 Tool + 多轮 Memory
> 验收日期：2026-08-16

---

## 1. 环境

| 项 | 值 |
|----|----|
| 操作系统 | Windows |
| 后端 | Java 17 + Spring Boot 3.2.5 + Maven |
| 前端 | React + TypeScript + Vite + Zustand |
| 数据库 | MongoDB（`localhost:27017/shopmind`） |
| 后端端口 | 8080（SSE 端点 `POST /api/chat`） |
| 前端端口 | 5173（Vite dev server，`/api` 代理到 8080） |
| 知识库 | 30 chunks（`knowledge/knowledge-base.json`） |

## 2. 使用模型及 profile

| 项 | 值 |
|----|----|
| Spring Profile | `qwen` |
| 对话模型（LLM） | `qwen-plus`（阿里云 DashScope） |
| Embedding 模型 | `text-embedding-v3`（阿里云 DashScope，真实语义向量） |
| API Key | `QWEN_API_KEY` 环境变量注入 |

> 说明：P0-3 必须使用 `qwen` profile，因为只有 DashScope 同时提供对话与 Embedding 两个模型。
> `deepseek` profile 仅提供文本生成，无 Embedding 接口，RAG 检索仍走 Mock（SHA-256 哈希，非语义），无法通过场景 2/5。

## 3. 模型参数

| 参数 | 值 | 来源 |
|------|----|----|
| `result_format` | `message` | DashScopeChatAdapter |
| `incremental_output` | `true`（流式） | DashScopeChatAdapter |
| SSE 启用 | `X-DashScope-SSE: enable` header | DashScopeChatAdapter |
| `temperature` | 未显式设置（DashScope 默认） | DashScopeChatAdapter |
| RAG `topK` | 3 | RetrievalPipeline 日志 |
| RAG `threshold` | 0.7 | RetrievalPipeline 日志 |
| 工具迭代上限 | 3（`max-iterations`） | application.yml |
| 记忆滑动窗口 | 20 条（`max-messages`） | application.yml |
| MCP 工具超时 | 3000ms | application.yml |
| 熔断（llmProvider） | sliding-window 5 / wait 15s | application.yml |
| 限流（llmRateLimiter） | 30 次/分钟 | application.yml |

## 4. 启动命令

后端（终端 1）：
```powershell
$env:QWEN_API_KEY="sk-你的key"
cd d:\A_big\ShopMind\backend
mvn spring-boot:run "-Dspring-boot.run.profiles=qwen"
```

前端（终端 2）：
```powershell
cd d:\A_big\ShopMind\frontend
npm run dev
```

浏览器打开 http://localhost:5173

## 5. 五个测试场景（请求 → 调用链 → 结果）

### 场景 1：普通知识问答

- **请求**：`POST /api/chat` `{"query":"你能帮我做什么","memoryId":"session_1786810570339"}`
- **调用链**：
  1. 意图识别：`[Orchestrator] Intent: knowledge=true, tools=true, category=知识与工具`
  2. RAG 检索：`Retrieving for query='你能帮我做什么', topK=3, threshold=0.7` → `hits=3/3, latency=344ms`
  3. 上下文组装：`Context hydrated: 15 history msgs, 3 knowledge chunks`
  4. LLM 流式生成（无工具调用）
  5. Memory 回写：`Upserted 16 messages`
- **结果**：✅ 通过。AI 介绍能力（查订单/退款/积分/优惠券/常见问题），50 tokens。
- **备注**：意图被识别为「知识与工具」，实际无需工具，属轻微意图过宽，不影响正确性。

### 场景 2：RAG 知识检索

- **请求**：`{"query":"裤子的退货政策是什么？","memoryId":"session_1786810570339"}`
- **调用链**：
  1. 意图识别：`knowledge=true, tools=true`
  2. RAG 检索：`Retrieving for query='裤子的退货政策是什么？'` → `hits=3/3, latency=305ms`
  3. 上下文组装：`17 history msgs, 3 knowledge chunks`
  4. LLM 流式生成（基于知识库，无工具调用）
- **结果**：✅ 通过。召回 `aftersales_001`，回答「7 天内无理由退货 / 15 天内换货 / 包运费」，35 tokens。

### 场景 3：Tool Calling

- **请求**：`{"query":"帮我查订单 ORD20240722001","memoryId":"session_1786810570339"}`
- **调用链**：
  1. 意图识别：`knowledge=false, tools=true, category=工具执行`
  2. RAG 检索：`hits=3/3`
  3. 上下文组装：`19 history msgs, 3 knowledge chunks`
  4. LLM 发出 `queryOrder` 工具调用（`[DashScope] Tool call: queryOrder args={"orderId":"ORD20240722001"}`）
  5. 工具执行：`queryOrder 6ms`
  6. 工具结果回喂 LLM，生成最终回答
- **结果**：✅ 通过。返回「已发货 / 顺丰 SF1234567890 / 运输中 / 299.00 元」，25 tokens。

### 场景 4：多轮 Memory

- **第一轮请求**：`{"query":"我的订单号是 ORD20240722001","memoryId":"session_1786810570339"}`
  - 调用链：意图 `tools=true` → 上下文 `20 history msgs` → LLM 确认订单 → Memory 滑动窗口 `21 -> 20`
  - 结果：✅ 确认订单号并说明状态。
- **第二轮请求**：`{"query":"那它现在能退吗？","memoryId":"session_1786810570339"}`（未重复订单号）
  - 调用链：意图 `tools=true` → RAG 检索 `'那它现在能退吗？' hits=3/3` → 上下文 `20 history msgs, 3 knowledge chunks` → LLM 读取记忆中的订单号
  - 结果：✅ 通过。记住订单号 `ORD20240722001`，回答「不能，尚未签收，签收后 7 天内可退」，59 tokens。
- **验证点**：两轮 `memoryId` 均为 `session_1786810570339`，Memory 回写 + 滑动窗口（20 条）正常工作。

### 场景 5：RAG + Tool 联合任务

- **请求**：`{"query":"我的订单 ORD20240722001 还能退货吗？","memoryId":"session_1786810570339"}`
- **调用链**：
  1. 意图识别：`knowledge=true, tools=true, category=知识与工具`
  2. RAG 检索：`hits=3/3, latency=210ms`
  3. 上下文组装：`20 history msgs, 3 knowledge chunks`
  4. LLM 结合订单状态（Tool）与退货政策（RAG）综合判断
- **结果**：✅ 通过。综合「已发货未签收 + 退货需签收后 7 天内」给出正确结论，64 tokens。

## 6. 失败项

本轮 5 个场景 **无失败项**，全部通过。

## 7. 已修复问题（P0 修复链）

| # | 问题 | 修复 |
|---|------|------|
| 1 | `spring-boot:run` 启动失败（pom.xml 多余 `<version>`） | 移除 spring-boot-maven-plugin exclude 中的 version |
| 2 | 前端 `vite.config.ts` 报 `node:url` 类型错误 | package.json 增加 `@types/node` |
| 3 | 测试 Mock 工具 `queryOrder` 与生产工具重名 | 改名 `mockQueryOrder` |
| 4 | McpEngineTest / ShopAgentOrchestratorTest 断言过时 | 更新工具数/断言 |
| 5 | AI 最终回复不回写 Memory、用户消息重复、工具结果类型错误 | 重构 Memory 回写（AiMessage/SystemMessage） |
| 6 | Benchmark_Report.md 残留 80 chunks | 对齐 30 chunks |
| 7 | qwen 下无回复（SSE 解析缺按行拆分） | 补 `flatMap(chunk -> chunk.lines())` |
| 8 | 工具参数截断（tool_calls 未跨 chunk 累积） | 新增 ThreadLocal 累积 + finish_reason 触发 |
| 9 | 工具名 `_queryOrder`（off-by-one） | `substring(7)` → `substring(8)` |
| 10 | v2.3 CoT 步骤标签泄露到回复 | persona 增加「禁止输出推理过程」约束 |

## 8. 当前剩余问题（后期改善项，不阻塞 P0）

1. **工具数据为硬编码 mock**：`queryOrder/queryPoints/queryCoupons/refund` 返回写死的业务数据（`ORD20240722001`、`USER1001` 等），未接真实订单/会员数据库。
2. **DeepSeek profile 下 RAG 不可用**：DeepSeek 无 Embedding 接口，embedding 仍是 SHA-256 哈希伪向量；如需真实 RAG 必须 qwen 或接入第三方 embedding。
3. **真实 LLM 评测基线偏低**：`benchmark_matrix_20260816-000221.md` 显示意图准确率 ~50%、任务成功率 ~10-18%（主因是「知识未找到-正确拒答」+ 意图/工具选择错误），需后期优化 prompt。
4. **前端无自动化测试**：package.json 无 vitest/jest。
5. **HTTP 层无直测**：`POST /api/chat` 无 `@WebFluxTest` 覆盖。

## 9. 最终结论：P0-3 是否通过

**✅ 通过。**

在 `qwen` profile + 有效 `QWEN_API_KEY` + 本地 MongoDB 环境下，以下 P0-3 目标全部达成：

- `POST /api/chat` SSE 真连接 ✅
- 前端 Mock 删除、真实后端接入 ✅
- 真实 LLM 流式回答（qwen-plus）✅
- 真实 RAG 检索（DashScope text-embedding-v3）✅
- 真实 Tool Calling（≥ 2 个生产工具，实际 4 个）✅
- 多轮 Memory（MongoDB + 滑动窗口）✅
- RAG + Tool 联合任务 ✅

**前置条件**：P0-3 通过的前提是 `qwen` profile（真实 embedding）。`deepseek` profile 因无 embedding 接口，仅能验证 Tool + Memory，RAG 场景（2/5）不适用。
