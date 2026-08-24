# ADR 002：向量存储选型（InMemory EmbeddingStore）

- 状态：Accepted
- 日期：2026-07-28（回溯补记）
- 相关模块：RAG Engine

## 背景（Context）

RAG 检索需要一个向量存储来保存知识库 chunk 的 embedding 并支持相似度检索。

## 决策（Decision）

当前使用 LangChain4j 的 `InMemoryEmbeddingStore`（进程内内存存储），通过 `VectorStorePort` 抽象隔离；Qdrant 作为后续计划（planned）。

## 代码事实（Evidence）

- `VectorStorePort`（端口）→ `InMemoryVectorStoreAdapter`（适配器）
- [ContextHydrationStep.java](file:///d:/A_big/ShopMind/backend/src/main/java/com/shopmind/orchestrator/pipeline/ContextHydrationStep.java#L97-L101)：检索参数 `topK=3`、`scoreThreshold=0.7`
- 静态知识库 [knowledge-base.json](file:///d:/A_big/ShopMind/backend/src/main/resources/knowledge/knowledge-base.json)：30 chunks
- Embedding 随 profile 切换：`qwen`=text-embedding-v3（1024 维）；`default`/`deepseek`=Mock SHA-256（256 维）

## 取舍（Consequences）

- 优点：单节点零外部依赖、启动简单、30 chunks 小规模够用
- 代价：内存存储，进程重启即失效（由静态 JSON 知识库重新加载）；不支持大规模 / 持久化检索
- 后续：Qdrant 已标注 planned，用于生产级向量检索

## 待人工确认

「为何暂未接入 Qdrant / 何时切换」的规划未在规范中明确，需人工补充。
