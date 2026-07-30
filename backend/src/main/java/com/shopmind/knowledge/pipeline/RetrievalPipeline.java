package com.shopmind.knowledge.pipeline;

import com.shopmind.knowledge.api.KnowledgeRetriever;
import com.shopmind.knowledge.exception.EmbeddingTimeoutException;
import com.shopmind.knowledge.exception.LowSimilarityException;
import com.shopmind.knowledge.exception.VectorStoreConnectionException;
import com.shopmind.knowledge.model.KnowledgeChunk;
import com.shopmind.knowledge.model.QueryRequest;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.knowledge.port.EmbeddingProviderPort;
import com.shopmind.knowledge.port.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * RAG 检索核心流水线 — RAG_Engine.md §8 + §11 规范。
 * <p>
 * 严格按照检索流水线时序执行：
 * <ol>
 *   <li><b>准入检查</b>：空 Query 直接返回空上下文</li>
 *   <li><b>Query Cache</b>：命中则直接返回，旁路后续 Embedding</li>
 *   <li><b>Embedding</b>：调用 EmbeddingProviderPort 将 Query 向量化</li>
 *   <li><b>Vector Search</b>：调用 VectorStorePort 进行语义检索</li>
 *   <li><b>Threshold Filter</b>：丢弃低于 scoreThreshold 的块，全丢弃则抛 LowSimilarityException</li>
 *   <li><b>Context Build</b>：组装 RetrievedContext 返回给调用方</li>
 * </ol>
 * <p>
 * <b>线程安全</b>：本类为无状态组件（@Component 单例），所有状态均在方法参数中传递，
 * 无任何可变实例字段，可在高并发下安全使用。
 */
@Component
public class RetrievalPipeline implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final QueryCacheService cacheService;
    private final EmbeddingProviderPort embeddingProvider;
    private final VectorStorePort vectorStore;

    public RetrievalPipeline(QueryCacheService cacheService,
                             EmbeddingProviderPort embeddingProvider,
                             VectorStorePort vectorStore) {
        this.cacheService = cacheService;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
    }

    // ============================================================
    //  retrieve — Pipeline 主流程
    // ============================================================

    @Override
    public RetrievedContext retrieve(QueryRequest request) {
        long startTime = System.currentTimeMillis();

        // ---- Step 0: 准入检查（§12.1 边缘场景：空 Query） ----
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            log.info("[Knowledge] Empty query, returning empty context.");
            return buildEmptyContext(request, startTime, false);
        }

        log.info("[Knowledge] Retrieving for query='{}', topK={}, threshold={}",
                request.getQuery(), request.getTopK(), request.getScoreThreshold());

        // ---- Step 1: Query Cache（§8 + §12.1） ----
        RetrievedContext cached = cacheService.lookup(request);
        if (cached != null) {
            return cached; // 缓存命中，直接返回
        }

        // ---- Step 2: Embedding（§8 第二步） ----
        float[] queryVector;
        try {
            queryVector = embeddingProvider.embed(request.getQuery());
        } catch (EmbeddingTimeoutException e) {
            // §12: Embedding 超时 → 跳过 RAG，返回空上下文
            log.error("[Knowledge] Embedding timed out for query='{}'. "
                    + "Degrading to empty context.", request.getQuery(), e);
            RetrievedContext degraded = buildEmptyContext(request, startTime, false);
            cacheService.put(request, degraded);
            return degraded;
        }

        // ---- Step 3: 向量相似度检索Vector Search（§8 第三步） ----
        List<KnowledgeChunk> rawChunks;
        try {
            rawChunks = vectorStore.search(queryVector, request.getTopK());
        } catch (VectorStoreConnectionException e) {
            // §12.1: 向量库断连 → 跳过 RAG
            log.error("[Knowledge] Vector store connection failed for query='{}'. "
                    + "Degrading to empty context.", request.getQuery(), e);
            RetrievedContext degraded = buildEmptyContext(request, startTime, false);
            return degraded;
        }

        // ---- Step 3.1: 空结果检查（§12.1 边缘场景：空知识库） ----
        if (rawChunks == null || rawChunks.isEmpty()) {
            log.warn("[Knowledge] No chunks found for query='{}'.", request.getQuery());
            throw new LowSimilarityException(request.getQuery());
        }

        // ---- Step 4: 阈值过滤Threshold Filter（§8 第四步 + §12） ----
        List<KnowledgeChunk> filtered = rawChunks.stream()
                .filter(chunk -> chunk.getScore() >= request.getScoreThreshold())
                .toList();

        if (filtered.isEmpty()) {
            double maxScore = rawChunks.stream()
                    .mapToDouble(KnowledgeChunk::getScore)
                    .max().orElse(0.0);
            log.warn("[Knowledge] All chunks below threshold. Max score={}, threshold={}",
                    maxScore, request.getScoreThreshold());
            throw new LowSimilarityException(maxScore, request.getScoreThreshold());
        }

        // ---- Step 5: Context Build（§8 第五步） ----
        long latency = System.currentTimeMillis() - startTime;
        RetrievedContext context = RetrievedContext.builder()
                .chunks(filtered)
                .latency(latency)
                .cacheHit(false)
                .build();

        log.info("[Knowledge] Retrieval complete: query='{}', hits={}/{}, latency={}ms",
                request.getQuery(), filtered.size(), rawChunks.size(), latency);

        // ---- Step 6: 写入缓存 ----
        cacheService.put(request, context);

        return context;
    }

    // ============================================================
    //  私有方法
    // ============================================================

    /**
     * 构建空上下文（用于降级场景）。
     */
    private RetrievedContext buildEmptyContext(QueryRequest request, long startTime, boolean cacheHit) {
        return RetrievedContext.builder()
                .chunks(Collections.emptyList())
                .latency(System.currentTimeMillis() - startTime)
                .cacheHit(cacheHit)
                .build();
    }
}
