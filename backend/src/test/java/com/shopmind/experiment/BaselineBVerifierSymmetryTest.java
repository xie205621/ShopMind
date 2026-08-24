package com.shopmind.experiment;

import com.shopmind.orchestrator.domain.OrchestrationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-C1：B/C Information Symmetry 与 GT Leakage 研究效度测试（§13/§14/§15）。
 * <p>
 * 证明 Method C Router 与 Baseline B Verifier 在给定同一份 {@link RuntimeSessionContext}
 * 时看到相同的 runtimeAuthorization / runtimeTargetScope，且两者都不直接依赖 RtmpTestCase GT。
 */
class BaselineBVerifierSymmetryTest {

    private final RouterContextFactory factory = new RouterContextFactory();
    private final RuntimeContextRiskScorer scorer = new RuntimeContextRiskScorer();
    private final ToolSafetyVerifier verifier = ExperimentCondition.BASELINE_B.safetyVerifier();

    @Test
    @DisplayName("Router 从 RouterContext 获得 runtimeAuthorization")
    void routerReadsRuntimeAuthorization() {
        RuntimeSessionContext sc = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        RouterContext rc = factory.build(new OrchestrationContext("m", "退款"), List.of(), sc);
        assertEquals(Optional.of(RuntimeAuthorization.USER), rc.runtimeAuthorization());
    }

    @Test
    @DisplayName("Router 从 RouterContext 获得 runtimeTargetScope")
    void routerReadsRuntimeTargetScope() {
        RuntimeSessionContext sc = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        RouterContext rc = factory.build(new OrchestrationContext("m", "退款"), List.of(), sc);
        assertEquals(Optional.of(RuntimeTargetScope.OTHER_USER), rc.runtimeTargetScope());
    }

    @Test
    @DisplayName("B 与 C 消费同一 RuntimeSessionContext（值相同）")
    void verifierReadsSameRuntimeContext() {
        RuntimeSessionContext sc = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);

        // B：verifier 直接消费同一 RuntimeSessionContext
        SafetyDecision b = verifier.verify(new SafetyVerificationRequest(sc, "refund", Map.of()));

        // C：Router 从同一 RuntimeSessionContext 派生 RouterContext
        RouterContext rc = factory.build(new OrchestrationContext("m", "退款"), List.of(), sc);

        assertEquals(sc.runtimeAuthorization(), rc.runtimeAuthorization().orElse(null));
        assertEquals(sc.runtimeTargetScope(), rc.runtimeTargetScope().orElse(null));
        assertFalse(b.allowed(), "B 应拦截 OTHER_USER 的 refund");
        assertEquals(1.0, scorer.score(rc, "refund"), 1e-9);
    }

    @Test
    @DisplayName("同一 runtime context 下 B 拦截 / C 高风险，判定方向一致")
    void bcDecisionDirectionConsistent() {
        // OTHER_USER：B 拦截 refund；C 对 refund 高风险（裁剪）
        RuntimeSessionContext otherUser = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        assertFalse(verifier.verify(new SafetyVerificationRequest(otherUser, "refund", Map.of())).allowed());
        assertEquals(1.0, scorer.score(
                factory.build(new OrchestrationContext("m", "退款"), List.of(), otherUser), "refund"), 1e-9);

        // OWN_DATA：B 放行 refund；C 对 refund 低风险（保留）
        RuntimeSessionContext ownData = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OWN_DATA);
        assertTrue(verifier.verify(new SafetyVerificationRequest(ownData, "refund", Map.of())).allowed());
        assertEquals(0.0, scorer.score(
                factory.build(new OrchestrationContext("m", "退款"), List.of(), ownData), "refund"), 1e-9);
    }

    @Test
    @DisplayName("C1.1 OTHER_USER 下四工具 B/C 对称（B 拦截 / C 裁剪，均基于 OWN_DATA 能力审计）")
    void otherUserFourToolsBcSymmetric() {
        RuntimeSessionContext otherUser = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        RouterContext rc = factory.build(new OrchestrationContext("m", "操作他人数据"), List.of(), otherUser);

        for (String tool : List.of("queryOrder", "refund", "queryPoints", "queryCoupons")) {
            assertFalse(verifier.verify(new SafetyVerificationRequest(otherUser, tool, Map.of())).allowed(),
                    "B 应拦截 OTHER_USER 的 " + tool);
            assertEquals(1.0, scorer.score(rc, tool), 1e-9,
                    "C 应对 OTHER_USER 的 " + tool + " 高风险裁剪");
        }
    }

    @Test
    @DisplayName("SafetyVerificationRequest 不携带 RtmpTestCase GT 字段")
    void verifierRequestHasNoGtField() {
        boolean hasGt = Arrays.stream(SafetyVerificationRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(n -> n.equals("groundTruth") || n.equals("testCase") || n.equals("rtmpTestCase"));
        assertFalse(hasGt, "SafetyVerificationRequest 不得携带 GT 字段");
    }

    @Test
    @DisplayName("RouterContext 不携带 RtmpTestCase.authorization / riskLabel（无 GT 依赖）")
    void routerContextHasNoGtAuthorization() {
        boolean has = Arrays.stream(RouterContext.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(n -> n.equals("authorization") || n.equals("riskLabel") || n.equals("groundTruth"));
        assertFalse(has, "RouterContext 不得携带 GT authorization/riskLabel/groundTruth");
    }
}
