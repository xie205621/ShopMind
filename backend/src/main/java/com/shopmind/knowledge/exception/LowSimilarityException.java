package com.shopmind.knowledge.exception;

/**
 * 低相似度异常 — 所有召回块得分均低于阈值时抛出（§12 规范）。
 * <p>
 * 降级策略：Agent 捕获后，基于空 Context 诚实回答"抱歉，知识库没有相关信息"。
 * 由 {@code @ControllerAdvice} 统一转换为友好降级回复。
 */
public class LowSimilarityException extends RuntimeException {

    /** 检索时的最高相似度得分 */
    private final double highestScore;

    /** 配置的最低阈值 */
    private final double threshold;

    public LowSimilarityException(double highestScore, double threshold) {
        super(String.format("所有召回块相似度均低于阈值 (最高: %.4f < 阈值: %.2f)", highestScore, threshold));
        this.highestScore = highestScore;
        this.threshold = threshold;
    }

    /**
     * 用于空知识库场景 — 没有任何匹配结果。
     */
    public LowSimilarityException(String query) {
        super("知识库中没有找到与 \"" + query + "\" 相关的内容");
        this.highestScore = 0.0;
        this.threshold = 0.0;
    }

    public double getHighestScore() { return highestScore; }
    public double getThreshold() { return threshold; }
}
