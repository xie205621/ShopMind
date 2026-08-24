# ShopMind P1-2 稳定性与异常处理验收报告

> P1-2 阶段：稳定性与异常处理验收（Timeout / Retry / Circuit Breaker / Fallback / SSE）
> 验收日期：2026-08-16

---

## 0. 结论摘要

**P1-2 通过。**

- 全量测试：`Tests run: 98, Failures: 0, Errors: 0, Skipped: 7`，`BUILD SUCCESS`（7 个 skipped 为 `RealLlmBenchmarkTest`，被条件注解默认禁用）。
- 9 个异常场景全部通过，未为通过测试而改动业务逻辑。
- 发现并修复 **3 个阻塞性稳定性 Bug**（均为必要最小修复，见第 2 节）。

---

## 1. 验收范围与原则

本阶段禁止新增业务功能、禁止重构核心 Agent 架构。仅执行：

1. 验证现有 Timeout / Retry / Circuit Breaker / Fallback；
2. 修复发现的阻塞性稳定性 Bug；
3. 为现有稳定性机制补充自动化测试。

---

## 2. 发现并修复的阻塞性 Bug

| # | Bug | 影响场景 | 严重性 |
|---|-----|---------|--------|
| B1 | `executeWithToolLoop`（outer loop）绕过了带 `.timeout()` 的 `callLlm()`，直接裸调 `chatModelPort.stream()`，导致 LLM 无响应时**永不超时、请求无限挂起** | 场景 1、8、9 | 阻塞 |
| B2 | Resilience4j 未显式配置 `minimumNumberOfCalls`（默认 100），低流量下 Circuit Breaker 永不进入 OPEN | 场景 7 | 阻塞 |
| B3 | `retryWhen` 重试用尽后抛出 `RetryExhaustedException`（包装原始 `LlmProviderTimeoutException`），`degradeEvents` 未沿 cause 链识别，导致超时被误判为"意外错误"（`LLM_ERROR`） | 场景 1、8、9 | 阻塞 |

---

## 3. 场景验证明细

> 测试输入 / 触发方式 / 预期行为 / 实际行为 / 日志证据 / 是否通过 / 是否修改生产代码 / 新增或修改测试

### 场景 1：LLM 超时

| 项 | 内容 |
|----|------|
| 测试输入 | `OrchestrationRequest("stability_hang", "你好")` |
| 触发方式 | mock `ChatModelPort.stream` 返回 `Flux.never()`（模拟 LLM 无响应） |
| 预期行为 | 300ms 超时 → 重试 2 次 → 降级为 `Error` 事件并正常结束，不无限等待 |
| 实际行为 | 按预期超时降级，`verifyComplete()` 正常结束，未挂起 |
| 日志证据 | 代码日志点：`degradeEvents` 中 `log.error("[Orchestrator] LLM provider timeout.", error)`；StepVerifier 断言通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | **是**（B1：`executeWithToolLoop` 复用 `callLlm()` 补上 `.timeout()`） |
| 新增/修改测试 | 新增 `OrchestratorStabilityTest.llmHangsThenTimeoutDegrades` |

### 场景 2：LLM API 异常

| 项 | 内容 |
|----|------|
| 测试输入 | `OrchestrationRequest("stability_api_error", "你好")` |
| 触发方式 | mock `ChatModelPort.stream` 返回 `Flux.error(new RuntimeException("simulated 500"))` |
| 预期行为 | 不重试，直接降级为 `Error[code=LLM_ERROR]` 并正常结束 |
| 实际行为 | 按预期降级，订阅 1 次 |
| 日志证据 | 代码日志点：`degradeEvents` 中 `log.error("[Orchestrator] Unexpected error during orchestration.", error)` |
| 是否通过 | ✅ |
| 是否修改生产代码 | 否（既有 `onErrorResume` 降级已正确工作） |
| 新增/修改测试 | 新增 `OrchestratorStabilityTest.llmApiExceptionDegradesWithoutRetry` |

### 场景 3：Tool 超时

| 项 | 内容 |
|----|------|
| 测试输入 | `mcpEngine.executeTool("slowTask", "{}")` |
| 触发方式 | `slowTask` 测试工具模拟 5 秒延时 |
| 预期行为 | 3000ms 超时 → 返回"工具执行超时，请稍后重试"，4000ms 内返回，不阻塞主链 |
| 实际行为 | 按预期降级，`elapsed < 4000ms` |
| 日志证据 | 测试断言 `elapsed < 4000` 通过 + 返回降级文案 |
| 是否通过 | ✅ |
| 是否修改生产代码 | 否（既有 Tool 超时机制已正确工作） |
| 新增/修改测试 | 复用既有 `McpEngineTest.shouldTimeoutAndReturnDegradedMessage` |

