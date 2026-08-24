package com.shopmind.experiment;

import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.port.IntentAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 — P4-3 验收测试：RtmpVisibility + ToolMenuPruner（candidate → visibleTools）。
 * <p>
 * 验证 P4-2.1 冻结的 {@link ToolScoreResult} → {@link ToolDecisionCandidate}
 * 真正落实为 visibleTools，并保证：
 * <ul>
 *   <li>KEEP_CANDIDATE → visible；PRUNE_CANDIDATE → hidden</li>
 *   <li>合法高风险 refund 不被默认裁剪（effectiveRisk=0.5 仍 KEEP）</li>
 *   <li>越权 refund/queryOrder 被裁剪；无关低风险工具不因全局 risk 被机械裁剪</li>
 *   <li>empty-tool-set 是有效结果，不 fallback 恢复任何工具</li>
 *   <li>pruningDecision 可观测（复用 ToolScoreResult，无第二套 DTO）</li>
 *   <li>RouterContext 不携带任何 Ground Truth 字段</li>
 * </ul>
 */
class RtmpVisibilityTest {

    private final RtmpVisibility visibility = new RtmpVisibility();

    // ============================================================
    //  帮助方法
    // ============================================================

    private List<ToolSpecification> allTools() {
        return List.of(
                ToolSpecification.builder().toolName("queryOrder")
                        .description("查询用户订单状态、物流信息、发货进度。输入订单号。")
                        .parameters(List.of(ParameterSpec.builder().name("orderId").type("String")
                                .required(true).description("订单号").build()))
                        .build(),
                ToolSpecification.builder().toolName("refund")
                        .description("处理退款申请。需要提供订单号和退款原因。")
                        .parameters(List.of(
                                ParameterSpec.builder().name("orderId").type("String")
                                        .required(true).description("订单号").build(),
                                ParameterSpec.builder().name("reason").type("String")
                                        .required(false).description("退款原因").build()))
                        .build(),
                ToolSpecification.builder().toolName("queryPoints")
                        .description("查询会员积分余额与会员等级。输入会员ID。")
                        .parameters(List.of(ParameterSpec.builder().name("userId").type("String")
                                .required(true).description("会员ID").build()))
                        .build(),
                ToolSpecification.builder().toolName("queryCoupons")
                        .description("查询会员名下可用优惠券列表。输入会员ID。")
                        .parameters(List.of(ParameterSpec.builder().name("userId").type("String")
                                .required(true).description("会员ID").build()))
                        .build());
    }

    private VisibilityResult apply(String query, IntentAnalyzer.IntentResult intent, String... history) {
        OrchestrationContext oc = new OrchestrationContext("mem-p4-3", query);
        oc.setHistory(history == null ? List.of()
                : Arrays.stream(history).map(UserMessage::new).map(m -> (ChatMessage) m).toList());
        oc.setIntent(intent);
        RouterContext rc = new RouterContextFactory().build(oc, allTools());
        return visibility.apply(allTools(), rc);
    }

    private Set<String> visibleNames(VisibilityResult v) {
        return v.visibleTools().stream().map(ToolSpecification::getToolName).collect(Collectors.toSet());
    }

    private Set<String> prunedNames(VisibilityResult v) {
        return v.prunedTools().stream().map(ToolSpecification::getToolName).collect(Collectors.toSet());
    }

