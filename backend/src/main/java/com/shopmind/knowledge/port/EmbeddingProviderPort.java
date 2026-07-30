package com.shopmind.knowledge.port;

import com.shopmind.knowledge.exception.EmbeddingTimeoutException;

/**
 * Embedding 提供者抽象接口 — RAG_Engine.md §6 Adapter 模式。
 * <p>
 * 通过此接口屏蔽底层 Embedding 模型差异（DashScope / OpenAI / 本地模型）。
 * 遵循 DIP，核心逻辑仅依赖此接口。
 */
public interface EmbeddingProviderPort {

    /**
     * 将文本向量化。
     *
     * @param text 待向量化的文本
     * @return 浮点向量数组（维度取决于实现）
     * @throws EmbeddingTimeoutException 外部服务超时时抛出
     */
    float[] embed(String text);
}
