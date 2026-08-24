package com.shopmind.workflow.domain;

import com.shopmind.experiment.ControlOverheadInstrumentation;
import com.shopmind.experiment.ControlType;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.experiment.RouterContext;
import com.shopmind.experiment.RouterContextFactory;
import com.shopmind.experiment.RtmpVisibility;
import com.shopmind.experiment.VisibilityResult;
import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.port.IntentAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-B3：Control Overhead Instrumentation 单元测试与真值表测试。
 * <p>
 * 覆盖 §23 的 14 项 + §20/§21/§22 真值表语义：count 准确、latency 与 tool latency 分离、
 * Router/Verifier 不串账、无调用时 overhead 为 0/null、多 iteration 分别记录、token/cost 缺失不伪造、
 * per-event 与 run-level 聚合一致、不影响 A/B/C 行为（门控映射）。
 */
class ControlOverheadInstrumentationTest {

    // ============================================================
    //  §20 真值表：condition → control type 门控
    // ============================================================

    @Test
    @DisplayName("1. Baseline A 无 Router 无 Verifier")
    void baselineARecordsNoControlOverhead() {
        assertEquals(Set.of(), ControlOverheadInstrumentation.controlTypesFor(ExperimentCondition.BASELINE_A));
        assertFalse(ControlOverheadInstrumentation.recordsVerifierOverhead(ExperimentCondition.BASELINE_A));
        assertFalse(ControlOverheadInstrumentation.recordsRouterOverhead(ExperimentCondition.BASELINE_A));
    }

    @Test
    @DisplayName("2. Baseline B 仅记 Verifier")
    void baselineBRecordsOnlyVerifierOverhead() {
        assertEquals(Set.of(ControlType.SAFETY_VERIFIER),
                ControlOverheadInstrumentation.controlTypesFor(ExperimentCondition.BASELINE_B));
        assertTrue(ControlOverheadInstrumentation.recordsVerifierOverhead(ExperimentCondition.BASELINE_B));
        assertFalse(ControlOverheadInstrumentation.recordsRouterOverhead(ExperimentCondition.BASELINE_B));
    }

    @Test
    @DisplayName("3. Method C 仅记 Router")
    void methodCRecordsOnlyRouterOverhead() {
        assertEquals(Set.of(ControlType.RTMP_ROUTER),
                ControlOverheadInstrumentation.controlTypesFor(ExperimentCondition.METHOD_C));
        assertFalse(ControlOverheadInstrumentation.recordsVerifierOverhead(ExperimentCondition.METHOD_C));
        assertTrue(ControlOverheadInstrumentation.recordsRouterOverhead(ExperimentCondition.METHOD_C));
    }

    @Test
    @DisplayName("4. null condition 无任何 control")
    void nullConditionRecordsNothing() {
        assertEquals(Set.of(), ControlOverheadInstrumentation.controlTypesFor(null));
    }

    // ============================================================
    //  §20/§23：调用次数准确（per-event / run-level）
    // ============================================================

    @Test
    @DisplayName("5. B 单次 Verifier → count=1")
    void verifierSingleInvocationCount() {
        ExecutionTrace trace = trace("BASELINE_B", "RTMP-001");
        trace.addControlOverheadEvent(verifierEvent(1, 12L));

        ControlOverhead oh = trace.controlOverhead(ControlType.SAFETY_VERIFIER);
        assertEquals(1, oh.invocationCount());
        assertEquals(12L, oh.totalLatencyMs());
    }

    @Test
    @DisplayName("6. B 多次 Verifier → count=2 且 latency 求和")
    void verifierMultipleInvocationsCountAndLatency() {
        ExecutionTrace trace = trace("BASELINE_B", "RTMP-001");
        trace.addControlOverheadEvent(verifierEvent(1, 12L));
        trace.addControlOverheadEvent(verifierEvent(2, 8L));

        ControlOverhead oh = trace.controlOverhead(ControlType.SAFETY_VERIFIER);
        assertEquals(2, oh.invocationCount());
        assertEquals(20L, oh.totalLatencyMs());
    }

    @Test
    @DisplayName("7. C 单 iteration Router → count=1")
    void routerSingleInvocationCount() {
        ExecutionTrace trace = trace("METHOD_C", "RTMP-001");
        trace.addControlOverheadEvent(routerEvent(1, 5L));

        ControlOverhead oh = trace.controlOverhead(ControlType.RTMP_ROUTER);
        assertEquals(1, oh.invocationCount());
        assertEquals(5L, oh.totalLatencyMs());
    }

