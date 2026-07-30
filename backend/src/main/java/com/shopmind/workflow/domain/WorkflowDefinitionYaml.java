package com.shopmind.workflow.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * YAML → WorkflowDefinition 转换器 — 将 SnakeYAML 泛型 Map 转换为领域对象。
 * <p>
 * <b>为什么不用 SnakeYAML 的 {@code Constructor(Class)} 类型绑定？</b>
 * Spring Boot 3.2.x 内置的 SnakeYAML 2.x 中，{@code Constructor(Class)}
 * 被标记为 deprecated，新 API ({@code LoaderOptions}) 在不同小版本间
 * 包路径不稳定。使用泛型 {@code Map<String, Object>} 加载 + 手动转换
 * 是兼容性最好、可测试性最强的方案。
 * <p>
 * <b>YAML Schema：</b>
 * <pre>
 * id: customer-service
 * version: v2.0
 * persona: |
 *   system prompt text...
 * toolRules:
 *   - toolName: queryOrder
 *     description: Query order status
 *     required: false
 * constraints:
 *   - name: no_hallucination
 *     content: Never fabricate information
 *     level: HARD
 * </pre>
 * <p>
 * <b>线程安全：</b>纯函数，无状态，所有数据通过参数传入。
 *
 * @see WorkflowDefinitionLoader
 * @see ToolRule
 * @see Policy
 */
public final class WorkflowDefinitionYaml {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionYaml.class);

    // ---- YAML key constants ----
    private static final String KEY_ID = "id";
    private static final String KEY_VERSION = "version";
    private static final String KEY_PERSONA = "persona";
    private static final String KEY_TOOL_RULES = "toolRules";
    private static final String KEY_CONSTRAINTS = "constraints";

    // ---- ToolRule sub-keys ----
    private static final String KEY_TOOL_NAME = "toolName";
    private static final String KEY_TOOL_DESC = "description";
    private static final String KEY_TOOL_REQUIRED = "required";

    // ---- Policy sub-keys ----
    private static final String KEY_POLICY_NAME = "name";
    private static final String KEY_POLICY_CONTENT = "content";
    private static final String KEY_POLICY_LEVEL = "level";

    private WorkflowDefinitionYaml() { /* utility class */ }

    /**
     * 将 SnakeYAML 加载的泛型 Map 转换为不可变 WorkflowDefinition。
     * <p>
     * 对所有字段提供默认值兜底，不会因 YAML 缺少字段而抛异常。
     *
     * @param map SnakeYAML {@code yaml.load(inputStream)} 返回的顶层 Map
     * @return 完整的 WorkflowDefinition，toolRules/constraints 可能为空列表
     */
    public static WorkflowDefinition fromMap(Map<String, Object> map) {
        String id = getString(map, KEY_ID, "unknown");
        String version = getString(map, KEY_VERSION, "v0.0");
        String persona = getString(map, KEY_PERSONA, "");

        List<ToolRule> toolRules = parseToolRules(map.get(KEY_TOOL_RULES));
        List<Policy> constraints = parseConstraints(map.get(KEY_CONSTRAINTS));

        log.debug("[WorkflowYaml] Parsed workflow: id={}, version={}, personaChars={}, toolRules={}, constraints={}",
                id, version, persona.length(), toolRules.size(), constraints.size());

        return new WorkflowDefinition(id, version, persona, toolRules, constraints);
    }

    // ============================================================
    //  ToolRule 解析
    // ============================================================

    /**
     * 从 YAML 中的 toolRules 列表解析为 ToolRule 领域对象列表。
     * <p>
     * YAML 格式：
     * <pre>
     * toolRules:
     *   - toolName: queryOrder
     *     description: Query order by ID
     *     required: true
     * </pre>
     *
     * @param raw SnakeYAML 解析出的原始对象（应为 {@code List<Map<String, Object>>}）
     * @return 不可变 ToolRule 列表，解析失败或为空时返回空列表
     */
    @SuppressWarnings("unchecked")
    static List<ToolRule> parseToolRules(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }

        List<ToolRule> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                log.warn("[WorkflowYaml] Skipping non-map toolRule entry: {}", item);
                continue;
            }
            Map<String, Object> ruleMap = (Map<String, Object>) itemMap;

            String toolName = getString(ruleMap, KEY_TOOL_NAME, null);
            if (toolName == null || toolName.isBlank()) {
                log.warn("[WorkflowYaml] Skipping toolRule with missing toolName");
                continue;
            }

            String description = getString(ruleMap, KEY_TOOL_DESC, "");
            boolean required = getBoolean(ruleMap, KEY_TOOL_REQUIRED, false);

            result.add(new ToolRule(toolName, description, required));
        }
        return Collections.unmodifiableList(result);
    }

    // ============================================================
    //  Policy (约束) 解析
    // ============================================================

    /**
     * 从 YAML 中的 constraints 列表解析为 Policy 领域对象列表。
     * <p>
     * YAML 格式：
     * <pre>
     * constraints:
     *   - name: no_hallucination
     *     content: Never fabricate. Only use retrieved knowledge.
     *     level: HARD
     *   - name: polite_tone
     *     content: Maintain a polite, professional tone.
     *     level: SOFT
     * </pre>
     *
     * @param raw SnakeYAML 解析出的原始对象（应为 {@code List<Map<String, Object>>}）
     * @return 不可变 Policy 列表，解析失败或为空时返回空列表
     */
    @SuppressWarnings("unchecked")
    static List<Policy> parseConstraints(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }

        List<Policy> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                log.warn("[WorkflowYaml] Skipping non-map constraint entry: {}", item);
                continue;
            }
            Map<String, Object> policyMap = (Map<String, Object>) itemMap;

            String name = getString(policyMap, KEY_POLICY_NAME, null);
            if (name == null || name.isBlank()) {
                log.warn("[WorkflowYaml] Skipping constraint with missing name");
                continue;
            }

            String content = getString(policyMap, KEY_POLICY_CONTENT, "");
            PolicyLevel level = parsePolicyLevel(getString(policyMap, KEY_POLICY_LEVEL, "SOFT"));

            result.add(new Policy(name, content, level));
        }
        return Collections.unmodifiableList(result);
    }

    // ============================================================
    //  Utility helpers
    // ============================================================

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? defaultValue : str;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        // YAML 可能解析 "true" 为字符串
        return Boolean.parseBoolean(value.toString());
    }

    private static PolicyLevel parsePolicyLevel(String level) {
        if (level == null) return PolicyLevel.SOFT;
        try {
            return PolicyLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[WorkflowYaml] Unknown policy level '{}', defaulting to SOFT", level);
            return PolicyLevel.SOFT;
        }
    }
}
