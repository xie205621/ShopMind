# 全局架构设计 (Overall Architecture)

**文档版本**：v1.1 (v2.3 Evaluation Engine 更新) **核心目标**：定义 ShopMind Enterprise 的系统边界与分层架构，确保 AI 推理能力与底层电商业务逻辑的绝对解耦。

## 1. 系统逻辑拓扑图 (System Topology)

本系统采用经典的“接入 - AI 中枢 - 业务支撑 - 数据存储”四层架构。核心请求流转路径如下：

Plaintext

```
                    User
                      │
                      ▼
              Web(Vue/React)
                      │
                      ▼
          Spring Boot Gateway
                      │
                      ▼
              AI Orchestrator
                      │
      ┌───────────────┼───────────────┐
      ▼               ▼               ▼
   Memory         Planner         Knowledge
   (MongoDB)   (Workflow Engine)  (RAG Engine)
      │               │               │
      └───────────────┼───────────────┘
                      │
                      ▼
                Tool Registry (MCP)
                      │
      ┌───────────────┼───────────────┐
      ▼               ▼               ▼
 Search Tool    Order Tool     Payment Tool
                      │
                      ▼
              Business Service
                      │
      ┌───────────────┼───────────────┐
      ▼               ▼               ▼
    MySQL          Redis          Logs

              ════════════════════
              ▼ (离线评估层 v2.3)
        Evaluation Engine
  (Benchmark + LLM-as-Judge)
```

## 2. 核心分层架构说明 (Layer Breakdown)

### 2.1 接入层 (Access Layer)

- **Web (Vue/React)**：前端用户界面，通过 Server-Sent Events (SSE) 协议与后端建立长连接，负责流式渲染 AI 输出（打字机效果）。
- **Spring Boot Gateway**：系统的统一流量入口。负责安全鉴权、多租户身份提取（UserId/SessionId 绑定）以及应对高并发请求的响应式限流，随后将结构化请求下发至 AI 中枢。

### 2.2 AI 中枢层 (AI Core Orchestrator)

这是整个 ShopMind 的“大脑”，负责意图解析与任务调度。大模型 API 自身是无状态的，所有的状态维护均在此层完成：

- **Memory (上下文记忆组件)**：利用 MongoDB 维护 Session 级滑动窗口记忆与 Long-term 画像。保障大模型不会“失忆”。
- **Planner (任务规划与决策)**：依赖底层的 Prompt Engine（提示词引擎），动态组装系统提示词、用户历史记忆与当前输入，送入 LLM 进行意图识别，规划出下一步需要调用的业务链路。
- **Knowledge (知识增强检索)**：结合 Vector Store（向量数据库），在 LLM 回答售后、促销规则前，强制召回本地准确知识片段，彻底消除模型幻觉。

### 2.3 桥接集成层 (Integration Layer)

- **Tool Registry (标准化工具注册中心)**：基于 MCP (Model Context Protocol) 架构思想设计。AI 不直接操作数据库，而是将生成的意图参数（如 `{"orderId": "1001"}`）传递给 Tool Registry。该层负责将自然语言生成的 JSON 参数映射为标准 Java 方法调用（如 `Search Tool`, `Order Tool`）。

### 2.4 业务与数据支撑层 (Business & Data Layer)

- **Business Service**：纯正的电商底层业务逻辑。包含商品防超卖扣减、并发订单流转状态机（基于 CAS 乐观锁）。这层对“上面调用它的是人还是 AI”完全无感，具备极高的独立性。
- **基础设施 (Infrastructure)**：MySQL 承担具有强事务一致性要求的核心业务数据；Redis 承担高频热点缓存与延迟队列流转任务；Logs 承担完整的全链路系统监控与 AI 评估日志落盘。

## 3. 架构设计哲学 (Architecture Philosophy)

1. **AI 与业务绝对解耦**：大模型（LLM）随时可能发生幻觉或生成错误参数。因此，AI 只能拥有“建议权”与“参数组装权”，真正的“执行权”（扣款、改库存）死死锁在 Business Service 层，由严格的强类型校验和事务锁把控。
2. **无状态化流式流转**：AI Orchestrator 层通过响应式编程（WebFlux）打通从大模型推理到前端显示的流式数据管道，避免长时间阻塞等待导致 Tomcat 线程池耗尽。