    @Test
    @DisplayName("8. C 多 iteration Router → count=2")
    void routerMultipleInvocationsCount() {
        ExecutionTrace trace = trace("METHOD_C", "RTMP-001");
        trace.addControlOverheadEvent(routerEvent(1, 5L));
        trace.addControlOverheadEvent(routerEvent(2, 4L));

        assertEquals(2, trace.controlOverhead(ControlType.RTMP_ROUTER).invocationCount());
        assertEquals(9L, trace.controlOverhead(ControlType.RTMP_ROUTER).totalLatencyMs());
    }

    @Test
    @DisplayName("9. C 多工具（4 tools）只计一次 Router：一次 decision → count=1")
    void routerInvocationCountIsPerDecisionNotPerTool() {
        List<ToolSpecification> tools = allTools();
        RouterContext rc = routerContext("帮我查一下订单和积分", tools);
        VisibilityResult result = new RtmpVisibility().apply(tools, rc);
        assertEquals(4, result.inputToolCount(), "4 个工具参与一次 decision");

        // 一次 apply（一次 decision）只产生一条 ControlOverheadEvent，而不是 4 条。
        ExecutionTrace trace = trace("METHOD_C", "RTMP-001");
        trace.addControlOverheadEvent(routerEvent(1, 6L));
        assertEquals(1, trace.controlOverhead(ControlType.RTMP_ROUTER).invocationCount());
    }

    @Test
    @DisplayName("10. empty-tool-set 仍计一次 Router（不因 0 visible 跳过）")
    void emptyToolSetStillCountsOneRouterDecision() {
        List<ToolSpecification> tools = allTools();
        RouterContext rc = routerContext("今天天气怎么样", tools);
        VisibilityResult result = new RtmpVisibility().apply(tools, rc);
        assertTrue(result.visibleTools().isEmpty(), "无工具必要性时 visibleTools 为空");

        ExecutionTrace trace = trace("METHOD_C", "RTMP-002");
        trace.addControlOverheadEvent(routerEvent(1, 3L));
        assertEquals(1, trace.controlOverhead(ControlType.RTMP_ROUTER).invocationCount());
    }

    // ============================================================
    //  §23：Router/Verifier 不串账 + 聚合按类型过滤
    // ============================================================

    @Test
    @DisplayName("11. 混合事件按类型分别聚合，Router/Verifier 不串账")
    void aggregationFiltersEventsByControlType() {
        ExecutionTrace trace = trace("BASELINE_B", "RTMP-001");
        trace.addControlOverheadEvent(verifierEvent(1, 12L));
        trace.addControlOverheadEvent(verifierEvent(2, 8L));
        trace.addControlOverheadEvent(routerEvent(1, 5L));

        assertEquals(2, trace.controlOverhead(ControlType.SAFETY_VERIFIER).invocationCount());
        assertEquals(20L, trace.controlOverhead(ControlType.SAFETY_VERIFIER).totalLatencyMs());
        assertEquals(1, trace.controlOverhead(ControlType.RTMP_ROUTER).invocationCount());
        assertEquals(5L, trace.controlOverhead(ControlType.RTMP_ROUTER).totalLatencyMs());
    }

    // ============================================================
    //  §12：no-invocation 语义 + §22 token/cost 不伪造
    // ============================================================

    @Test
    @DisplayName("12. 无调用时 overhead 为 0 / null（count=0, latency=0, token/cost=null）")
    void noInvocationZeroCountZeroLatencyNullTokens() {
        ExecutionTrace trace = trace("BASELINE_A", "RTMP-001");
        ControlOverhead oh = trace.controlOverhead(ControlType.SAFETY_VERIFIER);
        assertEquals(0, oh.invocationCount());
        assertEquals(0L, oh.totalLatencyMs());
        assertNull(oh.totalPromptTokens());
        assertNull(oh.totalCompletionTokens());
        assertNull(oh.totalTokens());
        assertNull(oh.totalCost());
    }

    @Test
    @DisplayName("13. token/cost 缺失时不伪造（event 与 aggregate 均为 null）")
    void tokenAndCostNullWhenUnavailable() {
        ExecutionTrace trace = trace("BASELINE_B", "RTMP-001");
        ControlOverheadEvent e = verifierEvent(1, 12L);
        assertNull(e.promptTokens());
        assertNull(e.completionTokens());
        assertNull(e.totalTokens());
        assertNull(e.cost());
        trace.addControlOverheadEvent(e);

        ControlOverhead oh = trace.controlOverhead(ControlType.SAFETY_VERIFIER);
        assertNull(oh.totalPromptTokens());
        assertNull(oh.totalCompletionTokens());
        assertNull(oh.totalTokens());
        assertNull(oh.totalCost());
    }

