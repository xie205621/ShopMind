package com.shopmind.knowledge.adapter;

import com.shopmind.knowledge.model.KnowledgeChunk;
import com.shopmind.knowledge.port.VectorStorePort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 内嵌向量存储适配器 — 基于 LangChain4j 的 InMemoryEmbeddingStore。
 * <p>
 * Phase 1 使用 JVM 内存存储（开发/测试/小规模生产）。
 * Phase 4 将无缝替换为 QdrantVectorStoreAdapter，无需修改调用方代码。
 * <p>
 * 线程安全：内部 {@link InMemoryEmbeddingStore} 使用 ConcurrentHashMap 保证并发安全。
 */
@Component
public class InMemoryVectorStoreAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStoreAdapter.class);

    /**
     * LangChain4j 内嵌向量存储 — 常驻内存，基于余弦相似度搜索。
     */
    private volatile EmbeddingStore<TextSegment> embeddingStore;

    /** 当前已存储向量总数（LangChain4j InMemoryEmbeddingStore 不暴露 size） */
    private final AtomicInteger sizeCounter = new AtomicInteger(0);

    public InMemoryVectorStoreAdapter() {
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        log.info("[Knowledge] Vector Store initialized: InMemoryEmbeddingStore (Phase 1)");
    }

    @Override
    public List<KnowledgeChunk> search(float[] queryVector, int topK) {
        Embedding queryEmbedding = Embedding.from(toList(queryVector));
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, topK);

        return matches.stream()
                .map(this::toKnowledgeChunk)
                .collect(Collectors.toList());
    }

    @Override
    public void add(KnowledgeChunk chunk, float[] vector) {
        Embedding embedding = Embedding.from(toList(vector));
        TextSegment segment = chunk.getMetadata() != null
                ? TextSegment.from(chunk.getText(), toMetadata(chunk.getMetadata()))
                : TextSegment.from(chunk.getText());
        embeddingStore.add(embedding, segment);
        sizeCounter.incrementAndGet();
        log.debug("[Knowledge] Added chunk '{}' to vector store", chunk.getId());
    }

    @Override
    public void clear() {
        // InMemoryEmbeddingStore 没有 clear() 方法，重新创建实例实现清空
        this.embeddingStore = new InMemoryEmbeddingStore<>();
        this.sizeCounter.set(0);
        log.info("[Knowledge] Vector store cleared.");
    }

    @Override
    public int size() {
        return sizeCounter.get();
    }

    // ============================================================
    //  私有映射方法
    // ============================================================

    /**
     * 将 LangChain4j EmbeddingMatch 转换为领域 KnowledgeChunk。
     */
    private KnowledgeChunk toKnowledgeChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        return KnowledgeChunk.builder()
                .id(match.embeddingId())
                .text(segment.text())
                .score(match.score())
                .metadata(segment.metadata() != null
                        ? segment.metadata().toMap()
                        : null)
                .build();
    }

    /**
     * float[] → List&lt;Float&gt;
     */
    private List<Float> toList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }

    /**
     * Map<String, Object> → LangChain4j Metadata
     */
    private dev.langchain4j.data.document.Metadata toMetadata(Map<String, Object> map) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return metadata;
    }
}
