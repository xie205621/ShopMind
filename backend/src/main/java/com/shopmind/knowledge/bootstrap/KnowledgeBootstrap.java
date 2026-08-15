package com.shopmind.knowledge.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.knowledge.model.KnowledgeChunk;
import com.shopmind.knowledge.port.EmbeddingProviderPort;
import com.shopmind.knowledge.port.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 知识库启动加载器 — 应用启动时从 classpath 加载真实业务知识并写入向量库。
 * <p>
 * 数据源：{@code classpath:knowledge/knowledge-base.json}。
 * 使 RAG 在无外部数据源的情况下也能检索到业务知识（替代测试代码中的硬编码 seed）。
 */
@Component
public class KnowledgeBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private static final String KNOWLEDGE_RESOURCE = "knowledge/knowledge-base.json";

    private final EmbeddingProviderPort embeddingProvider;
    private final VectorStorePort vectorStore;
    private final ObjectMapper objectMapper;

    public KnowledgeBootstrap(EmbeddingProviderPort embeddingProvider,
                              VectorStorePort vectorStore,
                              ObjectMapper objectMapper) {
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (InputStream in = new ClassPathResource(KNOWLEDGE_RESOURCE).getInputStream()) {
            List<KnowledgeChunk> chunks = objectMapper.readValue(in,
                    new TypeReference<List<KnowledgeChunk>>() {});
            for (KnowledgeChunk chunk : chunks) {
                float[] vector = embeddingProvider.embed(chunk.getText());
                vectorStore.add(chunk, vector);
            }
            log.info("[Knowledge] Bootstrapped {} chunks from {} into vector store (total={})",
                    chunks.size(), KNOWLEDGE_RESOURCE, vectorStore.size());
        } catch (Exception e) {
            log.error("[Knowledge] Failed to bootstrap knowledge base from {}", KNOWLEDGE_RESOURCE, e);
        }
    }
}
