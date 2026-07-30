package com.shopmind.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 检索结果上下文 — Knowledge Engine 输出模型（§10 规范）。
 * <p>
 * 包含召回的 Chunk 列表、检索耗时和缓存命中标记，
 * 由 Agent Orchestrator 消费后拼入 Prompt。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedContext {

    /** 召回的知识块集合 */
    @Builder.Default
    private List<KnowledgeChunk> chunks = Collections.emptyList();

    /** 检索全链路耗时（毫秒） */
    private long latency;

    /** 是否命中缓存（true = 未经过 Embedding + Vector Search） */
    @Builder.Default
    private boolean cacheHit = false;

    // ============================================================
    //  便捷方法
    // ============================================================

    /** 是否为空（知识库无相关内容） */
    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }

    /** 是否有有效召回结果 */
    public boolean hasResults() {
        return !isEmpty();
    }

    /** 获取最高相似度得分 */
    public double getMaxScore() {
        return chunks == null || chunks.isEmpty()
                ? 0.0
                : chunks.stream().mapToDouble(KnowledgeChunk::getScore).max().orElse(0.0);
    }

    // ============================================================
    //  Explicit getters & setters (Lombok not generating in this env)
    // ============================================================

    public List<KnowledgeChunk> getChunks() { return chunks; }
    public void setChunks(List<KnowledgeChunk> chunks) { this.chunks = chunks; }

    public long getLatency() { return latency; }
    public void setLatency(long latency) { this.latency = latency; }

    public boolean isCacheHit() { return cacheHit; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<KnowledgeChunk> chunks = Collections.emptyList();
        private long latency;
        private boolean cacheHit = false;

        public Builder chunks(List<KnowledgeChunk> chunks) { this.chunks = chunks; return this; }
        public Builder latency(long latency) { this.latency = latency; return this; }
        public Builder cacheHit(boolean cacheHit) { this.cacheHit = cacheHit; return this; }

        public RetrievedContext build() {
            RetrievedContext obj = new RetrievedContext();
            obj.setChunks(chunks);
            obj.setLatency(latency);
            obj.setCacheHit(cacheHit);
            return obj;
        }
    }

    /** 拼接所有 Chunk 文本为单一字符串（用于注入 Prompt） */
    public String toConcatenatedText() {
        if (isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            sb.append("[来源 ").append(i + 1).append("] ").append(chunk.getText()).append("\n");
        }
        return sb.toString();
    }
}
