# Module: Knowledge Engine (企业知识增强引擎 / RAG)

**Version**: v1.1

**Status**: 📝 Draft -> [ ] Review -> [x] Approved -> [ ] Implemented -> [ ] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:**
>
> This document is the single source of truth for implementing the Knowledge (RAG) Engine. Any AI-generated code MUST strictly follow this specification.
>
> **Architecture Review Prompt:**
>
> Review this specification from the perspective of SOLID, DDD, Clean Architecture, Thread Safety, High Availability, and Extensibility. Do not generate code until the review is passed.

## 1. Overview (模块概述)

Knowledge Engine 是 AI Platform 的事实依据中枢。它通过构建完整的 Retrieval Pipeline（检索流水线），将企业的私有资产转化为高维向量并进行语义召回。核心目标是为 Agent 提供绝对可靠的 Context（上下文），从根本上消除大模型在垂直业务域的“知识幻觉”。

## 2. Business Requirement (业务需求)

- **私域知识问答**：确保大模型在回答电商特定规则时，100% 依据企业内部最新文档，而非通用预训练语料。
- **知识库热更新**：当运营修改了“满减规则”文档，系统需能低成本、快速地更新索引，无需重新训练大模型。

## 3. Functional Requirement (功能需求)

- **文档切片 (Chunking)**：按照指定策略对 Markdown/PDF 进行合理分块。
- **动态向量化 (Embedding)**：对接外部 Embedding Provider，将文本切片向量化。
- **多级检索与过滤 (Retrieval Pipeline)**：支持前置缓存拦截、向量相似度召回以及阈值过滤。

## 4. Non-functional Requirement (性能要求)

- **检索低延迟**：从收到 Query 到返回 Top-K Context 的全链路耗时必须 `< 200ms`。
- **可插拔架构**：底层向量数据库和 Embedding 模型必须彻底解耦，保障多环境无缝切换。

## 5. Responsibility (职责)

- **专注知识检索**：本引擎**只负责**将 Query 映射为相关知识片段（Text Chunks）。
- **边界隔离**：本引擎**绝对不负责** Prompt 的拼接与最终答案的生成（这是 Agent Orchestrator 的职责）。

## 6. Constraints (约束)

**必须实现 (MUST)：**

- [x] **Adapter 模式**：必须抽象 `VectorStorePort` 和 `EmbeddingProviderPort`。
- [x] **依赖倒置**：核心逻辑仅依赖接口，不直接依赖第三方 SDK 实现。

**绝对禁止 (MUST NOT)：**

- [ ] 禁止每次检索都直接请求 Embedding API，必须前置一层 Cache。
- [ ] 禁止 Agent 绕过此引擎直接读取本地文件。

## 7. Chunk Strategy (分块策略)

合理的文档切片是 RAG 召回率的核心保障，本引擎强制采用以下策略：

- **Chunk Size (标准块大小)**: 500 Characters (字符)
- **Overlap (重叠字符)**: 100 Characters (保障上下文语义不被硬切断)
- **Split Strategy (切分标识)**: 优先按段落 (`\n\n`) 切分，其次按 Markdown Header (`#`, `##`) 切分。
- **Maximum Chunk (最大容忍块)**: 1000 Characters (防止长段落撑爆 Token)

## 8. Workflow & Sequence Diagram (检索流水线与时序图)

采用带有缓存命中的标准 Retrieval Pipeline：

Plaintext

```
User Query
    │
    ▼
[ Query Cache ]
    ├── (Hit)
    │      ▼
    │   Return Context
    │
    └── (Miss)
           ▼
[ Embedding Provider ] (e.g., DashScope / OpenAI)
           │
           ▼ (Vector)
[ Vector Store Search ] (InMemory / Qdrant)
           │
           ▼ (Top K Chunks)
[ Threshold Filter ] (e.g., Score < 0.75 则丢弃)
           │
           ▼ 
[ Context Builder ]
           │
           ▼
    Return Context
```

## 9. Data Model (数据模型)

**KnowledgeChunk (核心领域模型)** 为支持未来的 Citation (溯源) 与 Rerank，统一在系统内流转此对象，而非单纯的 String。

Java

```
class KnowledgeChunk {
    private String id;           // 唯一标识 (如 doc_aftersales_chunk_12)
    private String text;         // 切片文本内容
    private double score;        // 相似度得分
    private Map<String, Object> metadata; // 元数据 (如来源文件、类目)
}
```

## 10. API Design (内部接口)

面向未来演进设计，采用强类型对象封装请求与响应：

Java

