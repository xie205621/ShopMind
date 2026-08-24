# ShopMind P1-3 HTTP / Integration Test 验收报告

> P1-3 阶段：HTTP 层自动化验证（`POST /api/chat`）
> 验收日期：2026-08-16

---

## 0. 结论摘要

**P1-3 通过。**

- 新增 HTTP 层测试 `ChatControllerHttpTest`：4 run / 0 fail。
- 全量测试：`Tests run: 102, Failures: 0, Errors: 0, Skipped: 7`，`BUILD SUCCESS`（7 个 skipped 为 `RealLlmBenchmarkTest`，被条件注解默认禁用）。
- 发现并修复 1 个 HTTP 层异常兜底缺失（必要最小修复）。

---

## 1. 测试技术方案

| 项 | 选择 | 理由 |
|----|------|------|
| 测试切片 | `@WebFluxTest(ChatController.class)` | 只加载 Controller 层，不启动完整 Spring 上下文 / Embedded MongoDB，聚焦 HTTP 层 |
| Orchestrator 隔离 | `@MockBean ChatStreamingPort` | 模拟 Orchestrator 返回事件，明确区分 HTTP 层职责与 Orchestrator 职责 |
| HTTP 客户端 | `WebTestClient` | Spring Boot 自带（`spring-boot-starter-webflux`），未新增任何第三方测试框架 |
| 流断言 | `StepVerifier`（reactor-test） | 项目既有依赖，断言 SSE 事件序列与流完整结束 |

**被测端点分析**

| 项 | 现状 |
|----|------|
| 端点 | `POST /api/chat`，`produces = text/event-stream` |
| 请求 DTO | `ChatApiRequest(memoryId, query)`（record，无 Bean Validation 注解） |
| 返回类型 | `Flux<ServerSentEvent<ChatStreamEvent>>` |
| 参数校验 | 手动：`query` 为 null/blank → 返回 `Error[LLM_ERROR]` SSE 事件（HTTP 200，非 4xx） |

> 说明：当前参数校验采用"手动校验 + SSE error 事件"而非 `@Valid` + HTTP 400，属于既有设计（SSE 场景下用事件而非状态码统一处理错误），本阶段记录现状、不改变该约定。

---

## 2. 场景验证明细

### Case A：正常请求

| 项 | 内容 |
|----|------|
| 请求 | `POST /api/chat`，body `{"memoryId":"mem_001","query":"你好"}`，`Content-Type: application/json` |
| 触发方式 | mock `ChatStreamingPort.stream` 返回 `Flux[Intent, Token, Done]` |
| 预期行为 | HTTP 2xx；Content-Type 为 SSE；至少收到 Intent / Token / Done |
| 实际行为 | 按预期返回 |
| HTTP status | **200** |
| SSE 事件 | `Intent("闲聊")` → `Token("你好")` → `Done` |
| 是否通过 | ✅ |

### Case B：请求参数异常

| 项 | 内容 |
|----|------|
| 请求 | `POST /api/chat`，body `{"memoryId":"mem_001","query":""}`（query 为空） |
| 触发方式 | 直接发送空 query |
| 预期行为 | 返回 SSE error 事件（既有设计：HTTP 200 + error 事件，非 4xx） |
| 实际行为 | 按预期返回 `Error[LLM_ERROR]` |
| HTTP status | **200** |
| SSE 事件 | `Error(code=LLM_ERROR, "消息内容不能为空。")` |
| 是否通过 | ✅ |

### Case C：Agent 内部异常

| 项 | 内容 |
|----|------|
| 请求 | `POST /api/chat`，body `{"memoryId":"mem_001","query":"你好"}` |
| 触发方式 | mock `ChatStreamingPort.stream` 返回 `Flux.error(RuntimeException("boom"))` |
| 预期行为 | Controller 兜底返回 error event，SSE 正常结束，不无限挂起 |
| 实际行为 | 按预期降级为 `Error[LLM_ERROR]` 并正常结束 |
| HTTP status | **200** |
| SSE 事件 | `Error(code=LLM_ERROR, "系统遇到了一个意外错误，请稍后重试。")` |
| 是否通过 | ✅ |
| 是否修改生产代码 | **是**（见第 3 节） |

### Case D：SSE 完整结束

| 项 | 内容 |
|----|------|
| 请求 | `POST /api/chat`，body `{"memoryId":"mem_001","query":"你好"}` |
| 触发方式 | mock `ChatStreamingPort.stream` 返回 `Flux[Intent, Done]` |
| 预期行为 | 正常请求最终收到 Done，HTTP 连接正常结束 |
| 实际行为 | 流在 Done 后 `complete`，`verifyComplete()` 通过 |
| HTTP status | **200** |
| SSE 事件 | `Intent` → `Done` → `complete` |
| 是否通过 | ✅ |

---

## 3. 修改的生产代码（Change Log）

**修改文件**：`ChatController.java`

- **修改原因**：Case C 要求"Agent 内部异常 → Controller 返回 error event，SSE 正常结束"。原 Controller 对 `chatStreamingPort.stream()` 抛出的异常无兜底。
- **原实现问题**：`Flux` 若 error 会直接传播到 WebFlux 框架层 → HTTP 500、连接异常中断，而非约定的 SSE error 事件。
- **新实现**：追加 `.onErrorResume(e -> Flux.just(Error[LLM_ERROR]))`，把任何未捕获异常降级为 SSE error 事件并正常结束；同时新增 `Logger` 记录兜底日志。
- **技术原理**：Reactor `onErrorResume` 作为 HTTP 边界兜底，保证 SSE 永远以事件（而非框架 500）收尾；正常降级路径（orchestrator 内部已 `degradeEvents`）不受影响。
- **影响范围**：仅 `POST /api/chat` 异常兜底路径，不改任何业务逻辑。
- **测试方式**：`ChatControllerHttpTest` Case C。

---

## 4. 新增测试

| 文件 | 用例 | 覆盖 |
|------|------|------|
| `ChatControllerHttpTest.java` | 4 | Case A（正常）/ B（参数异常）/ C（内部异常）/ D（SSE 完整结束） |

---

## 5. 测试结果

| 测试 | 结果 |
|------|------|
| `ChatControllerHttpTest` | 4 run / 0 fail（3.1s） |
| 全量 `mvn clean test` | **102 run / 0 fail / 0 error / 7 skipped**，`BUILD SUCCESS`（6:57） |

> 7 个 skipped 为 `RealLlmBenchmarkTest`（真实 LLM 基准，被 `@EnabledIfSystemProperty` 条件禁用，非失败）。

---

## 6. 最终结论

P1-3 HTTP / Integration Test 验收 **通过**。

- 4 个 HTTP 场景（正常请求 / 参数异常 / 内部异常 / SSE 完整结束）全部验证通过。
- 使用项目既有测试框架（`@WebFluxTest` + `WebTestClient` + `StepVerifier`），未新增第三方框架。
- 发现并修复 1 个 HTTP 层异常兜底缺失，为"不改业务逻辑、只补 HTTP 边界"的必要最小修复。
- 全量测试 102 run / 0 fail，未破坏既有能力。
