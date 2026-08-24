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
 * Phase 5-B4：Comparison 描述性比较测试。
 * <p>
 * 验证三对 condition pair、配对结构（caseId#repetition）、描述性差异计算，
 * 以及统计占位字段恒为 null（B1 未实现）。
 */
class RtmpExperimentComparisonTest {

    private static RtmpComparison.PairComparison pair(RtmpComparison c, String pairId) {
        for (RtmpComparison.PairComparison p : c.pairs()) {
            if (p.pairId().equals(pairId)) {
                return p;
            }
        }
        return null;
    }

    private static RtmpComparison.ComparisonEntry entry(RtmpComparison c, String pairId, String metric) {
        RtmpComparison.PairComparison p = pair(c, pairId);
        for (RtmpComparison.ComparisonEntry e : p.entries()) {
            if (e.metric().equals(metric)) {
                return e;
            }
        }
        return null;
    }

    @Test
    @DisplayName("1. 固定三对 comparison：A_vs_B / B_vs_C / A_vs_C")
    void comparisonProducesThreePairsInOrder() {
        RtmpComparison c = RtmpComparisonBuilder.build(List.of(), "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");

        assertEquals(3, c.pairs().size());
        assertEquals(List.of("A_vs_B", "B_vs_C", "A_vs_C"),
                c.pairs().stream().map(RtmpComparison.PairComparison::pairId).toList());
    }

    @Test
    @DisplayName("2. 统计占位字段恒为 null（B1 未实现）")
    void comparisonStatisticalFieldsNull() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-C01", RtmpTaskCategory.SAFE_LOW_RISK,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        List<RtmpRawRecord> records = List.of(
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-C01", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("BASELINE_B", "RTMP-C01", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("METHOD_C", "RTMP-C01", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID));

        RtmpComparison c = RtmpComparisonBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        for (RtmpComparison.PairComparison p : c.pairs()) {
            for (RtmpComparison.ComparisonEntry e : p.entries()) {
                assertNull(e.statisticalTest());
                assertNull(e.statistic());
                assertNull(e.pValue());
                assertNull(e.decision());
            }
        }
    }

    @Test
    @DisplayName("3. 配对结构：caseId#repetition 为配对键，仅相同 unit 进入 pairedN")
    void comparisonPairedStructure() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-C02", RtmpTaskCategory.SAFE_LOW_RISK,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        List<RtmpRawRecord> records = List.of(
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-C02", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("BASELINE_B", "RTMP-C02", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("METHOD_C", "RTMP-C99", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID));

        RtmpComparison c = RtmpComparisonBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");

        RtmpComparison.ComparisonEntry ab = entry(c, "A_vs_B", "L2_RATE");
        assertEquals(1, ab.pairedN());
        assertEquals(List.of("RTMP-C02#1"), ab.pairedUnitIds());

        // B 与 C 无共同 unit → pairedN=0
        RtmpComparison.ComparisonEntry bc = entry(c, "B_vs_C", "L2_RATE");
        assertEquals(0, bc.pairedN());
    }

    @Test
    @DisplayName("4. 描述性差异：scalar latency 的 value/difference/relativeDifference")
    void comparisonScalarDifference() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-C03", RtmpTaskCategory.MULTI_TOOL,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        List<RtmpRawRecord> records = List.of(
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-C03", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("METHOD_C", "RTMP-C03", 1, tc,
                        List.of(),
                        List.of(RtmpB4Fixtures.controlEvent("METHOD_C", "RTMP-C03", 1, ControlType.RTMP_ROUTER, 1, 6L)),
                        List.of(), RunStatus.VALID));

        RtmpComparison c = RtmpComparisonBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        RtmpComparison.ComparisonEntry router = entry(c, "A_vs_C", "ROUTER_LATENCY_MEAN_MS");

        assertEquals(0.0, router.valueA());
        assertEquals(6.0, router.valueB());
        assertEquals(-6.0, router.difference());
        assertEquals(-1.0, router.relativeDifference());
    }

    @Test
    @DisplayName("5. 仅 VALID + evaluation 非 null 进入配对，invalid 被排除")
    void comparisonExcludesInvalidRecords() {
        RtmpTestCase tc = RtmpB4Fixtures.testCase("RTMP-C04", RtmpTaskCategory.SAFE_LOW_RISK,
                "ANSWER_EXPECTED", "queryOrder", ExpectedToolAction.CALL);
        List<RtmpRawRecord> records = List.of(
                RtmpB4Fixtures.raw("BASELINE_A", "RTMP-C04", 1, tc, List.of(), List.of(), List.of(), RunStatus.VALID),
                RtmpB4Fixtures.raw("BASELINE_B", "RTMP-C04", 1, tc, List.of(), List.of(), List.of(), RunStatus.INVALID_RUN));

        RtmpComparison c = RtmpComparisonBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        assertEquals(0, entry(c, "A_vs_B", "L2_RATE").pairedN(),
                "invalid run 不应进入配对");
    }
}
