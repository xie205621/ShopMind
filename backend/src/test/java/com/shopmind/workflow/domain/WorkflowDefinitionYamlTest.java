package com.shopmind.workflow.domain;

import com.shopmind.workflow.pipeline.WorkflowDefinitionLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowDefinitionYaml 单元测试 — 覆盖 fromMap / parseToolRules / parseConstraints 的所有场景。
 *
 * <p>Phase B 死代码补全后的测试回归，Phase C 保持通过这些测试。</p>
 */
@DisplayName("WorkflowDefinitionYaml — Map → 领域对象转换")
class WorkflowDefinitionYamlTest {

    // ============================================================
    //  fromMap — 基本场景
    // ============================================================

    @Test
    @DisplayName("完整 Map → 完整 WorkflowDefinition")
    void shouldParseCompleteMap() {
        Map<String, Object> map = Map.of(
                "id", "customer-service",
                "version", "v2.1",
                "persona", "你是智能客服",
                "toolRules", List.of(
                        Map.of("toolName", "queryOrder", "description", "查询订单", "required", true)
                ),
                "constraints", List.of(
                        Map.of("name", "no_hallucination", "content", "禁止编造", "level", "HARD")
                )
        );

        WorkflowDefinition wf = WorkflowDefinitionYaml.fromMap(map);

        assertEquals("customer-service", wf.id());
        assertEquals("v2.1", wf.version());
        assertEquals("你是智能客服", wf.persona());
        assertEquals(1, wf.toolRules().size());
        assertEquals(1, wf.constraints().size());

        ToolRule rule = wf.toolRules().get(0);
        assertEquals("queryOrder", rule.toolName());
        assertEquals("查询订单", rule.description());
        assertTrue(rule.required());

        Policy policy = wf.constraints().get(0);
        assertEquals("no_hallucination", policy.name());
        assertEquals("禁止编造", policy.content());
        assertEquals(PolicyLevel.HARD, policy.level());
    }

    @Test
    @DisplayName("空 Map → 默认值兜底")
    void shouldReturnDefaultsForEmptyMap() {
        WorkflowDefinition wf = WorkflowDefinitionYaml.fromMap(Collections.emptyMap());

        assertEquals("unknown", wf.id());
        assertEquals("v0.0", wf.version());
        assertEquals("", wf.persona());
        assertTrue(wf.toolRules().isEmpty());
        assertTrue(wf.constraints().isEmpty());
    }

    @Test
    @DisplayName("缺失字段 → 默认值兜底，不抛异常")
    void shouldUseDefaultsForMissingFields() {
        Map<String, Object> map = Map.of("id", "test-wf");

        WorkflowDefinition wf = WorkflowDefinitionYaml.fromMap(map);

        assertEquals("test-wf", wf.id());
        assertEquals("v0.0", wf.version());
        assertEquals("", wf.persona());
    }

    // ============================================================
    //  parseToolRules
    // ============================================================

    @Nested
    @DisplayName("parseToolRules")
    class ParseToolRules {

        @Test
        @DisplayName("null → 空列表")
        void shouldReturnEmptyForNull() {
            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(null);
            assertTrue(rules.isEmpty());
        }

        @Test
        @DisplayName("非 List 类型 → 空列表")
        void shouldReturnEmptyForNonList() {
            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules("not a list");
            assertTrue(rules.isEmpty());
        }

        @Test
        @DisplayName("空列表 → 空列表")
        void shouldReturnEmptyForEmptyList() {
            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(Collections.emptyList());
            assertTrue(rules.isEmpty());
        }

        @Test
        @DisplayName("缺少 toolName → 跳过该条")
        void shouldSkipRuleWithMissingToolName() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("description", "desc", "required", false) // 缺少 toolName
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertTrue(rules.isEmpty());
        }

