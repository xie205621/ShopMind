# Module: MCP Engine (工具调度与扩展引擎)

**Version**: v1.0 **State**: 📝 Draft -> [ ] Review -> [ ] Approved -> [ ] Implemented -> [ ] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:** This document is the single source of truth for implementing this module. Any AI-generated code (Claude/Cursor/Copilot) MUST strictly follow this specification. Do not invent unauthorized classes or bypass constraints.

## 1. Overview (模块概述)

MCP Engine 是连接“不可靠的 AI 推理大脑”与“绝对严谨的企业业务系统”之间的标准桥梁。它基于 Model Context Protocol 思想，将电商域的商品搜索、模拟支付、订单查询等能力抽象为标准化的 Tool（工具），实现 AI 侧与业务侧的物理/逻辑解耦。

**`MCP_Engine.md`（调度引擎）**：它是 AI 的“手和脚”（执行器官）。负责把 AI 想干的事情（比如“付款”），翻译成代码去调用真实的系统方法。

## 2. Business Requirement (业务需求)

- **业务插件化**：新增业务功能（如“查询优惠券”）时，只需在业务模块加注解即可，无需修改 Agent 核心流转代码。
- **平台复用性**：MCP Engine 必须是业务无关的。当前用于电商，未来可无缝迁移至金融、医疗等其他场景。

## 3. Functional Requirement (功能需求)

- **工具发现 (Tool Discovery)**：系统启动时，自动扫描并提取带有特定注解的方法，生成大模型可读的 JSON Schema 描述。
- **参数映射 (Parameter Binding)**：将 LLM 吐出的 JSON 字符串，精准反序列化并绑定到 Java 方法的强类型参数上。
- **工具路由与执行 (Routing & Execution)**：根据 LLM 的指令，通过反射机制安全调用对应的业务 Service。

## 4. Non-functional Requirement (性能要求)

- **低延迟**：工具的反射调用与参数映射过程耗时必须 `< 10ms`。
- **高吞吐**：执行引擎需支持高并发调用，不因单一工具阻塞拖垮整个 Agent 线程。

## 5. Responsibility (职责)

- **隔离边界**：绝对禁止 LLM 生成 SQL 或直接操作数据库。LLM 只能提交意图，由 MCP Engine 接管并触发对应的安全业务方法。
- **契约管理**：维护 AI 与业务线之间的 Input/Output Schema 契约。

## 6. Constraints (约束)

**必须实现 (MUST)：**

- [x] 必须使用自定义注解（如 `@McpTool`）来标记对外暴露的业务方法。
- [x] 必须在执行 Tool 之前，进行严格的参数类型校验。
- [x] 必须设定 Tool 执行的超时时间（默认 3000ms），防止长耗时业务拖死 AI 响应。

**绝对禁止 (MUST NOT)：**

- [ ] 禁止 Agent 核心代码直接 `@Autowired` 具体的业务 Service（如 `OrderService`）。
- [ ] 禁止盲目信任 LLM 传来的参数（例如：LLM 传来扣款 100 万，必须在业务层做权限和上限拦截，MCP 层只负责传参和抛出异常）。

## 7. Workflow & Sequence Diagram (流程与时序图)

Plaintext

```
User            Agent Engine        MCP Engine        Business Service
 │                   │                   │                   │
 │─1. 想要付款 ──────▶│                   │                   │
 │                   │─2. 规划为付款意图 ▶│                   │
 │                   │                   │                   │
 │                   │◀3. 返回 ToolSchema─│                   │
 │                   │                   │                   │
 │                   │─4. 传入 JSON 参数 ─▶│                   │
 │                   │                   │─5. 参数校验与反射 ─▶│
 │                   │                   │                   │─6. CAS乐观锁扣款
 │                   │                   │◀7. 返回成功/失败 ───│
 │                   │◀8. Observation ───│                   │
 │◀9. 流式回复成功 ───│                   │                   │
```

## 8. Data Model (数据模型)

**ToolRegistry Schema (内存缓存映射)** *注：工具注册信息常驻应用内存，不落盘。*

JSON

```
{
  "toolName": "confirmPayment",
  "description": "执行模拟付款，需提供订单号",
  "targetClass": "com.shopmind.business.OrderService",
  "targetMethod": "payOrder",
  "parameters": {
    "orderNo": {
      "type": "String",
      "required": true,
      "description": "18位订单编号"
    }
  }
}
```

## 9. API Design (接口设计)

*本模块为内部框架级组件，提供给 Agent Engine 调用的接口：*

Java

```
public interface McpEngine {
    // 发现并获取所有已注册的工具描述 (供大模型 Prompt 使用)
    List<ToolSpecification> discoverTools();

    // 执行具体的工具调用
    String executeTool(String toolName, String jsonArguments);
}
```

## 10. Class Design (核心类设计)

AI Coding 生成要求：

- **`@McpTool` (Annotation)**：包含 `name` 和 `description` 属性，用于标记方法。
- **`@McpParam` (Annotation)**：用于标记方法参数，提取参数描述供 LLM 理解。
- **`ToolRegistry` (Component)**：利用 Spring `BeanPostProcessor` 在系统启动阶段扫描带有 `@McpTool` 的 Bean，解析并缓存映射关系。
- **`McpExecutor` (Component)**：实现 `McpEngine` 接口，利用 Jackson 将 `jsonArguments` 转换为目标方法的参数数组，通过 `Method.invoke()` 执行，并捕获业务异常格式化返回。

## 11. Exception Handling (异常处理)

- **`ToolNotFoundException`**：LLM 幻觉生成了不存在的工具名。降级策略：向 LLM 返回 `"工具不存在，请重新规划"`。
- **`ParameterBindingException`**：LLM 生成的 JSON 缺少必填参数或类型错误。降级策略：向 LLM 返回 `"参数错误：缺少 orderNo，请向用户追问"`。
- **`BusinessExecutionException`**：业务方法抛出异常（如订单已取消无法付款）。降级策略：捕获该异常的 Message，原样作为 Observation 反哺给 LLM，让 LLM 向用户解释。

## 12. Test Plan (测试计划)

- **启动测试**：验证 `ToolRegistry` 是否能在 Spring Boot 启动时正确扫描到 Mock 业务类上的 `@McpTool` 注解。
- **异常回环测试**：故意传入非法 JSON 字符串，验证 `McpExecutor` 是否能稳健捕获并返回格式化的错误说明，而不导致主线程崩溃。

## 13. Acceptance Criteria (验收标准)

- [ ] Spring Boot 启动时无报错完成本地 Tool 扫描与注册。
- [ ] 成功拦截非法参数并返回规范错误 JSON。
- [ ] 模拟业务延时 5 秒，触发 MCP 层超时熔断机制成功。
- [ ] 新增一个业务模块（如积分系统），无需修改 MCP 代码即可被 Agent 发现。

## 14. Future Evolution (演进路线)

- 当前为 **Local MCP**（在同一个 JVM 内通过反射调用）。未来可演进为 **Remote MCP**：通过 HTTP/SSE 协议，调用部署在其他微服务节点上的工具，实现纯粹的分布式 AI 架构。