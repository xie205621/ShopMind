# ADR 001：为什么使用 MongoDB 存储会话记忆

- 状态：Accepted
- 日期：2026-07-28（回溯补记）
- 相关模块：Memory Engine

## 背景（Context）

Agent 的会话记忆需要在多次请求之间保持连续，并支持多租户隔离与 token 成本控制。

## 决策（Decision）

会话记忆持久化到 MongoDB，以 `memory_id` 为主键，使用 `upsert` 原子覆写，并施加滑动窗口（默认保留最近 20 条，超出 FIFO 截断）。

## 代码事实（Evidence）

- [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L129-L137)：`mongoTemplate.upsert` 原子覆写
- [MongoChatMemoryStore.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/memory/store/MongoChatMemoryStore.java#L172-L184)：`applySlidingWindow` FIFO 截断
- [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L5-L13)：MongoDB URI 与 `max-messages: 20`
- [1_Session_Memory.md](file:///d:/A_big/ShopMind/docs/02_Specifications/1_Session_Memory.md#L41-L43)：「必须使用 MongoDB 的 upsert 原子操作，禁止 delete 后 insert」「连接失败返回空列表，禁止抛异常中断主流程」
- [pom.xml](file:///d:/A_big/ShopMind/backend/pom.xml#L112-L118)：`de.flapdoodle.embed.mongo`（test scope，测试无需外部 MongoDB）

## 取舍（Consequences）

- 优点：持久化、`upsert` 原子覆写、多租户按 `memory_id` 隔离、滑动窗口控制 token 成本
- 代价：运行时依赖 MongoDB；测试需 Embedded MongoDB

## 待人工确认

「为什么选 MongoDB 而非 Redis / 关系型数据库」的对比取舍，当前规范只给了「用 MongoDB」的约束，未记录与其他方案的对比理由，需人工补充。
