package com.shopmind.experiment;

import com.shopmind.orchestrator.domain.OrchestrationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-C1：RuntimeContextRiskScorer 的 runtime signal 优先级测试（§9 / §31 Risk）。
 * <p>
 * 验证 runtimeAuthorization / runtimeTargetScope 作为比 query pattern 更可靠的运行时风险来源，
 * 且 query pattern 仅在无 runtime signal 时回退。
 */
class RuntimeContextRiskScorerTest {

    private final RuntimeContextRiskScorer scorer = new RuntimeContextRiskScorer();

    private RouterContext withRuntime(RuntimeAuthorization auth, RuntimeTargetScope scope) {
        OrchestrationContext oc = new OrchestrationContext("mem", "帮我查一下订单");
        return new RouterContextFactory().build(oc, List.of(),
                new RuntimeSessionContext("p", auth, scope));
    }

    private RouterContext withQuery(String query) {
        OrchestrationContext oc = new OrchestrationContext("mem", query);
        return new RouterContextFactory().build(oc, List.of());
    }

    @Test
    @DisplayName("OWN_DATA refund → runtimeRisk=0（本人退款不越权）")
    void ownDataRefundRiskZero() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OWN_DATA);
        assertEquals(0.0, scorer.score(ctx, "refund"), 1e-9);
    }

    @Test
    @DisplayName("OTHER_USER refund → runtimeRisk=1（跨用户退款越权）")
    void otherUserRefundRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertEquals(1.0, scorer.score(ctx, "refund"), 1e-9);
    }

    @Test
    @DisplayName("OTHER_USER queryOrder → runtimeRisk=1")
    void otherUserQueryOrderRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertEquals(1.0, scorer.score(ctx, "queryOrder"), 1e-9);
    }

    @Test
    @DisplayName("OTHER_USER queryPoints → runtimeRisk=1（积分属账户数据，跨用户越权）")
    void otherUserQueryPointsRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertEquals(1.0, scorer.score(ctx, "queryPoints"), 1e-9);
    }

    @Test
    @DisplayName("UNAUTHORIZED → 所有工具 runtimeRisk=1")
    void unauthorizedAllToolsRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.UNAUTHORIZED, RuntimeTargetScope.OWN_DATA);
        assertEquals(1.0, scorer.score(ctx, "refund"), 1e-9);
        assertEquals(1.0, scorer.score(ctx, "queryPoints"), 1e-9);
    }

    @Test
    @DisplayName("SYSTEM_SCOPE → 所有工具 runtimeRisk=1")
    void systemScopeAllToolsRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.SYSTEM_SCOPE);
        assertEquals(1.0, scorer.score(ctx, "queryCoupons"), 1e-9);
    }

    @Test
    @DisplayName("runtime signal 优先于 query pattern（OTHER_USER 无关键词也触发）")
    void runtimeSignalOverridesQueryPattern() {
        // query 不含任何越权关键词，但 runtimeTargetScope=OTHER_USER 仍触发 refund 风险
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertEquals(1.0, scorer.score(ctx, "refund"), 1e-9);
    }

    @Test
    @DisplayName("query-only 回退（无 runtime signal）：越权关键词触发 refund/queryOrder 风险")
    void queryPatternFallback() {
        RouterContext ctx = withQuery("帮我退款，这是别人的订单");
        assertEquals(1.0, scorer.score(ctx, "refund"), 1e-9);
        assertEquals(1.0, scorer.score(ctx, "queryOrder"), 1e-9);
        assertEquals(0.0, scorer.score(ctx, "queryPoints"), 1e-9);
    }

    @Test
    @DisplayName("risk mapping 确定（重复计算一致）")
    void deterministic() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        for (int i = 0; i < 5; i++) {
            assertEquals(1.0, scorer.score(ctx, "refund"), 1e-9);
            assertEquals(1.0, scorer.score(ctx, "queryPoints"), 1e-9);
        }
    }

    // ============================================================
    //  Phase 5-C1.1：tool-specific runtime risk mapping（§10 补全）
    // ============================================================

    @Test
    @DisplayName("C1.1 OWN_DATA queryOrder → runtimeRisk=0")
    void ownDataQueryOrderRiskZero() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OWN_DATA);
        assertEquals(0.0, scorer.score(ctx, "queryOrder"), 1e-9);
    }

    @Test
    @DisplayName("C1.1 OTHER_USER queryCoupons → runtimeRisk=1（优惠券属账户数据，跨用户越权）")
    void otherUserQueryCouponsRiskOne() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertEquals(1.0, scorer.score(ctx, "queryCoupons"), 1e-9);
    }

    @Test
    @DisplayName("C1.1 UNAUTHORIZED → 四工具均 1.0（能力审计：四工具 permissionScope 均为 OWN_DATA）")
    void unauthorizedDerivedFromOwnDataCapability() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.UNAUTHORIZED, RuntimeTargetScope.OWN_DATA);
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertEquals(1.0, scorer.score(ctx, tool), 1e-9,
                    "UNAUTHORIZED 下 " + tool + " 应越权");
        }
        // 能力审计证据：四工具 permissionScope 均为 OWN_DATA（需认证），故未认证不可访问任一工具
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertEquals("OWN_DATA", ToolStaticRiskCatalog.forTool(tool).orElseThrow().permissionScope());
        }
    }

    @Test
    @DisplayName("C1.1 OTHER_USER → 四工具均 1.0（能力审计：四工具 permissionScope 均为 OWN_DATA，跨用户访问账户数据越权）")
    void otherUserDerivedFromOwnDataCapability() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertEquals(1.0, scorer.score(ctx, tool), 1e-9,
                    "OTHER_USER 下 " + tool + " 应越权");
        }
    }

    @Test
    @DisplayName("C1.1 SYSTEM_SCOPE → 四工具均 1.0（能力审计：无工具 permissionScope == SYSTEM）")
    void systemScopeDerivedFromNoSystemCapability() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.SYSTEM_SCOPE);
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertEquals(1.0, scorer.score(ctx, tool), 1e-9,
                    "SYSTEM_SCOPE 下 " + tool + " 应越界");
        }
        // 能力审计证据：无任何生产工具具备 SYSTEM 能力
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertNotEquals("SYSTEM", ToolStaticRiskCatalog.forTool(tool).orElseThrow().permissionScope());
        }
    }

    @Test
    @DisplayName("C1.1 OWN_DATA → 四工具均 0.0（无全局传播，仅授权边界内不越权）")
    void ownDataNoGlobalPropagation() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.OWN_DATA);
        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertEquals(0.0, scorer.score(ctx, tool), 1e-9,
                    "OWN_DATA 下 " + tool + " 不应越权");
        }
    }

    @Test
    @DisplayName("C1.1 UNKNOWN target scope → 0.0（不确定目标不越权）")
    void unknownTargetScopeRiskZero() {
        RouterContext ctx = withRuntime(RuntimeAuthorization.USER, RuntimeTargetScope.UNKNOWN);
        assertEquals(0.0, scorer.score(ctx, "refund"), 1e-9);
    }
}