        @Test
        @DisplayName("toolName 为空字符串 → 跳过该条")
        void shouldSkipRuleWithBlankToolName() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("toolName", "   ", "description", "desc")
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertTrue(rules.isEmpty());
        }

        @Test
        @DisplayName("required 缺失 → 默认 false")
        void shouldDefaultRequiredToFalse() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("toolName", "queryOrder", "description", "desc")
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertEquals(1, rules.size());
            assertFalse(rules.get(0).required());
        }

        @Test
        @DisplayName("required 为字符串 true → 解析为 boolean true")
        void shouldParseStringTrueAsBoolean() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("toolName", "queryOrder", "description", "desc", "required", "true")
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertTrue(rules.get(0).required());
        }

        @Test
        @DisplayName("多条 toolRule → 全部解析")
        void shouldParseMultipleRules() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("toolName", "queryOrder", "description", "查订单"),
                    Map.of("toolName", "refund", "description", "退款", "required", true)
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertEquals(2, rules.size());
            assertEquals("queryOrder", rules.get(0).toolName());
            assertEquals("refund", rules.get(1).toolName());
        }

        @Test
        @DisplayName("返回的列表不可修改")
        void shouldReturnUnmodifiableList() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("toolName", "queryOrder", "description", "desc")
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertThrows(UnsupportedOperationException.class, () -> rules.add(
                    new ToolRule("x", "y", false)));
        }

        @Test
        @DisplayName("列表中混入非 Map 元素 → 跳过")
        void shouldSkipNonMapEntries() {
            List<Object> raw = List.of(
                    Map.of("toolName", "queryOrder", "description", "desc"),
                    "not a map" // 非 Map 元素
            );

            List<ToolRule> rules = WorkflowDefinitionYaml.parseToolRules(raw);
            assertEquals(1, rules.size());
            assertEquals("queryOrder", rules.get(0).toolName());
        }
    }

    // ============================================================
    //  parseConstraints
    // ============================================================

    @Nested
    @DisplayName("parseConstraints")
    class ParseConstraints {

        @Test
        @DisplayName("null → 空列表")
        void shouldReturnEmptyForNull() {
            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(null);
            assertTrue(policies.isEmpty());
        }

        @Test
        @DisplayName("空列表 → 空列表")
        void shouldReturnEmptyForEmptyList() {
            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(Collections.emptyList());
            assertTrue(policies.isEmpty());
        }

        @Test
        @DisplayName("HARD 级别策略 → 正确解析")
        void shouldParseHardPolicy() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "no_hallucination", "content", "禁止编造", "level", "HARD")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(1, policies.size());
            assertEquals("no_hallucination", policies.get(0).name());
            assertEquals(PolicyLevel.HARD, policies.get(0).level());
        }

        @Test
        @DisplayName("SOFT 级别策略 → 正确解析")
        void shouldParseSoftPolicy() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "polite", "content", "语气友好", "level", "SOFT")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(PolicyLevel.SOFT, policies.get(0).level());
        }

        @Test
        @DisplayName("level 缺失 → 默认 SOFT")
        void shouldDefaultLevelToSoft() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "polite", "content", "语气友好")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(PolicyLevel.SOFT, policies.get(0).level());
        }

        @Test
        @DisplayName("level 非法值 → 默认 SOFT")
        void shouldDefaultInvalidLevelToSoft() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "test", "content", "test", "level", "INVALID")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(PolicyLevel.SOFT, policies.get(0).level());
        }

        @Test
        @DisplayName("level 小写 → 正确解析")
        void shouldParseLowercaseLevel() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "h", "content", "c", "level", "hard")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(PolicyLevel.HARD, policies.get(0).level());
        }

        @Test
        @DisplayName("name 缺失 → 跳过该条")
        void shouldSkipConstraintWithMissingName() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("content", "c", "level", "HARD") // 缺少 name
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertTrue(policies.isEmpty());
        }

        @Test
        @DisplayName("多条 Policy → 全部解析")
        void shouldParseMultiplePolicies() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "h", "content", "hard rule", "level", "HARD"),
                    Map.of("name", "s", "content", "soft rule", "level", "SOFT")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertEquals(2, policies.size());
        }

        @Test
        @DisplayName("返回的列表不可修改")
        void shouldReturnUnmodifiableList() {
            List<Map<String, Object>> raw = List.of(
                    Map.of("name", "h", "content", "c", "level", "HARD")
            );

            List<Policy> policies = WorkflowDefinitionYaml.parseConstraints(raw);
            assertThrows(UnsupportedOperationException.class, () -> policies.add(
                    new Policy("x", "y", PolicyLevel.SOFT)));
        }
    }

    // ============================================================
    //  集成测试 — 从真实 YAML 加载
    // ============================================================

    @Test
    @DisplayName("加载 v2.0.yaml → persona 非空，toolRules + constraints 为空")
    void shouldLoadV20FromClasspath() {
        WorkflowDefinition wf = WorkflowDefinitionLoader.load("customer-service", "v2.0");

        assertEquals("customer-service", wf.id());
        assertEquals("v2.0", wf.version());
        assertNotNull(wf.persona());
        assertFalse(wf.persona().isBlank());
        assertTrue(wf.toolRules().isEmpty());
        assertTrue(wf.constraints().isEmpty());
    }

    @Test
    @DisplayName("加载 v2.1.yaml → 含 toolRules + constraints")
    void shouldLoadV21FromClasspath() {
        WorkflowDefinition wf = WorkflowDefinitionLoader.load("customer-service", "v2.1");

        assertEquals("customer-service", wf.id());
        assertEquals("v2.1", wf.version());
        assertEquals(2, wf.toolRules().size());
        assertEquals("queryOrder", wf.toolRules().get(0).toolName());
        assertEquals("refund", wf.toolRules().get(1).toolName());
        assertEquals(2, wf.constraints().size());

        // 验证 HARD policy 方法
        assertEquals(1, wf.hardPolicies().size());
        assertEquals(1, wf.softPolicies().size());
        assertEquals("no_hallucination", wf.hardPolicies().get(0).name());
    }
}
