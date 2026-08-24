package com.shopmind.knowledge;

import com.shopmind.knowledge.exception.EmbeddingTimeoutException;
import com.shopmind.knowledge.exception.VectorStoreConnectionException;
import com.shopmind.knowledge.model.QueryRequest;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.knowledge.pipeline.QueryCacheService;
import com.shopmind.knowledge.pipeline.RetrievalPipeline;
import com.shopmind.knowledge.port.EmbeddingProviderPort;
import com.shopmind.knowledge.port.VectorStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-2 稳定性验收：RAG 异常降级。
 * <p>
 * 纯单元测试（无 Spring 上下文），直接构造 {@link RetrievalPipeline} 并注入 mock 依赖，
 * 验证 Embedding 超时 / 向量库断连时按 §12 规范降级为空上下文，而不是抛异常中断主链路。
 */
class RetrievalPipelineDegradationTest {

    @Test
    @DisplayName("RAG 异常：Embedding 超时 → 降级为空上下文，不抛异常")
    void embeddingTimeoutDegradesToEmptyContext() {
        EmbeddingProviderPort embedding = mock(EmbeddingProviderPort.class);
        when(embedding.embed(anyString())).thenThrow(new EmbeddingTimeoutException("simulated timeout"));

        VectorStorePort vectorStore = mock(VectorStorePort.class);
        RetrievalPipeline pipeline = new RetrievalPipeline(
                new QueryCacheService(10, 30), embedding, vectorStore);

        RetrievedContext ctx = pipeline.retrieve(
                QueryRequest.builder().query("退货政策").topK(3).scoreThreshold(0.7).build());

        assertThat(ctx.hasResults()).isFalse();
        assertThat(ctx.getChunks()).isEmpty();
    }

    @Test
    @DisplayName("RAG 异常：向量库断连 → 降级为空上下文，不抛异常")
    void vectorStoreConnectionFailureDegradesToEmptyContext() {
        EmbeddingProviderPort embedding = mock(EmbeddingProviderPort.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});

        VectorStorePort vectorStore = mock(VectorStorePort.class);
        when(vectorStore.search(any(), anyInt()))
                .thenThrow(new VectorStoreConnectionException("simulated connection failure"));

        RetrievalPipeline pipeline = new RetrievalPipeline(
                new QueryCacheService(10, 30), embedding, vectorStore);

        RetrievedContext ctx = pipeline.retrieve(
                QueryRequest.builder().query("退货政策").topK(3).scoreThreshold(0.7).build());

        assertThat(ctx.hasResults()).isFalse();
        assertThat(ctx.getChunks()).isEmpty();
    }
}
