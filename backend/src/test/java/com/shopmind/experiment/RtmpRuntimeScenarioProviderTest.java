package com.shopmind.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-C1：Runtime Session Context Provider 与 Runtime Fixture 研究效度测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>provider 从独立 fixture 解析正确的 runtime 会话环境事实；</li>
 *   <li>fixture 覆盖全部 42 个 GT caseId，且<b>不携带任何 GT 字段</b>（防泄漏）；</li>
 *   <li>每条 fixture 都有非空 provenance（可回答 provenance audit，§6/§15）。</li>
 * </ul>
 */
class RtmpRuntimeScenarioProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE_PATH =
            "classpath:datasets/rtmp_v1/rtmp_runtime_scenarios_v1.json";

    private final RtmpRuntimeScenarioProvider provider = RtmpRuntimeScenarioProvider.load();

    @Test
    @DisplayName("resolve RTMP-023 → UNAUTHORIZED / SYSTEM_SCOPE")
    void resolveUnauthorizedSystemScope() {
        RuntimeSessionContext ctx = provider.resolve("RTMP-023");
        assertNotNull(ctx, "fixture 必须包含 RTMP-023");
        assertEquals(RuntimeAuthorization.UNAUTHORIZED, ctx.runtimeAuthorization());
        assertEquals(RuntimeTargetScope.SYSTEM_SCOPE, ctx.runtimeTargetScope());
    }

    @Test
    @DisplayName("resolve RTMP-020 → USER / OTHER_USER")
    void resolveOtherUser() {
        RuntimeSessionContext ctx = provider.resolve("RTMP-020");
        assertNotNull(ctx, "fixture 必须包含 RTMP-020");
        assertEquals(RuntimeAuthorization.USER, ctx.runtimeAuthorization());
        assertEquals(RuntimeTargetScope.OTHER_USER, ctx.runtimeTargetScope());
    }

    @Test
    @DisplayName("resolve RTMP-001 → USER / OWN_DATA")
    void resolveOwnData() {
        RuntimeSessionContext ctx = provider.resolve("RTMP-001");
        assertNotNull(ctx, "fixture 必须包含 RTMP-001");
        assertEquals(RuntimeAuthorization.USER, ctx.runtimeAuthorization());
        assertEquals(RuntimeTargetScope.OWN_DATA, ctx.runtimeTargetScope());
    }

    @Test
    @DisplayName("resolve 未知 caseId → null（不伪造 runtime context）")
    void resolveUnknownReturnsNull() {
        assertNull(provider.resolve("RTMP-999"));
        assertNull(provider.resolve(null));
    }

    @Test
    @DisplayName("runtime fixture 覆盖 42 个 caseId 且不含任何 GT 字段")
    void fixtureCoversAllCaseIdsAndHasNoGtFields() {
        JsonNode scenarios = fixtureRoot().get("scenarios");
        assertNotNull(scenarios, "fixture 必须包含 scenarios 数组");
        assertTrue(scenarios.isArray());
        assertEquals(42, scenarios.size(), "fixture 必须覆盖 42 个 GT caseId");

        Set<String> ids = new HashSet<>();
        for (JsonNode s : scenarios) {
            String id = s.get("caseId").asText();
            assertTrue(ids.add(id), "caseId 重复: " + id);
            assertFalse(s.has("riskLabel"), "runtime fixture 不得含 GT riskLabel");
            assertFalse(s.has("expectedTool"), "runtime fixture 不得含 GT expectedTool");
            assertFalse(s.has("expectedOutcome"), "runtime fixture 不得含 GT expectedOutcome");
            assertFalse(s.has("expectedToolAction"), "runtime fixture 不得含 GT expectedToolAction");
            assertFalse(s.has("taskCategory"), "runtime fixture 不得含 GT taskCategory");
            assertFalse(s.has("contextRisk"), "runtime fixture 不得含 GT contextRisk");
            assertFalse(s.has("toolRiskProfile"), "runtime fixture 不得含 GT toolRiskProfile");
            assertFalse(s.has("candidateTools"), "runtime fixture 不得含 GT candidateTools");
            assertFalse(s.has("adversarial"), "runtime fixture 不得含 GT adversarial");
            assertFalse(s.has("mockResponse"), "runtime fixture 不得含 GT mockResponse");
        }
    }

    @Test
    @DisplayName("每条 runtime fixture 都有非空 provenance（provenance audit 可回答）")
    void everyScenarioHasNonBlankProvenance() {
        JsonNode scenarios = fixtureRoot().get("scenarios");
        for (JsonNode s : scenarios) {
            assertTrue(s.has("provenance"), "缺少 provenance: " + s.get("caseId").asText());
            assertFalse(s.get("provenance").asText().isBlank(),
                    "provenance 不得为空: " + s.get("caseId").asText());
        }
    }

    private JsonNode fixtureRoot() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource resource = resolver.getResource(FIXTURE_PATH);
            assertTrue(resource.exists(), "fixture 不存在: " + FIXTURE_PATH);
            try (InputStream is = resource.getInputStream()) {
                return MAPPER.readTree(is);
            }
        } catch (Exception e) {
            throw new AssertionError("读取 fixture 失败: " + e.getMessage(), e);
        }
    }
}
