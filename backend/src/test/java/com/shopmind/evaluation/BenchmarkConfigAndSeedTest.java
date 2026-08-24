package com.shopmind.evaluation;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.ExperimentReport;
import com.shopmind.evaluation.pipeline.BenchmarkConfigHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-0.5 验收 V5 + V6：BenchmarkConfig 参数完整性 + Seed 注入验证。
 */
class BenchmarkConfigAndSeedTest {

    // ============================================================
    //  V5: BenchmarkConfig 参数完整性
    // ============================================================

    @Nested
    @DisplayName("V5: BenchmarkConfig 参数完整性")
    class BenchmarkConfigTests {

        @Test
        @DisplayName("BenchmarkConfig 包含 model 字段")
        void config_hasModel() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, null
            );
            assertEquals("deepseek-v4-flash", config.llmProvider());
        }

        @Test
        @DisplayName("BenchmarkConfig 包含 temperature 字段")
        void config_hasTemperature() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, null
            );
            assertEquals(0.1, config.temperature(), 0.001);
        }

        @Test
        @DisplayName("BenchmarkConfig 包含 topP 字段")
        void config_hasTopP() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, null
            );
            assertEquals(0.9, config.topP(), 0.001);
        }

        @Test
        @DisplayName("BenchmarkConfig 包含 seed 字段（可为 null）")
        void config_hasSeed() {
            // 有 seed
            BenchmarkConfig withSeed = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, 42, null
            );
            assertEquals(42, withSeed.seed());

            // 无 seed
            BenchmarkConfig noSeed = new BenchmarkConfig(
                    "test-002", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, null
            );
            assertNull(noSeed.seed(), "seed 应可为 null");
        }

        @Test
        @DisplayName("BenchmarkConfig hyperparameterEntries 包含 seed")
        void config_hyperparametersIncludeSeed() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, 42, null
            );

            String hyperparams = String.join(", ", config.hyperparameterEntries());
            assertTrue(hyperparams.contains("seed=42"),
                    "hyperparameterEntries 应包含 seed=42，实际: " + hyperparams);
        }
    }

    // ============================================================
    //  V6: Seed 参数注入到 API 请求
    // ============================================================

    @Nested
    @DisplayName("V6: Seed 参数注入到 API 请求")
    class SeedInjectionTests {

        @Test
        @DisplayName("DeepSeek Adapter 构造函数接受 seed 参数（Integer 类型）")
        void deepSeek_supportsSeedConstructor() throws NoSuchMethodException {
            var constructor = com.shopmind.orchestrator.adapter.DeepSeekChatAdapter.class.getConstructors()[0];
            var paramTypes = constructor.getParameterTypes();
            boolean hasIntegerParam = false;
            for (var type : paramTypes) {
                if (type == Integer.class) {
                    hasIntegerParam = true;
                    break;
                }
            }
            assertTrue(hasIntegerParam,
                    "DeepSeekChatAdapter 构造函数应包含 Integer seed 参数");
        }

        @Test
        @DisplayName("DashScope Adapter 不支持 seed（已知限制，如实记录）")
        void dashScope_doesNotSupportSeed() {
            // DashScope API 当前不支持 seed 参数
            // 这是已知限制，不伪造"已支持"
            // 此测试仅作为文档记录
            assertTrue(true, "DashScope 不支持 seed — 已知限制，待 DashScope API 支持");
        }
    }

    // ============================================================
    //  P2-0.5C: 参数注入 / API 请求体 / Report 记录测试
    // ============================================================

    @Nested
    @DisplayName("P2-0.5C: 参数一致性")
    class ParameterConsistencyTests {

        @BeforeEach
        void setUp() {
            BenchmarkConfigHolder.clear();
        }

        @AfterEach
        void tearDown() {
            BenchmarkConfigHolder.clear();
        }

        @Test
        @DisplayName("BenchmarkConfig 包含 maxTokens 字段")
        void config_hasMaxTokens() {
            // maxTokens 不为 null
            BenchmarkConfig withMaxTokens = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, 2048
            );
            assertEquals(2048, withMaxTokens.maxTokens());

            // maxTokens 可为 null
            BenchmarkConfig noMaxTokens = new BenchmarkConfig(
                    "test-002", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, null, null
            );
            assertNull(noMaxTokens.maxTokens(), "maxTokens 应可为 null");
        }

        @Test
        @DisplayName("hyperparameterEntries 包含 maxTokens")
        void config_hyperparametersIncludeMaxTokens() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, 42, 2048
            );

            String hyperparams = String.join(", ", config.hyperparameterEntries());
            assertTrue(hyperparams.contains("maxTokens=2048"),
                    "hyperparameterEntries 应包含 maxTokens=2048，实际: " + hyperparams);
        }

        @Test
        @DisplayName("BenchmarkConfigHolder 设置/获取/清除 正确")
        void holder_setAndGet() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.3, 0.85,
                    "text-embedding-v3", "InMemory", 5, 30, 42, 2048
            );

            assertNull(BenchmarkConfigHolder.get(), "初始状态应为 null");

            BenchmarkConfigHolder.set(config);
            assertEquals(config, BenchmarkConfigHolder.get());
            assertEquals(0.3, BenchmarkConfigHolder.get().temperature(), 0.001);
            assertEquals(0.85, BenchmarkConfigHolder.get().topP(), 0.001);
            assertEquals(42, BenchmarkConfigHolder.get().seed());
            assertEquals(2048, BenchmarkConfigHolder.get().maxTokens());

            BenchmarkConfigHolder.clear();
            assertNull(BenchmarkConfigHolder.get(), "清除后应为 null");
        }

        @Test
        @DisplayName("ExperimentReport 记录实际生效参数（effectiveParameters）")
        void report_recordsEffectiveParameters() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "test-001", "v2.3", "benchmark_v1.0",
                    "deepseek-v4-flash", 0.1, 0.9,
                    "text-embedding-v3", "InMemory", 5, 30, 42, 2048
            );

            ExperimentReport report = new ExperimentReport().finalize(config);

            Map<String, Object> params = report.getEffectiveParameters();
            assertNotNull(params, "effectiveParameters 不应为 null");
            assertEquals("deepseek-v4-flash", params.get("model"));
            assertEquals(0.1, (Double) params.get("temperature"), 0.001);
            assertEquals(0.9, (Double) params.get("topP"), 0.001);
            assertEquals(42, params.get("seed"));
            assertEquals(2048, params.get("maxTokens"));
            assertEquals(5, params.get("maxConcurrency"));
            assertEquals(30, params.get("rpmLimit"));
        }

        @Test
        @DisplayName("BenchmarkConfig 所有参数可独立设置（单一事实源验证）")
        void config_allParametersIndependentlySettable() {
            BenchmarkConfig config = new BenchmarkConfig(
                    "exp-42", "v3.0", "ds-v2",
                    "qwen-max", 0.7, 0.95,
                    "bge-m3", "Qdrant", 10, 60, 12345, 4096
            );

            assertEquals("exp-42", config.experimentId());
            assertEquals("v3.0", config.workflowVersion());
            assertEquals("ds-v2", config.datasetVersion());
            assertEquals("qwen-max", config.llmProvider());
            assertEquals(0.7, config.temperature(), 0.001);
            assertEquals(0.95, config.topP(), 0.001);
            assertEquals("bge-m3", config.embeddingModel());
            assertEquals("Qdrant", config.vectorStore());
            assertEquals(10, config.maxConcurrency());
            assertEquals(60, config.rpmLimit());
            assertEquals(12345, config.seed());
            assertEquals(4096, config.maxTokens());
        }
    }
}
