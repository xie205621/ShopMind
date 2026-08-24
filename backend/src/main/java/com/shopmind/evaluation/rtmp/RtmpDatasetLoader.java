package com.shopmind.evaluation.rtmp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RTMP 数据集加载器 — Phase 1 Evaluation/Instrumentation Foundation。
 * <p>
 * 加载 {@code datasets/rtmp_v1/rtmp_dataset_v1.json}，并执行严格校验：
 * <ol>
 *   <li>dataset version == {@code rtmp_v1.0}</li>
 *   <li>case count == 42</li>
 *   <li>caseId 唯一</li>
 *   <li>tool pool == 4 tools（queryOrder / refund / queryPoints / queryCoupons）</li>
 *   <li>taskCategory 分布严格为 8/6/8/6/6/4/4</li>
 *   <li>required fields 存在</li>
 * </ol>
 * <p>
 * <b>关键约束：</b>schema / count / distribution 校验失败时，加载器<b>抛出异常</b>
 * （而非 silently skip），确保非法 RTMP 数据集不会静默降级成部分数据集后继续实验。
 * <p>
 * mockResponse 可以加载，但只能作为 Mock 输入数据，不能被当作 runtime observation truth。
 */
public final class RtmpDatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(RtmpDatasetLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** RTMP 数据集资源（位于 test resources；精确匹配，避免与 runtime scenario fixture 混淆） */
    private static final String DATASET_PATTERN = "classpath:datasets/rtmp_v1/rtmp_dataset_v1.json";

    /** 冻结的 dataset version */
    public static final String EXPECTED_VERSION = "rtmp_v1.0";

    /** 冻结的 case count */
    public static final int EXPECTED_CASE_COUNT = 42;

    /** 冻结的工具池（顺序无关，但必须精确等于这 4 个工具） */
    public static final Set<String> EXPECTED_TOOL_POOL = Set.of(
            "queryOrder", "refund", "queryPoints", "queryCoupons");

    private RtmpDatasetLoader() { /* utility class */ }

    /**
     * 加载并校验 RTMP 数据集。
     *
     * @return 校验通过后构建的 {@link RtmpEvaluationDataset}
     * @throws IllegalStateException 当 schema / count / distribution 校验失败时
     */
    public static RtmpEvaluationDataset load() {
        JsonNode root = readRoot();
        validateVersion(root);
        JsonNode toolPoolNode = root.get("toolPool");
        List<String> toolPool = validateToolPool(toolPoolNode);

        List<RtmpTestCase> cases = parseAndValidateCases(root.get("cases"));

        return new RtmpEvaluationDataset(
                EXPECTED_VERSION,
                root.has("scenario") ? root.get("scenario").asText() : "RTMP_SAFETY_UTILITY",
                toolPool,
                cases);
    }

    // ============================================================
    //  读取 + 顶层校验
    // ============================================================

    private static JsonNode readRoot() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(DATASET_PATTERN);
            if (resources == null || resources.length == 0) {
                throw new IllegalStateException("[RtmpDatasetLoader] RTMP dataset not found: " + DATASET_PATTERN);
            }
            if (resources.length > 1) {
                throw new IllegalStateException(
                        "[RtmpDatasetLoader] Expected exactly one RTMP dataset file, found " + resources.length);
            }
            try (InputStream is = resources[0].getInputStream()) {
                return MAPPER.readTree(is);
            }
        } catch (IOException e) {
            throw new IllegalStateException("[RtmpDatasetLoader] Failed to read RTMP dataset: " + e.getMessage(), e);
        }
    }

    private static void validateVersion(JsonNode root) {
        if (root == null || !root.has("version")) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing 'version' field");
        }
        String version = root.get("version").asText();
        if (!EXPECTED_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Unexpected dataset version: '" + version
                            + "' (expected '" + EXPECTED_VERSION + "')");
        }
    }

    private static List<String> validateToolPool(JsonNode toolPoolNode) {
        if (toolPoolNode == null || !toolPoolNode.isArray()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing or invalid 'toolPool' field");
        }
        Set<String> actual = new HashSet<>();
        for (JsonNode n : toolPoolNode) {
            actual.add(n.asText());
        }
        if (!EXPECTED_TOOL_POOL.equals(actual)) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Invalid tool pool: " + actual + " (expected " + EXPECTED_TOOL_POOL + ")");
        }
        List<String> ordered = new ArrayList<>();
        for (JsonNode n : toolPoolNode) {
            ordered.add(n.asText());
        }
        return ordered;
    }

    // ============================================================
    //  case 解析 + 严格校验
    // ============================================================

    private static List<RtmpTestCase> parseAndValidateCases(JsonNode casesNode) {
        if (casesNode == null || !casesNode.isArray()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing or invalid 'cases' array");
        }

        List<RtmpTestCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<RtmpTaskCategory, Integer> distribution = new EnumMap<>(RtmpTaskCategory.class);

        for (JsonNode node : casesNode) {
            RtmpTestCase tc = parseCase(node);
            if (!ids.add(tc.id())) {
                throw new IllegalStateException(
                        "[RtmpDatasetLoader] Duplicate caseId: '" + tc.id() + "'");
            }
            cases.add(tc);
            distribution.merge(tc.taskCategory(), 1, Integer::sum);
        }

        // case count == 42
        if (cases.size() != EXPECTED_CASE_COUNT) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Case count mismatch: " + cases.size()
                            + " (expected " + EXPECTED_CASE_COUNT + ")");
        }

        // 7 类分布严格为 8/6/8/6/6/4/4
        for (RtmpTaskCategory category : RtmpTaskCategory.values()) {
            int actual = distribution.getOrDefault(category, 0);
            if (actual != category.pilotCount()) {
                throw new IllegalStateException(
                        "[RtmpDatasetLoader] taskCategory distribution mismatch for " + category
                                + ": " + actual + " (expected " + category.pilotCount() + ")");
            }
        }

        log.info("[RtmpDatasetLoader] Loaded RTMP dataset: {} cases, 7-category distribution OK", cases.size());
        return cases;
    }

    private static RtmpTestCase parseCase(JsonNode node) {
        String id = requiredText(node, "id");
        String query = requiredText(node, "query");

        String taskCategoryRaw = requiredText(node, "taskCategory");
        RtmpTaskCategory taskCategory = parseTaskCategory(id, taskCategoryRaw);

        String expectedOutcome = requiredText(node, "expectedOutcome");
        String expectedTool = nullableText(node, "expectedTool");
        String expectedToolActionRaw = requiredText(node, "expectedToolAction");
        ExpectedToolAction expectedToolAction = parseExpectedToolAction(id, expectedToolActionRaw);

        List<String> candidateTools = requiredStringList(node, "candidateTools", id);
        ToolRiskProfile toolRiskProfile = parseToolRiskProfile(node, id);
        ContextRisk contextRisk = parseContextRisk(node, id);

        String authorization = requiredText(node, "authorization");
        String riskLabel = requiredText(node, "riskLabel");

        if (!node.has("adversarial") || !node.get("adversarial").isBoolean()) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Missing/invalid 'adversarial' for case " + id);
        }
        boolean adversarial = node.get("adversarial").asBoolean();

        String expectedReason = nullableText(node, "expectedReason");
        String mockResponse = nullableText(node, "mockResponse");

        List<String> expectedToolSequence = parseExpectedToolSequence(
                node, id, taskCategory, expectedTool, expectedToolAction);

        return new RtmpTestCase(
                id, query, taskCategory, expectedOutcome, expectedTool, expectedToolAction,
                candidateTools, toolRiskProfile, contextRisk, authorization, riskLabel,
                adversarial, expectedReason, mockResponse, expectedToolSequence);
    }

    /**
     * Phase 5-C1.1：解析合法工具执行序列（explicit GT）。
     * <p>
     * MULTI_TOOL case 必须在 JSON 中显式给出 {@code expectedToolSequence}（≥2）；
     * 非 MULTI_TOOL case 由 expectedToolAction / expectedTool 派生：
     * CALL → {@code [expectedTool]}，NOT_CALL → {@code []}。
     * <p>
     * 禁止从 query 文本 / riskLabel 自动标注合法工具（这会再次把 evaluator 变成 heuristic）。
     */
    private static List<String> parseExpectedToolSequence(JsonNode node, String id,
                                                          RtmpTaskCategory category,
                                                          String expectedTool,
                                                          ExpectedToolAction action) {
        List<String> sequence;
        if (node.has("expectedToolSequence") && !node.get("expectedToolSequence").isNull()) {
            sequence = requiredStringList(node, "expectedToolSequence", id);
        } else {
            if (category == RtmpTaskCategory.MULTI_TOOL) {
                throw new IllegalStateException(
                        "[RtmpDatasetLoader] MULTI_TOOL case " + id
                                + " must declare explicit 'expectedToolSequence'");
            }
            sequence = (action == ExpectedToolAction.CALL && expectedTool != null)
                    ? List.of(expectedTool)
                    : List.of();
        }
        validateExpectedToolSequence(id, category, action, sequence);
        return sequence;
    }

    /**
     * Phase 5-C1.1 dataset schema validation（§20）：
     * <ul>
     *   <li>sequence 每个元素必须是合法生产工具；</li>
     *   <li>CALL → 非空；NOT_CALL → 空；</li>
     *   <li>MULTI_TOOL → ≥2；非 MULTI_TOOL → ≤1。</li>
     * </ul>
     * 任一不满足即抛出 dataset schema blocker（不得静默修正）。
     */
    private static void validateExpectedToolSequence(String id, RtmpTaskCategory category,
                                                     ExpectedToolAction action, List<String> sequence) {
        for (String tool : sequence) {
            if (!EXPECTED_TOOL_POOL.contains(tool)) {
                throw new IllegalStateException(
                        "[RtmpDatasetLoader] Unknown tool in expectedToolSequence for case " + id + ": " + tool);
            }
        }
        if (action == ExpectedToolAction.CALL && sequence.isEmpty()) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] CALL case " + id + " has empty expectedToolSequence");
        }
        if (action == ExpectedToolAction.NOT_CALL && !sequence.isEmpty()) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] NOT_CALL case " + id + " has non-empty expectedToolSequence");
        }
        if (category == RtmpTaskCategory.MULTI_TOOL && sequence.size() < 2) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] MULTI_TOOL case " + id + " requires >= 2 expectedToolSequence");
        }
        if (category != RtmpTaskCategory.MULTI_TOOL && sequence.size() > 1) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] non-MULTI_TOOL case " + id + " requires <= 1 expectedToolSequence");
        }
    }

    private static ToolRiskProfile parseToolRiskProfile(JsonNode node, String id) {
        JsonNode p = node.get("toolRiskProfile");
        if (p == null || !p.isObject()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing 'toolRiskProfile' for case " + id);
        }
        return new ToolRiskProfile(
                requiredFieldText(p, "sideEffect", id, "toolRiskProfile"),
                requiredFieldText(p, "financialImpact", id, "toolRiskProfile"),
                requiredFieldText(p, "reversibility", id, "toolRiskProfile"),
                requiredFieldText(p, "dataSensitivity", id, "toolRiskProfile"),
                requiredFieldText(p, "permissionScope", id, "toolRiskProfile"));
    }

    private static ContextRisk parseContextRisk(JsonNode node, String id) {
        JsonNode c = node.get("contextRisk");
        if (c == null || !c.isObject()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing 'contextRisk' for case " + id);
        }
        return new ContextRisk(
                requiredFieldText(c, "intentConfidence", id, "contextRisk"),
                requiredFieldText(c, "authorization", id, "contextRisk"),
                requiredFieldText(c, "targetScope", id, "contextRisk"),
                requiredFieldText(c, "requestType", id, "contextRisk"));
    }

    private static RtmpTaskCategory parseTaskCategory(String id, String raw) {
        try {
            return RtmpTaskCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Unknown taskCategory '" + raw + "' for case " + id);
        }
    }

    private static ExpectedToolAction parseExpectedToolAction(String id, String raw) {
        try {
            return ExpectedToolAction.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Unknown expectedToolAction '" + raw + "' for case " + id);
        }
    }

    // ============================================================
    //  字段读取 helpers（required fields 缺失即抛异常）
    // ============================================================

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Missing required field '" + field + "'");
        }
        String text = node.get(field).asText();
        if (text.isBlank()) {
            throw new IllegalStateException("[RtmpDatasetLoader] Blank required field '" + field + "'");
        }
        return text;
    }

    private static String requiredFieldText(JsonNode parent, String field, String id, String parentName) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Missing required field '" + parentName + "." + field + "' for case " + id);
        }
        return parent.get(field).asText();
    }

    private static String nullableText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static List<String> requiredStringList(JsonNode node, String field, String id) {
        if (node == null || !node.has(field) || !node.get(field).isArray()) {
            throw new IllegalStateException(
                    "[RtmpDatasetLoader] Missing required array field '" + field + "' for case " + id);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode n : node.get(field)) {
            result.add(n.asText());
        }
        return result;
    }
}