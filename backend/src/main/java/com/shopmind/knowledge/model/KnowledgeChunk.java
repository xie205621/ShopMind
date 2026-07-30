package com.shopmind.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * 知识块 — Knowledge Engine 的核心领域模型（§9 规范）。
 * <p>
 * 在系统中统一流转此对象而非纯 String，为未来的 Citation（溯源）和 Rerank（重排）预留扩展空间。
 * 每个 Chunk 携带相似度得分和来源元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    /** 唯一标识（如 "doc_aftersales_chunk_12"），默认自动生成 */
    @Builder.Default
    private String id = UUID.randomUUID().toString().substring(0, 8);

    /** 切片文本内容 — 检索后用于拼入 Prompt 的核心数据 */
    private String text;

    /** 向量相似度得分（余弦相似度，范围 0.0 ~ 1.0） */
    private double score;

    /** 元数据（如来源文件路径、文档类目、章节标题等） */
    private Map<String, Object> metadata;

    // ============================================================
    //  Explicit getters & setters (Lombok not generating in this env)
    // ============================================================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id = UUID.randomUUID().toString().substring(0, 8);
        private String text;
        private double score;
        private Map<String, Object> metadata;

        public Builder id(String id) { this.id = id; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder score(double score) { this.score = score; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public KnowledgeChunk build() {
            KnowledgeChunk obj = new KnowledgeChunk();
            obj.setId(id);
            obj.setText(text);
            obj.setScore(score);
            obj.setMetadata(metadata);
            return obj;
        }
    }
}