### 场景 4：Tool 执行异常

| 项 | 内容 |
|----|------|
| 测试输入 | ① 不存在工具 `ghostTool` ② 缺参数 `confirmPayment(orderNo)` ③ 非法 JSON ④ 业务异常 `amount=99999` |
| 触发方式 | 通过 `McpEngine.executeTool` 注入各类异常 |
| 预期行为 | 返回明确降级提示，不抛出导致 SSE 崩溃的异常 |
| 实际行为 | 分别返回"工具不存在，请重新规划"/"参数错误…"/"参数格式错误"/"不能超过10000元" |
| 日志证据 | 4 个 `ExceptionLoopbackTests` 断言通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | 否 |
| 新增/修改测试 | 复用既有 `McpEngineTest.ExceptionLoopbackTests`（4 个用例） |

### 场景 5：RAG 异常

| 项 | 内容 |
|----|------|
| 测试输入 | `QueryRequest(query="退货政策", topK=3, threshold=0.7)` |
| 触发方式 | mock `EmbeddingProviderPort.embed` 抛 `EmbeddingTimeoutException`；mock `VectorStorePort.search` 抛 `VectorStoreConnectionException` |
| 预期行为 | 降级为空上下文，不抛异常中断主链路 |
| 实际行为 | `hasResults()==false`，`chunks` 为空 |
| 日志证据 | 测试断言 `ctx.hasResults()==false` 通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | 否（既有 `RetrievalPipeline` 降级已正确工作） |
| 新增/修改测试 | 新增 `RetrievalPipelineDegradationTest`（2 个用例） |

### 场景 6：MongoDB / Memory 异常

| 项 | 内容 |
|----|------|
| 测试输入 | `OrchestrationContext("mem_fail_001", "你好")` |
| 触发方式 | mock `ChatMemoryStore.getMessages` 抛 `RuntimeException("simulated MongoDB failure")` |
| 预期行为 | 降级为空历史，主链路继续运行 |
| 实际行为 | `history` 为空，链路不中断 |
| 日志证据 | 实测日志：`[Orchestrator] Memory load failed for memoryId=mem_fail_001, continue with empty history. Error: simulated MongoDB failure` + `Context hydrated: 0 history msgs, 0 knowledge chunks (memory=3ms, rag=1ms)` |
| 是否通过 | ✅ |
| 是否修改生产代码 | 否 |
| 新增/修改测试 | 新增 `ContextHydrationDegradationTest`（1 个用例） |

### 场景 7：Circuit Breaker

| 项 | 内容 |
|----|------|
| 测试输入 | 连续 6 次 `OrchestrationRequest("stability_cb_" + i, "你好")` |
| 触发方式 | mock `ChatModelPort.stream` 返回 `Flux.error(500)`，循环 6 次 |
| 预期行为 | `minimumNumberOfCalls=5` + `failureRate=50%` → 连续失败后进入 OPEN |
| 实际行为 | `CircuitBreaker.State == OPEN` |
| 日志证据 | `assertThat(state).isEqualTo(OPEN)` 通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | **是**（B2：`application.yml` 新增 `minimum-number-of-calls: 5`） |
| 新增/修改测试 | 新增 `OrchestratorStabilityTest.circuitBreakerOpensAfterConsecutiveFailures` |

### 场景 8：Retry

| 项 | 内容 |
|----|------|
| 测试输入 | ① 超时异常 ② 非超时异常（RuntimeException 500） |
| 触发方式 | mock 分别返回 `Flux.error(LlmProviderTimeoutException)` / `Flux.error(RuntimeException)`，用 `Flux.defer` + 计数器统计订阅次数 |
| 预期行为 | 只对 `LlmProviderTimeoutException` 重试（3 次订阅），不对 4xx/5xx 重试（1 次订阅） |
| 实际行为 | 超时场景订阅 3 次，非超时场景订阅 1 次 |
| 日志证据 | `assertThat(attempts.get()).isEqualTo(3)` / `isEqualTo(1)` 通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | **是**（B3：`degradeEvents` 沿 cause 链识别超时；B1 使 `callLlm` 的 retry 链路完整生效） |
| 新增/修改测试 | 新增 `llmTimeoutExceptionRetriesThenDegrades` + `llmApiExceptionDegradesWithoutRetry` |

### 场景 9：SSE 行为

