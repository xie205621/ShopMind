package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;

/**
 * Final formal experiment configuration — Phase 5-E1（B2）。
 * <p>
 * 正式 runner 的唯一正式 {@link BenchmarkConfig} 来源。冻结值：
 * <ul>
 *   <li>{@code llmProvider = "qwen-max"}</li>
 *   <li>{@code seed = null}</li>
 *   <li>{@code maxTokens = null}</li>
 *   <li>{@code temperature = 0.1}</li>
 *   <li>{@code topP = 0.9}</li>
 *   <li>{@code workflowVersion = "v2.3"}（customer-service v2.3）</li>
 * </ul>
 * <p>
 * <b>约束：</b>不通过修改 application.yml 默认模型来掩盖 formal runner 缺失；formal runner
 * 显式注入 {@code qwen-max}。{@code seed=null} 仅作为 config 元数据保留，不发送给 DashScope；
 * {@code maxTokens=null} 不得发送 {@code max_tokens}。
 */
public final class RtmpFormalExperimentConfig {

    /** 默认正式实验标识。 */
    public static final String DEFAULT_EXPERIMENT_ID = "RTMP-EXP01";

    /** 冻结的正式模型。 */
    public static final String MODEL = "qwen-max";

    public static final double TEMPERATURE = 0.1;
    public static final double TOP_P = 0.9;
    public static final String WORKFLOW_VERSION = "v2.3";
    public static final String EMBEDDING_MODEL = "bge-m3";
    public static final String VECTOR_STORE = "InMemory";

    private RtmpFormalExperimentConfig() {
    }

    /**
     * 构造唯一正式 {@link BenchmarkConfig}（seed / maxTokens 恒为 null）。
     */
    public static BenchmarkConfig build(String experimentId) {
        return new BenchmarkConfig(
                experimentId,
                WORKFLOW_VERSION,
                RtmpDatasetLoader.EXPECTED_VERSION,
                MODEL,
                TEMPERATURE,
                TOP_P,
                EMBEDDING_MODEL,
                VECTOR_STORE,
                BenchmarkConfig.DEFAULT_MAX_CONCURRENCY,
                BenchmarkConfig.DEFAULT_RPM_LIMIT,
                null,  // seed
                null); // maxTokens
    }
}
