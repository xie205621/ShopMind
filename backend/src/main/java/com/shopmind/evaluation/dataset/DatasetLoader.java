package com.shopmind.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.evaluation.domain.DatasetScenario;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.evaluation.domain.FailureReason;
import com.shopmind.evaluation.domain.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集加载器 — Phase D: 从 JSON 文件加载版本化的评测数据集。
 * <p>
 * <b>设计原则：</b>
 * <ul>
 *   <li>数据集版本化：{@code datasets/v1.0/}、{@code datasets/v1.1/}</li>
 *   <li>场景分类：每个 JSON 文件对应一个 {@link DatasetScenario}</li>
 *   <li>不可变性：加载后返回不可变对象</li>
 *   <li>全量合并：{@link #loadAllMerged(String)} 将所有场景合并为单一数据集</li>
 * </ul>
 * <p>
 * <b>JSON Schema（v1.0）：</b>
 * <pre>
 * {
 *   "version": "v1.0",
 *   "scenario": "NORMAL",
 *   "description": "...",
 *   "cases": [
 *     {
 *       "id": "NORMAL-001",
 *       "query": "...",
 *       "expectedIntent": "return_policy",
 *       "expectedTool": null,
 *       "expectedKnowledge": ["7天"],
 *       "expectedAnswer": "...",
 *       "expectedFailureReason": "WRONG_INTENT",
 *       "mockResponse": "..."
 *     }
 *   ]
 * }
 * </pre>
 * <p>
 * <b>线程安全：</b>无状态静态工具类。
 *
 * @see BenchmarkDatasetV1
 */
public final class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DATASETS_BASE = "datasets/";

    private DatasetLoader() { /* utility class */ }

    /**
     * 加载指定版本的所有场景数据集。
     *
     * @param version 数据集版本，如 "v1.0"
     * @return 按场景分类的不可变数据集列表
     */
    public static List<EvaluationDataset> load(String version) {
        String pattern = "classpath:" + DATASETS_BASE + version + "/*.json";
        List<EvaluationDataset> datasets = new ArrayList<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pattern);

            if (resources == null || resources.length == 0) {
                log.warn("[DatasetLoader] No dataset files found for version: {} (pattern: {})", version, pattern);
                return Collections.emptyList();
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                log.debug("[DatasetLoader] Loading: {}", filename);

                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = MAPPER.readTree(is);
                    DatasetScenario scenario = DatasetScenario.valueOf(
                            root.get("scenario").asText());

                    List<TestCase> cases = parseCases(root.get("cases"));
                    String datasetId = "benchmark_" + version + "_" + scenario.name().toLowerCase();

                    datasets.add(new EvaluationDataset(datasetId, scenario, cases));
                } catch (Exception e) {
                    log.error("[DatasetLoader] Failed to parse: {} — {}", filename, e.getMessage());
                }
            }

            log.info("[DatasetLoader] Loaded dataset {}: {} scenarios, {} total cases",
                    version, datasets.size(),
                    datasets.stream().mapToInt(EvaluationDataset::size).sum());

        } catch (IOException e) {
            log.error("[DatasetLoader] Failed to scan datasets for version: {} — {}", version, e.getMessage());
        }

        return Collections.unmodifiableList(datasets);
    }

    /**
     * 加载指定版本的所有用例，合并为单一 {@link EvaluationDataset}。
     * <p>
     * 适用于需要一次性跑全量 Benchmark 的场景。
     *
     * @param version 数据集版本，如 "v1.0"
     * @return 包含全部用例的合并数据集（scenario=NORMAL）
     */
    public static EvaluationDataset loadAllMerged(String version) {
        List<EvaluationDataset> datasets = load(version);
        List<TestCase> allCases = new ArrayList<>();
        int total = 0;

        for (EvaluationDataset ds : datasets) {
            allCases.addAll(ds.testCases());
            total += ds.size();
        }

        log.info("[DatasetLoader] Merged {} cases from {} scenarios (version={})",
                total, datasets.size(), version);

        return new EvaluationDataset("benchmark_" + version, DatasetScenario.NORMAL, allCases);
    }

    /**
     * 构建 Mock 响应映射表，供 Mock Orchestrator 使用。
     * <p>
     * 从 JSON 的 {@code mockResponse} 字段提取。TIMEOUT 和 SAFETY_BLOCKED 用例
     * 不在此映射中（由 Orchestrator 的 Flux.error 路径处理）。
     *
     * @param version 数据集版本
     * @return caseId → mockResponse 的不可变映射
     */
    public static Map<String, String> buildMockResponseMap(String version) {
        Map<String, String> map = new LinkedHashMap<>();

        for (EvaluationDataset ds : load(version)) {
            for (TestCase tc : ds.testCases()) {
                String mockResp = getMockResponseFromJson(tc.testCaseId(), version);
                if (mockResp != null) {
                    map.put(tc.testCaseId(), mockResp);
                }
            }
        }

        return Collections.unmodifiableMap(map);
    }

    /**
     * 从 JSON 中提取指定用例的超时 ID 列表。
     */
    public static List<String> extractTimeoutIds(String version) {
        return extractIdsByFailureReason(version, FailureReason.TIMEOUT);
    }

    /**
     * 从 JSON 中提取指定用例的安全拦截 ID 列表。
     */
    public static List<String> extractSafetyBlockedIds(String version) {
        return extractIdsByFailureReason(version, FailureReason.SAFETY_BLOCKED);
    }

    // ============================================================
    //  内部解析
    // ============================================================

    private static List<TestCase> parseCases(JsonNode casesNode) {
        if (casesNode == null || !casesNode.isArray()) {
            return Collections.emptyList();
        }

        List<TestCase> cases = new ArrayList<>();
        for (JsonNode node : casesNode) {
            try {
                String id = node.get("id").asText();
                String query = node.get("query").asText();
                String expectedIntent = node.get("expectedIntent").asText();
                String expectedTool = node.has("expectedTool") && !node.get("expectedTool").isNull()
                        ? node.get("expectedTool").asText() : null;

                List<String> expectedKnowledge = new ArrayList<>();
                JsonNode knowledgeNode = node.get("expectedKnowledge");
                if (knowledgeNode != null && knowledgeNode.isArray()) {
                    for (JsonNode k : knowledgeNode) {
                        expectedKnowledge.add(k.asText());
                    }
                }

                String expectedAnswer = node.has("expectedAnswer") && !node.get("expectedAnswer").isNull()
                        ? node.get("expectedAnswer").asText() : null;

                FailureReason failureReason = null;
                if (node.has("expectedFailureReason") && !node.get("expectedFailureReason").isNull()) {
                    failureReason = FailureReason.valueOf(node.get("expectedFailureReason").asText());
                }

                cases.add(new TestCase(id, query, expectedIntent, expectedTool,
                        expectedKnowledge, expectedAnswer, failureReason));

            } catch (Exception e) {
                log.warn("[DatasetLoader] Skipping invalid case: {}", e.getMessage());
            }
        }

        return cases;
    }

    /**
     * 从原始 JSON 中提取单个用例的 mockResponse（不依赖 TestCase 模型）。
     * 因为 TestCase record 中没有 mockResponse 字段。
     */
    private static String getMockResponseFromJson(String caseId, String version) {
        String pattern = "classpath:" + DATASETS_BASE + version + "/*.json";

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pattern);

            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = MAPPER.readTree(is);
                    JsonNode cases = root.get("cases");
                    if (cases == null || !cases.isArray()) continue;

                    for (JsonNode node : cases) {
                        if (caseId.equals(node.get("id").asText())) {
                            if (node.has("mockResponse") && !node.get("mockResponse").isNull()) {
                                return node.get("mockResponse").asText();
                            }
                            return null;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[DatasetLoader] Failed to read mockResponse for {}: {}", caseId, e.getMessage());
        }

        return null;
    }

    private static List<String> extractIdsByFailureReason(String version, FailureReason reason) {
        List<String> ids = new ArrayList<>();
        for (EvaluationDataset ds : load(version)) {
            for (TestCase tc : ds.testCases()) {
                if (tc.expectedFailureReason() == reason) {
                    ids.add(tc.testCaseId());
                }
            }
        }
        return ids;
    }
}