| 项 | 内容 |
|----|------|
| 测试输入 | 上述所有异常场景的流 |
| 触发方式 | 各场景 `StepVerifier` 断言 `expectNext(Intent) → expectNext(Error) → verifyComplete()` |
| 预期行为 | 异常时发送明确 `Error` 事件并正常 `complete`，不无限挂起 |
| 实际行为 | 所有场景 `verifyComplete()` 正常结束 |
| 日志证据 | 各场景 StepVerifier 断言通过 |
| 是否通过 | ✅ |
| 是否修改生产代码 | **是**（B3：`degradeEvents` 正确产出 `Error` 事件并结束流） |
| 新增/修改测试 | 见场景 1、2 的 StepVerifier 断言 |

---

## 4. 生产代码修改清单（Change Log）

### 修改 1：`ShopAgentOrchestrator.java` — 统一 LLM 调用保护链

- **修改原因**：修复 B1。
- **原实现问题**：`executeWithToolLoop` 裸调 `chatModelPort.stream()`，漏掉 `.timeout()`，LLM 无响应时请求无限挂起。
- **新实现**：`executeWithToolLoop` 改为复用 `callLlm(messages, tools)`（内含 `timeout → onErrorMap → CircuitBreakerOperator → retryWhen`）。
- **技术原理**：Reactor `.timeout()` 超时抛 `TimeoutException`，经 `onErrorMap` 映射为 `LlmProviderTimeoutException`；`timeout` 位于 CircuitBreaker 之前，使超时也计入熔断失败统计。
- **影响范围**：所有 profile 的 outer loop LLM 调用路径。

### 修改 2：`ShopAgentOrchestrator.java` — 沿 cause 链识别超时

- **修改原因**：修复 B3。
- **原实现问题**：`degradeEvents` 仅判断 `error instanceof LlmProviderTimeoutException`，无法识别被 `RetryExhaustedException` 包装的超时。
- **新实现**：新增 `isLlmTimeout(Throwable)`，沿 `getCause()` 链识别 `LlmProviderTimeoutException`。
- **技术原理**：Reactor `retryWhen` 重试用尽后抛出 `RetryExhaustedException`（`cause` 为原始异常），需遍历 cause 链恢复原始语义。
- **影响范围**：LLM 超时的降级分类（正确归为 `TIMEOUT` 而非 `LLM_ERROR`）。

### 修改 3：`application.yml` — 补充超时与熔断配置

- **修改原因**：修复 B2，并为 B1 提供超时阈值配置。
- **原实现问题**：无 `shopmind.llm.timeout-ms`；Resilience4j 未配置 `minimumNumberOfCalls`（默认 100）。
- **新实现**：新增 `shopmind.llm.timeout-ms: 30000`；circuitbreaker default 新增 `minimum-number-of-calls: 5`。
- **技术原理**：`minimumNumberOfCalls` 达到后才评估 failure-rate，避免低流量下熔断永不触发。
- **影响范围**：LLM 超时阈值、熔断灵敏度。

---

## 5. 测试代码新增/修改清单

| 文件 | 用例数 | 覆盖场景 |
|------|--------|---------|
| `OrchestratorStabilityTest.java`（新增） | 4 | 场景 1、2、7、8、9 |
| `RetrievalPipelineDegradationTest.java`（新增） | 2 | 场景 5 |
| `ContextHydrationDegradationTest.java`（新增） | 1 | 场景 6 |

> 场景 3、4 由既有 `McpEngineTest` 覆盖，未重复新增。

---

## 6. 测试结果汇总

| 测试 | 结果 |
|------|------|
| `OrchestratorStabilityTest` | 4 run / 0 fail（28.5s） |
| `RetrievalPipelineDegradationTest` | 2 run / 0 fail |
| `ContextHydrationDegradationTest` | 1 run / 0 fail |
| 全量 `mvn test` | **98 run / 0 fail / 0 error / 7 skipped**，`BUILD SUCCESS`（6:51） |

> 7 个 skipped 为 `RealLlmBenchmarkTest`（真实 LLM 基准，被 `@EnabledIfSystemProperty` 条件禁用，非失败）。

---

## 7. 最终结论

P1-2 稳定性与异常处理验收 **通过**。

- 9 个异常场景（LLM 超时 / LLM API 异常 / Tool 超时 / Tool 异常 / RAG 异常 / Memory 异常 / Circuit Breaker / Retry / SSE 行为）全部验证通过。
- 发现并修复 3 个阻塞性稳定性 Bug，均为"不改业务逻辑、只补稳定性机制"的必要最小修复。
- 全量测试 98 run / 0 fail，未破坏既有能力。
