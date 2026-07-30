# Module: Session Memory Engine (短期会话记忆引擎)

**Version**: v1.0

**Status**: 📝 Draft -> [x] Review -> [ ] Approved -> [ ] Implemented -> [ ] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:** This document is the single source of truth for implementing the Session Memory module. Any AI-generated code MUST strictly follow this specification. Do not invent unauthorized classes or bypass constraints.

## 1. Overview (模块概述)

本模块为 AI Platform 提供高性能、多租户隔离的短期上下文记忆管理机制。它负责解决 LLM (大语言模型) 无状态调用的问题，确保多轮对话中的指代消解与语境连贯。

**`Session_Memory.md`（记忆引擎）**：它是 AI 的“海马体”（大脑记忆）。负责记住用户前几轮说了什么。没有它，AI 聊到第二句就会忘记第一句。

## 2. Business Requirement (业务需求)

- **上下文继承**：用户在多轮对话中（如“推荐一款手机” -> “预算3000以内”），系统必须能够记住前置条件。
- **成本与计费控制**：必须严格控制每次发给大模型的历史对话长度，防止 Token 消耗无上限增长。

## 3. Functional Requirement (功能需求)

- **历史恢复**：根据用户标识准确提取其最近的历史对话列表。
- **滑动窗口**：当对话轮数超过设定阈值（默认 20 条）时，自动触发 FIFO（先进先出）截断，移除最老的对话记录。
- **原子更新**：每次对话结束后，将会话快照序列化并持久化落盘。

## 4. Non-functional Requirement (性能要求)

- 持久化读写耗时目标 `< 50ms`，不能因为读写记忆阻塞主对话流程。
- 系统重启、宕机后，用户的会话记忆不能丢失。

## 5. Responsibility (职责)

- 负责 `UserId:SessionId` 的隔离校验。
- 负责 Java 对象 `List<ChatMessage>` 与纯文本 JSON 之间的双向序列化映射。
- 屏蔽底层异构存储引擎（MongoDB）的复杂查询语法，向上层大模型代理暴露标准接口。

## 6. Constraints (约束)

**必须实现 (MUST)：**

- [x] 必须使用 MongoDB 的 `upsert` 原子操作来覆写文档，绝对禁止 `delete` 后再 `insert`。
- [x] 必须处理 Jackson 多态反序列化（能区分 `UserMessage`、`AiMessage` 和 `SystemMessage`）。
- [x] 必须设置兜底策略：当 MongoDB 连接失败时，直接返回空列表（新会话），绝对禁止抛出异常中断主流程。

**绝对禁止 (MUST NOT)：**

- [ ] 禁止使用 `static List` 或 `ConcurrentHashMap` 在 JVM 内存中长期缓存记忆数据。
- [ ] 禁止 Controller 层直接调用 MongoDB，必须经过 Agent 调度层。

## 7. Workflow & Sequence Diagram (流程与时序图)

Plaintext

```
User            AI Agent Gateway      Memory Engine           MongoDB
 │                   │                   │                   │
 │─1. 发起对话 ──────▶│                   │                   │
 │                   │─2. loadMemory ───▶│                   │
 │                   │                   │─3. findById ─────▶│
 │                   │                   │◀4. 返回 BSON 文档 ─│
 │                   │◀5. 反序列化 List ─│                   │
 │                   │                   │                   │
 │                   │ (拼接上下文并调用 LLM 推理...)        │
 │                   │                   │                   │
 │                   │─6. updateMemory ─▶│                   │
 │                   │                   │─7. 执行滑动截断   │
 │                   │                   │─8. 执行 upsert ──▶│
 │◀9. 返回流式响应 ───│                   │◀9. 写入成功确认 ──│
```

## 8. Data Model (数据模型)

**集合名称**：`chat_session_memory` **主键索引**：`memory_id`

JSON

```
{
  "_id": "ObjectId('651a2b3...')",
  "memory_id": "user_1001",
  "messages": [
    {
      "type": "USER",
      "content": "推荐一款手机"
    },
    {
      "type": "AI",
      "content": "好的，为您推荐...",
      "toolCalls": [] 
    }
  ],
  "updated_at": "ISODate('2026-07-23T12:00:00Z')"
}
```

## 9. API Design (内部接口)

Java

```
public interface ChatMemoryStore {
    // 恢复上下文
    List<ChatMessage> getMessages(Object memoryId);
    // 覆写上下文
    void updateMessages(Object memoryId, List<ChatMessage> messages);
    // 清除会话
    void deleteMessages(Object memoryId);
}
```

## 10. Class Design (核心类设计)

AI Coding 生成边界说明：

- **`ChatSessionDocument` (@Document)**：MongoDB 实体类，字段需映射第 8 节结构。
- **`ChatSessionRepository` (接口)**：继承 `MongoRepository`，需结合 `MongoTemplate` 实现原生的 `upsert` 逻辑。
- **`MongoChatMemoryStore` (实现类)**：实现 `ChatMemoryStore` 接口，注入 Repository 和 Jackson `ObjectMapper`，处理滑动截断与多态解析。

## 11. Exception Handling (异常处理)

- **`MongoTimeoutException`**：打出 Error 日志，向大模型返回空上下文，保障对话可用性。
- **`JsonMappingException`**：当旧版 JSON 无法解析时，清空当前文档的 messages 字段，重置为新会话并打出 Warn 日志。

## 12. Test Plan (测试计划)

- **截断测试**：向 `updateMessages` 传入 25 条记录，断言落盘后集合内仅存 20 条，且时间戳最新的记录被保留。
- **并发更新测试**：模拟两个线程同时对同一个 `memoryId` 触发 `updateMessages`，验证是否产生重复插入的脏数据。

## 13. Acceptance Criteria (验收标准)

- [ ] 连续输入 20 轮以上对话，系统不抛出 Token 超限异常。
- [ ] MongoDB 重启后，用户发起的第一条对话能准确回忆起上一轮的语义。
- [ ] 不同 `UserId` 的账户同时发起请求，上下文内容互不串扰。

## 14. Future Evolution (演进路线)

- **摘要记忆介入**：触发滑动窗口截断时，将被淘汰的消息交由 LLM 压缩成摘要（Summary Memory），并永久驻留于上下文头部。