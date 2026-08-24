package com.shopmind.evaluation.domain;

import java.util.Collections;
import java.util.List;

/**
 * 实验超参数配置 — 6_Evaluation_Engine.md §5.1 规范。
 * <p>
 * 严格记录 LLM 版本、Temperature、Embedding 模型等所有影响实验结果的变量。
 * 这是保证科研可复现性（Reproducible Research）的基石——任何一个参数变更
 * 都意味着一次独立的实验，需要在 ExperimentReport 中完整记录。
 * <p>
 * 使用 Java record 确保不可变语义——实验一旦开始，参数不可修改。
 *
 * @param experimentId     实验全局唯一 ID（用于 Trace 关联和报告命名）
 * @param workflowVersion  被评测的工作流版本（如 "v1.4"）
 * @param datasetVersion   数据集版本
 * @param llmProvider      大模型提供商+型号（如 "qwen-max", "gpt-4o"）
 * @param temperature      生成温度（0.0 ~ 2.0）
 * @param topP             nucleus sampling 参数（0.0 ~ 1.0）
 * @param embeddingModel   Embedding 模型名称（如 "bge-m3"）
 * @param vectorStore      向量数据库类型（如 "InMemory", "Qdrant"）
 * @param maxConcurrency   最大并发评测数（控制 Orchestrator 在途请求）
 * @param rpmLimit         每分钟请求上限（RPM rate limit，防 LLM 厂商 429）
 * @param seed             随机种子（可为 null；设值后可提升可复现性，取决于 LLM API 支持）
 * @param maxTokens        最大输出 Token 数（可为 null；设值后限制 LLM 回答长度，P2-0.5C 新增）
 */
public record BenchmarkConfig(
        String experimentId,
        String workflowVersion,
        String datasetVersion,
        String llmProvider,
        double temperature,
        double topP,
        String embeddingModel,
        String vectorStore,
        int maxConcurrency,
        int rpmLimit,
        Integer seed,
        Integer maxTokens  // P2-0.5C: 新增
) {

    /** 默认并发限制 */
    public static final int DEFAULT_MAX_CONCURRENCY = 5;

    /** 默认 RPM 限制 */
    public static final int DEFAULT_RPM_LIMIT = 30;

    /**
     * 紧凑构造器：设置合理的默认值。
     */
    public BenchmarkConfig {
        if (maxConcurrency <= 0) {
            maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        }
        if (rpmLimit <= 0) {
            rpmLimit = DEFAULT_RPM_LIMIT;
        }
    }

    /**
     * 生成用于实验隔离的 memoryId 前缀。
     * 格式: {experimentId}_{workflowVersion}_
     */
    public String toIsolationPrefix() {
        return experimentId + "_" + workflowVersion + "_";
    }

    /**
     * 生成实验的唯一标识键（用于 A/B 测试对比）。
     */
    public String experimentKey() {
        return experimentId + "@" + llmProvider + ":" + workflowVersion;
    }

    /**
     * 返回作为 Map entry 的超参数列表（用于报告的可序列化输出）。
     */
    public List<String> hyperparameterEntries() {
        return List.of(
                "llmProvider=" + llmProvider,
                "temperature=" + temperature,
                "topP=" + topP,
                "embeddingModel=" + embeddingModel,
                "vectorStore=" + vectorStore,
                "maxConcurrency=" + maxConcurrency,
                "rpmLimit=" + rpmLimit,
                "seed=" + (seed != null ? seed : "null"),
                "maxTokens=" + (maxTokens != null ? maxTokens : "null")
        );
    }
}