    private static void assertNoRecordComponent(String forbidden) {
        boolean present = Arrays.stream(RouterContext.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(forbidden::equals);
        assertFalse(present, "RouterContext 不得包含字段: " + forbidden);
    }

    // ============================================================
    //  1-4. 正常工具 KEEP / visible
    // ============================================================

    @Test
    @DisplayName("正常 queryOrder → KEEP / visible")
    void normalQueryOrderVisible() {
        VisibilityResult v = apply("帮我查一下订单ORD2024001的物流状态",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(visibleNames(v).contains("queryOrder"));
    }

    @Test
    @DisplayName("正常 refund → KEEP / visible")
    void normalRefundVisible() {
        VisibilityResult v = apply("帮我退款订单ORD2024001",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(visibleNames(v).contains("refund"));
    }

    @Test
    @DisplayName("正常 queryPoints → KEEP / visible")
    void normalQueryPointsVisible() {
        VisibilityResult v = apply("帮我查一下我的积分",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(visibleNames(v).contains("queryPoints"));
    }

    @Test
    @DisplayName("正常 queryCoupons → KEEP / visible")
    void normalQueryCouponsVisible() {
        VisibilityResult v = apply("帮我查一下我的优惠券",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(visibleNames(v).contains("queryCoupons"));
    }

    // ============================================================
    //  5. 合法高风险 refund 不被默认裁剪（sanity check）
    // ============================================================

    @Test
    @DisplayName("合法高风险 refund：relevance>=0.5 + runtime=0 + static=0.5 → effective=0.5 → KEEP/visible")
    void legitimateHighRiskRefundKept() {
        VisibilityResult v = apply("帮我退款订单ORD2024001",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        ToolScoreResult refund = v.pruningDecision().stream()
                .filter(r -> r.toolName().equals("refund")).findFirst().orElseThrow();
        assertEquals(0.5, refund.risk().staticRiskScore(), 1e-9);
        assertEquals(0.0, refund.risk().runtimeContextRiskScore(), 1e-9);
        assertEquals(0.5, refund.effectiveRiskValue(), 1e-9);
        assertEquals(ToolDecisionCandidate.KEEP_CANDIDATE, refund.candidate());
        assertTrue(visibleNames(v).contains("refund"),
                "合法高风险 refund 不得退化为‘高风险工具永远隐藏’");
    }

    // ============================================================
    //  6-8. 越权 / 批量裁剪 + 无关低风险工具不机械裁剪
    // ============================================================

    @Test
    @DisplayName("第三方订单：refund 与 queryOrder 被裁剪")
    void thirdPartyRefundAndOrderPruned() {
        VisibilityResult v = apply("帮我退款，这是别人的订单",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(prunedNames(v).contains("refund"));
        assertTrue(prunedNames(v).contains("queryOrder"));
        assertFalse(visibleNames(v).contains("refund"));
        assertFalse(visibleNames(v).contains("queryOrder"));
    }

    @Test
    @DisplayName("可疑上下文下非受影响工具仍 KEEP（不因全局 risk 被机械裁剪）")
    void unrelatedLowRiskToolNotPrunedByAuthorization() {
        VisibilityResult v = apply("帮我查一下我的积分，这是别人的订单",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        // queryPoints 与授权越权无关：runtime risk=0，effective=0.05，relevance>=0.5 → KEEP
        ToolScoreResult points = v.pruningDecision().stream()
                .filter(r -> r.toolName().equals("queryPoints")).findFirst().orElseThrow();
        assertEquals(0.0, points.risk().runtimeContextRiskScore(), 1e-9);
        assertEquals(0.05, points.effectiveRiskValue(), 1e-9);
        assertEquals(ToolDecisionCandidate.KEEP_CANDIDATE, points.candidate());
        assertTrue(visibleNames(v).contains("queryPoints"));
        assertTrue(prunedNames(v).contains("refund"));
        assertTrue(prunedNames(v).contains("queryOrder"));
    }

    @Test
    @DisplayName("批量退款：refund 被裁剪，无关只读工具不机械裁剪")
    void batchPrunesRefundButNotUnrelatedReadOnly() {
        VisibilityResult v = apply("帮我批量审批退款，顺便查一下我的积分",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(prunedNames(v).contains("refund"), "batch 必须裁剪 refund");
        assertTrue(visibleNames(v).contains("queryPoints"),
                "batch 不得机械裁剪无关只读工具 queryPoints");
    }

    // ============================================================
    //  9. Multi-tool 独立保留（禁止 Top-1）
    // ============================================================

    @Test
    @DisplayName("multi-tool：多个 KEEP 同时 visible（禁止 Top-1）")
    void multiToolAllKeptVisible() {
        VisibilityResult v = apply("帮我查一下订单，再查一下我的积分",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(visibleNames(v).contains("queryOrder"));
        assertTrue(visibleNames(v).contains("queryPoints"));
    }

    // ============================================================
    //  10-11. empty-tool-set 是有效结果，不 fallback 恢复
    // ============================================================

    @Test
    @DisplayName("无工具必要性 → visibleTools = ∅（不 fallback 恢复任何工具）")
    void allPrunedEmptySet() {
        VisibilityResult v = apply("今天天气怎么样",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertTrue(v.visibleTools().isEmpty(), "visibleTools 必须为空");
        assertTrue(v.prunedTools().size() == 4, "四个工具都应被裁剪");
    }

    @Test
    @DisplayName("empty-tool-set 不崩溃，返回非 null 空集合")
    void emptySetDoesNotCrash() {
        VisibilityResult v = apply("随便聊聊", IntentAnalyzer.IntentResult.tool("工具执行"));
        assertNotNull(v.visibleTools());
        assertNotNull(v.prunedTools());
        assertNotNull(v.pruningDecision());
        assertTrue(v.visibleTools().isEmpty());
    }

    // ============================================================
    //  12. pruningDecision 可观测（复用 ToolScoreResult）
    // ============================================================

    @Test
    @DisplayName("pruningDecision 可观测：每个工具都有 ToolScoreResult，无第二套 DTO")
    void pruningDecisionObservable() {
        VisibilityResult v = apply("帮我退款订单ORD2024001",
                IntentAnalyzer.IntentResult.tool("工具执行"));
        assertEquals(4, v.pruningDecision().size(), "四个工具都必须有评分决策");
        assertEquals(v.pruningDecision().stream().map(ToolScoreResult::toolName).collect(Collectors.toSet()),
                Set.of("queryOrder", "refund", "queryPoints", "queryCoupons"));
    }

    // ============================================================
    //  13. Router 不读取 Ground Truth
    // ============================================================

    @Test
    @DisplayName("RouterContext 不携带任何 Ground Truth 字段")
    void routerContextHasNoGroundTruthFields() {
        for (String forbidden : List.of("expectedTool", "expectedOutcome", "expectedToolAction",
                "taskCategory", "riskLabel", "adversarial", "expectedReason", "mockResponse",
                "candidateTools", "toolRiskProfile", "contextRisk", "authorization", "groundTruth")) {
            assertNoRecordComponent(forbidden);
        }
    }
}
