package com.shopmind.knowledge.port;

import com.shopmind.knowledge.model.KnowledgeChunk;

import java.util.List;

/**
 * 向量存储抽象接口 — RAG_Engine.md §6 Adapter 模式。
 * <p>
 * 通过此接口屏蔽底层向量数据库实现差异（InMemory / Qdrant）。
 * 系统内所有向量操作均通过此端口进行，遵循 DIP。
 */
public interface VectorStorePort {

    /**
     * 语义搜索：传入查询向量，返回最相似的 Top-K 知识块。
     *
     * @param queryVector 查询文本的向量表示
     * @param topK        最大召回数
     * @return 按相似度降序排列的知识块列表（可能为空）
     */
    List<KnowledgeChunk> search(float[] queryVector, int topK);

    /**
     * 添加知识块及其向量到存储中。
     *
     * @param chunk  知识块
     * @param vector 向量表示
     */
    void add(KnowledgeChunk chunk, float[] vector);

    /**
     * 清空所有向量数据（用于测试或重新初始化）。
     */
    void clear();

    /**
     * 当前已存储的向量总数。
     */
    int size();
}
