package com.shopmind.knowledge.api;

import com.shopmind.knowledge.model.QueryRequest;
import com.shopmind.knowledge.model.RetrievedContext;

/**
 * 知识检索入口接口 — RAG_Engine.md §10 规范。
 * <p>
 * Agent Orchestrator 通过此接口获取 RAG 上下文，
 * 无需关心底层 Retrieval Pipeline 的实现细节。
 */
public interface KnowledgeRetriever {

    /**
     * 执行完整的 Retrieval Pipeline 检索。
     * <p>
     * 内部流程：Cache Check → Embedding → Vector Search → Threshold Filter → Context Build。
     *
     * @param request 检索请求，包含 query、topK、scoreThreshold
     * @return 检索结果上下文，包含 chunk 列表、耗时、缓存命中标记
     */
    RetrievedContext retrieve(QueryRequest request);
}
