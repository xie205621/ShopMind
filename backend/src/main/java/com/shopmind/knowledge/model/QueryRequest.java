package com.shopmind.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索请求 — Knowledge Engine 入口参数（§10 规范）。
 * <p>
 * 由 Agent Orchestrator 构造并传入 KnowledgeRetriever。
 * 所有阈值参数均有合理默认值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {

    /** 用户检索词（从用户对话中提取或直接传入） */
    private String query;

    /** 最大召回数量，默认 5 */
    @Builder.Default
    private int topK = 5;

    /** 最低相似度阈值（0.0 ~ 1.0），低于此值的结果将被丢弃，默认 0.75 */
    @Builder.Default
    private double scoreThreshold = 0.75;

    // ============================================================
    //  Explicit getters & setters (Lombok not generating in this env)
    // ============================================================

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String query;
        private int topK = 5;
        private double scoreThreshold = 0.75;

        public Builder query(String query) { this.query = query; return this; }
        public Builder topK(int topK) { this.topK = topK; return this; }
        public Builder scoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; return this; }

        public QueryRequest build() {
            QueryRequest obj = new QueryRequest();
            obj.setQuery(query);
            obj.setTopK(topK);
            obj.setScoreThreshold(scoreThreshold);
            return obj;
        }
    }
}