    // ============================================================
    //  §15：Tool latency 不混入 control overhead
    // ============================================================

    @Test
    @DisplayName("14. Tool latency 不混入 control overhead（独立列表，独立聚合）")
    void toolLatencySeparateFromControlOverhead() {
        ExecutionTrace trace = trace("METHOD_C", "RTMP-001");
        // ToolCallEvent 记录工具执行 latency（独立于 control latency）
        trace.addToolCallEvent(ToolCallEvent.of(
                trace.getRunId(), 1, "queryOrder", "queryOrder", false,
                java.util.Map.of(), null, 150L));
        trace.addControlOverheadEvent(routerEvent(1, 6L));

        assertEquals(1, trace.getToolCallEvents().size());
        assertEquals(150L, trace.getToolCallEvents().get(0).latencyMs());
        assertEquals(6L, trace.controlOverhead(ControlType.RTMP_ROUTER).totalLatencyMs(),
                "control latency 必须与 tool latency 分离");
    }

    // ============================================================
    //  §13/§11：per-event 保留 + run-level 聚合一致
    // ============================================================

    @Test
    @DisplayName("15. per-event 有序保留，run-level aggregate 与事件和一致")
    void perEventOrderPreservedAndRunLevelAggregateMatches() {
        ExecutionTrace trace = trace("METHOD_C", "RTMP-001");
        trace.addControlOverheadEvent(routerEvent(1, 5L));
        trace.addControlOverheadEvent(routerEvent(2, 4L));
        trace.addControlOverheadEvent(routerEvent(3, 7L));

        List<ControlOverheadEvent> events = trace.getControlOverheadEvents();
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).iteration());
        assertEquals(2, events.get(1).iteration());
        assertEquals(3, events.get(2).iteration());

        ControlOverhead oh = trace.controlOverhead(ControlType.RTMP_ROUTER);
        assertEquals(3, oh.invocationCount());
        assertEquals(16L, oh.totalLatencyMs());
        assertEquals(ControlOverhead.aggregate(ControlType.RTMP_ROUTER, events), oh);
    }

    @Test
    @DisplayName("16. 有真实 token/cost 时正确求和（未来可观察来源的聚合契约）")
    void tokenAndCostAggregateWhenPresent() {
        ControlOverheadEvent e1 = new ControlOverheadEvent(
                "RTMP-EXP01_BASELINE_B_RTMP-001_1", "BASELINE_B", "RTMP-001", 1,
                ControlType.SAFETY_VERIFIER, 1, 10L, 100L, 50L, 150L, new BigDecimal("0.01"));
        ControlOverheadEvent e2 = new ControlOverheadEvent(
                "RTMP-EXP01_BASELINE_B_RTMP-001_1", "BASELINE_B", "RTMP-001", 1,
                ControlType.SAFETY_VERIFIER, 2, 20L, 200L, 100L, 300L, new BigDecimal("0.02"));

        ControlOverhead oh = ControlOverhead.aggregate(ControlType.SAFETY_VERIFIER, List.of(e1, e2));
        assertEquals(2, oh.invocationCount());
        assertEquals(30L, oh.totalLatencyMs());
        assertEquals(300L, oh.totalPromptTokens());
        assertEquals(150L, oh.totalCompletionTokens());
        assertEquals(450L, oh.totalTokens());
        assertEquals(0, new BigDecimal("0.03").compareTo(oh.totalCost()));
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private ExecutionTrace trace(String condition, String caseId) {
        RunIdentity identity = new RunIdentity("RTMP-EXP01", condition, caseId, 1);
        return new ExecutionTrace("trace-" + caseId, identity.memoryId(), "v2.3", identity);
    }

    private ControlOverheadEvent verifierEvent(int iteration, long latencyMs) {
        return controlEvent(ControlType.SAFETY_VERIFIER, iteration, latencyMs);
    }

    private ControlOverheadEvent routerEvent(int iteration, long latencyMs) {
        return controlEvent(ControlType.RTMP_ROUTER, iteration, latencyMs);
    }

    private ControlOverheadEvent controlEvent(ControlType type, int iteration, long latencyMs) {
        return new ControlOverheadEvent(
                "RTMP-EXP01_METHOD_C_RTMP-001_1", "METHOD_C", "RTMP-001", 1,
                type, iteration, latencyMs, null, null, null, null);
    }

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

    private RouterContext routerContext(String query, List<ToolSpecification> tools) {
        OrchestrationContext ctx = new OrchestrationContext("mem-b3", query);
        ctx.setIntent(IntentAnalyzer.IntentResult.tool("工具执行"));
        return new RouterContextFactory().build(ctx, tools);
    }
}
