package com.shopmind.evaluation.rtmp.persistence;

import com.shopmind.evaluation.rtmp.ExpectedToolAction;
import com.shopmind.evaluation.rtmp.RtmpTaskCategory;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.experiment.ControlType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-B4：Summary 聚合器纯函数测试。
 * <p>
 * 只验证 RtmpSummaryBuilder 对 Raw 记录的描述性聚合（count / rate / overhead / subgroup），
 * 不重算 B2 evaluator 语义。
 */
class RtmpExperimentAggregationTest {

    private static RtmpSummary.ConditionSummary cond(RtmpSummary s, String condition) {
        for (RtmpSummary.ConditionSummary c : s.conditions()) {
            if (c.condition().equals(condition)) {
                return c;
            }
        }
        return null;
    }

    private static RtmpSummary.SubgroupSummary subgroup(RtmpSummary.ConditionSummary c, String name) {
        for (RtmpSummary.SubgroupSummary sg : c.subgroups()) {
            if (sg.name().equals(name)) {
                return sg;
            }
        }
        return null;
    }

    @Test
    @DisplayName("1. Summary 恒产生 3 个 condition，顺序冻结（A/B/C）")
    void summaryProducesThreeConditionsInOrder() {
        RtmpSummary s = RtmpSummaryBuilder.build(List.of(), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");

        assertEquals(3, s.conditions().size());
        assertEquals(List.of("BASELINE_A", "BASELINE_B", "METHOD_C"),
                s.conditions().stream().map(RtmpSummary.ConditionSummary::condition).toList());
        assertAll(s.conditions().stream().map(c -> () -> assertEquals(0, c.totalRuns())));
    }

    @Test
    @DisplayName("2. run 状态计数：VALID / INVALID / RETRYABLE 分开统计")
    void summaryCountsRunStatuses() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-S01", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL);
        List<RtmpRawRecord> records = List.of(
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-S01", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-S02", 1, tc, List.of(), List.of(), List.of(), RunStatus.INVALID_RUN),
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-S03", 1, tc, List.of(), List.of(), List.of(), RunStatus.RETRYABLE_FAILURE));

        RtmpSummary s = RtmpSummaryBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        RtmpSummary.ConditionSummary a = cond(s, "BASELINE_A");

        assertEquals(3, a.totalRuns());
        assertEquals(1, a.validCount());
        assertEquals(1, a.invalidCount());
        assertEquals(1, a.retryableCount());
    }

    @Test
    @DisplayName("3. eligible=0 时 rate 为 null（不得伪造 0）")
    void summaryRateNullWhenNoEligible() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-S10", RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED,
                "REFUSE_EXPECTED", null, ExpectedToolAction.NOT_CALL);
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-S10", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID);

        RtmpSummary s = RtmpSummaryBuilder.build(List.of(r), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        RtmpSummary.ConditionSummary a = cond(s, "BASELINE_A");

        assertEquals(0, a.overRefusal().eligible());
        assertNull(a.overRefusal().rate(), "eligible=0 时 overRefusal rate 必须为 null");
    }

    @Test
    @DisplayName("4. Core Task Success 协议分母冻结为 30")
    void summaryCoreTaskProtocolN() {
        assertEquals(30, RtmpSummaryBuilder.CORE_TASK_PROTOCOL_N);
        RtmpSummary s = RtmpSummaryBuilder.build(List.of(), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        assertEquals(30, cond(s, "BASELINE_A").coreTaskProtocolN());
    }

    @Test
    @DisplayName("5. Control overhead 聚合：router 计数/耗时保留，verifier 零调用")
    void summaryOverheadAggregation() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-S20", RtmpTaskCategory.MULTI_TOOL,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        RtmpRawRecord r = RtmpB4Fixtures.raw("METHOD_C", "RTMP-S20", 1, tc,
                List.of(),
                List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-S20", 1, ControlType.RTMP_ROUTER, 1, 6L)),
                List.of(), RunStatus.VALID);

        RtmpSummary s = RtmpSummaryBuilder.build(List.of(r), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        RtmpSummary.ConditionSummary c = cond(s, "METHOD_C");

        assertEquals(1, c.routerOverhead().invocationCount());
        assertEquals(6L, c.routerOverhead().totalLatencyMs());
        assertEquals(0, c.verifierOverhead().invocationCount());
        assertEquals(0L, c.verifierOverhead().totalLatencyMs());
        assertNull(c.routerOverhead().totalPromptTokens(), "local deterministic 的 token 必须为 null");
    }

    @Test
    @DisplayName("6. Runtime totals：toolLatencyMs 为各 ToolCallEvent.latencyMs 之和")
    void summaryRuntimeTotalsToolLatency() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-S30", RtmpTaskCategory.SAFE_LOW_RISK,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        RtmpRawRecord r = RtmpB4Fixtures.raw("BASELINE_A", "RTMP-S30", 1, tc,
                List.of(RtmpB4Fixtures.toolCall("BASELINE_A", "RTMP-S30", 1, 1, "queryOrder", "queryOrder", false, 10L)),
                List.of(), List.of(), RunStatus.VALID);

        RtmpSummary s = RtmpSummaryBuilder.build(List.of(r), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        assertEquals(10L, cond(s, "BASELINE_A").runtimeTotals().toolLatencyMs());
    }

    @Test
    @DisplayName("7. Subgroup 枚举：6 组存在，primary 标记正确")
    void summarySubgroupPrimaryFlags() {
        RtmpSummary s = RtmpSummaryBuilder.build(List.of(), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        RtmpSummary.ConditionSummary a = cond(s, "BASELINE_A");

        assertEquals(6, a.subgroups().size());
        assertTrue(subgroup(a, "HIGH_RISK").primary());
        assertTrue(subgroup(a, "MULTI_TOOL").primary());
        assertTrue(subgroup(a, "AMBIGUOUS").primary());
        assertFalse(subgroup(a, "SAFE_LOW_RISK").primary());
        assertFalse(subgroup(a, "TOOL_DISTRACTOR").primary());
        assertFalse(subgroup(a, "OVER_REFUSAL_BOUNDARY").primary());
    }
}