```
public interface KnowledgeRetriever {
    /**
     * 执行完整的 Retrieval Pipeline 检索
     */
    RetrievedContext retrieve(QueryRequest request);
}

class QueryRequest {
    private String query;           // 用户检索词
    private int topK;               // 最大召回数量
    private double scoreThreshold;  // 最低相似度阈值
}

class RetrievedContext {
    private List<KnowledgeChunk> chunks; // 召回的知识块集合
    private long latency;                // 检索耗时 (ms)
    private boolean cacheHit;            // 是否命中缓存
}
```

## 11. Class Design (核心类设计)

- **`RetrievalPipeline` (Component)**：检索主干类，串联 Cache、Embedding、Search 和 Filter。
- **`VectorStorePort` (Interface)** & **`InMemoryVectorStoreAdapter` (Impl)**：解耦底层向量数据库。
- **`EmbeddingProviderPort` (Interface)**：
  - `DashScopeEmbeddingAdapter` (Impl)
  - `OpenAIEmbeddingAdapter` (Impl)
- **`QueryCacheService` (Component)**：基于 Caffeine 的本地缓存拦截器。本类线程安全：内部使用 `ConcurrentHashMap` 支撑的 `Cache` 实例只读共享，无任何可变实例字段。
- **`ChunkingStrategy` (Interface + Strategy Pattern)**：实现第 7 节的分块策略。当前默认 `ParagraphChunkingStrategy`，通过接口注入即可运行时切换。
- **`DocumentParser` (Component)**：负责加载 Markdown/PDF 文档，委托 `ChunkingStrategy` 执行分块，并调用 `VectorStorePort` 持久化向量。

## 12. Exception Handling (异常处理)

- **`LowSimilarityException` (核心业务异常)**：当最高 Score 低于 `scoreThreshold` 时抛出（如最高分仅 0.42）。**降级策略**：Agent 捕获后，基于空 Context 诚实回答“抱歉，知识库没有相关信息”。
- **`EmbeddingTimeoutException`**：外部大模型服务超时。**降级策略**：记录 Error 日志，视作未召回。
- **`VectorStoreConnectionException`**：向量库断连。**降级策略**：熔断 RAG 链路，保障电商主链路存活。

## 12.1 Edge Cases & Degradation (边界情况与降级)

本模块在实际运行中会遇到以下已知边缘场景，均需在 `RetrievalPipeline` 中编码处理：

| 边缘场景 | 检测条件 | 降级行为 |
|---|---|---|
| 空知识库 | `VectorStorePort.search()` 返回空列表 | 上抛 `LowSimilarityException`，Agent 反馈“知识库暂无内容，请联系运营” |
| 全部块低于阈值 | 最高 Score < `scoreThreshold` | 上抛 `LowSimilarityException`，Agent 反馈“抱歉，知识库没有相关信息” |
| Embedding API 超时 | 外部服务 > 3s 无响应 | 捕获 `EmbeddingTimeoutException`，跳过 RAG，降级为无知识回答 |
| 向量库连接断开 | `VectorStoreConnectionException` | 跳过 RAG，Agent 仅依赖 Memory 继续对话（不中断主链路） |
| 并发缓存访问 | 多线程同时查询同一 key | `Cache.get(key, loader)` 保证仅一个线程调用 loader，其余等待结果 |
| 空 Query 请求 | `query` 为 null 或空字符串 | 直接返回空 `RetrievedContext`，不触发后续 Pipeline |

## 13. Test Plan (测试计划)

- **Chunk Strategy Test (切片策略测试)**：输入一篇 10000 字的复杂 Markdown 文档，断言：1) Overlap 是否生效且无字符丢失；2) Markdown Header 结构是否被合理保留；3) 无超过 1000 字符的超长 Chunk。
- **Cache Hit Test (缓存穿透测试)**：连续传入相同的 QueryRequest，断言第二次调用的 `RetrievedContext.cacheHit == true` 且不触发底层 `EmbeddingProviderPort`。
- **Pipeline 完整性测试**：验证 Query -> Embedding -> Search -> Filter 的完整数据流转。

## 14. Evaluation (评测指标)

- **Retrieval Recall@K**：在 Top-K 召回中，真实相关文档的命中率（目标 `> 85%`）。
- **MRR (Mean Reciprocal Rank)**：相关文档出现在首位的概率评估。
- **Average Retrieval Latency**：Pipeline 端到端平均延迟（目标 `< 200ms`）。
- **Cache Hit Rate**：高频相似问题（如“退货要求”）的缓存命中率（目标 `> 40%`）。

## 15. Future Evolution (演进路线)

- **Phase 2**：在 Threshold Filter 之后引入专用的 Rerank（重排模型），提升精排准确度。
- **Phase 3**：引入 Hybrid Search（向量检索 + BM25 关键词检索双路召回）。
- **Phase 4**：生产环境无缝接入 `QdrantVectorStoreAdapter`。