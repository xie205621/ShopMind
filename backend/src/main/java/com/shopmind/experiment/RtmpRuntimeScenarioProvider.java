package com.shopmind.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * RTMP Runtime Scenario Provider — Phase 5-C1。
 * <p>
 * 从独立 fixture {@code datasets/rtmp_v1/rtmp_runtime_scenarios_v1.json} 加载
 * 42 条「会话环境事实」，实现 {@link RuntimeSessionContextProvider}。
 * <p>
 * <b>与 GT 严格隔离：</b>本 fixture 是物理独立的资源文件，字段为
 * {@code authenticatedPrincipal / runtimeAuthorization / runtimeTargetScope}，
 * 不携带 riskLabel / expectedTool / contextRisk 等任何 GT 字段。provenance 字段
 * 逐条回答「该 runtime 值是什么环境事实」，支持防泄漏审计。
 * <p>
 * 校验失败（version / count / caseId 唯一 / 非法枚举值 / 缺 provenance）时抛出异常，
 * 不允许静默降级为部分 fixture。
 */
public final class RtmpRuntimeScenarioProvider implements RuntimeSessionContextProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIXTURE_PATH =
            "classpath:datasets/rtmp_v1/rtmp_runtime_scenarios_v1.json";
    private static final String EXPECTED_VERSION = "rtmp_runtime_v1.0";
    private static final int EXPECTED_CASE_COUNT = 42;

    private static volatile RtmpRuntimeScenarioProvider INSTANCE;

    private final Map<String, RuntimeSessionContext> contexts;

    private RtmpRuntimeScenarioProvider(Map<String, RuntimeSessionContext> contexts) {
        this.contexts = contexts;
    }

    /** 懒加载单例（fixture 只加载一次，避免 378-run 重复解析）。 */
    public static RtmpRuntimeScenarioProvider load() {
        RtmpRuntimeScenarioProvider local = INSTANCE;
        if (local == null) {
            synchronized (RtmpRuntimeScenarioProvider.class) {
                local = INSTANCE;
                if (local == null) {
                    local = doLoad();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    @Override
    public RuntimeSessionContext resolve(String caseId) {
        if (caseId == null) {
            return null;
        }
        return contexts.get(caseId);
    }

    private static RtmpRuntimeScenarioProvider doLoad() {
        JsonNode root = readRoot();
        validateVersion(root);
        JsonNode scenariosNode = root.get("scenarios");
        if (scenariosNode == null || !scenariosNode.isArray()) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Missing or invalid 'scenarios' array");
        }

        Map<String, RuntimeSessionContext> contexts = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : scenariosNode) {
            String caseId = requiredText(node, "caseId");
            if (!ids.add(caseId)) {
                throw new IllegalStateException(
                        "[RtmpRuntimeScenarioProvider] Duplicate caseId: '" + caseId + "'");
            }
            String principal = nullableText(node, "authenticatedPrincipal");
            RuntimeAuthorization authorization = parseEnum(
                    node, "runtimeAuthorization", RuntimeAuthorization.class, caseId);
            RuntimeTargetScope targetScope = parseEnum(
                    node, "runtimeTargetScope", RuntimeTargetScope.class, caseId);
            String provenance = requiredText(node, "provenance");

            contexts.put(caseId, new RuntimeSessionContext(principal, authorization, targetScope));
        }

        if (contexts.size() != EXPECTED_CASE_COUNT) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Scenario count mismatch: " + contexts.size()
                            + " (expected " + EXPECTED_CASE_COUNT + ")");
        }
        return new RtmpRuntimeScenarioProvider(Map.copyOf(contexts));
    }

    private static JsonNode readRoot() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource(FIXTURE_PATH);
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "[RtmpRuntimeScenarioProvider] Runtime scenario fixture not found: " + FIXTURE_PATH);
            }
            try (InputStream is = resource.getInputStream()) {
                return MAPPER.readTree(is);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Failed to read fixture: " + e.getMessage(), e);
        }
    }

    private static void validateVersion(JsonNode root) {
        if (root == null || !root.has("version") || !EXPECTED_VERSION.equals(root.get("version").asText())) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Missing/unexpected 'version' (expected "
                            + EXPECTED_VERSION + ")");
        }
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode node, String field,
                                                   Class<E> type, String caseId) {
        String raw = requiredText(node, field);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Invalid '" + field + "' value '" + raw
                            + "' for case " + caseId);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Missing required field '" + field + "'");
        }
        String text = node.get(field).asText();
        if (text.isBlank()) {
            throw new IllegalStateException(
                    "[RtmpRuntimeScenarioProvider] Blank required field '" + field + "'");
        }
        return text;
    }

    private static String nullableText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }
}
