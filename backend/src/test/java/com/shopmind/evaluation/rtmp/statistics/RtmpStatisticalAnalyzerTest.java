package com.shopmind.evaluation.rtmp.statistics;

import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpComparison;
import com.shopmind.evaluation.rtmp.persistence.RtmpComparisonBuilder;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.experiment.ControlType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RTMP 统计写回集成测试 — Phase 5-B1（覆盖 §35 的 12 项）。
 * <p>
 * 使用 synthetic fixtures（不运行 Real LLM、不修改正式 42-case dataset）。
 */
class RtmpStatisticalAnalyzerTest {

    private static RtmpComparison buildAndAnalyze(List<RtmpRawRecord> records) {
        RtmpComparison desc = RtmpComparisonBuilder.build(records, "RTMP-EXP01", "RTMP-EXP01_raw.json", "now");
        return RtmpStatisticalAnalyzer.analyze(records, desc);
    }

    private static RtmpComparison.ComparisonEntry entry(RtmpComparison c, String pairId, String metric) {
        for (RtmpComparison.PairComparison p : c.pairs()) {
            if (p.pairId().equals(pairId)) {
                for (RtmpComparison.ComparisonEntry e : p.entries()) {
                    if (e.metric().equals(metric)) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    private static RtmpRawRecord rec(String condition, String caseId,
                                     boolean l2, boolean coreEligible, boolean coreSuccess, boolean overRefusal,
                                     List<com.shopmind.workflow.domain.ControlOverheadEvent> controls) {
        return RtmpB1Fixtures.rawRecord(condition, caseId, 1,
                "ANSWER_EXPECTED", "CALL",
                RtmpB1Fixtures.evaluation(condition, caseId, 1, l2, coreEligible, coreSuccess, overRefusal),
                RunStatus.VALID, controls);
    }

    @Test
    @DisplayName("1+8. L2（H1）→ McNemar 写回")
    void l2McnemarMapping() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-003", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-002", true, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-003", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "L2_RATE");

        assertEquals("McnemarExact", e.statisticalTest());
        assertEquals(-3.0, e.statistic(), 1e-12); // b-c = 0-3
        assertEquals(0.25, e.pValue(), 1e-12);    // n=3, min=0 → 2*0.125
        assertEquals("NOT_SIGNIFICANT", e.decision());
        assertEquals("H1", e.hypothesis());
    }

    @Test
    @DisplayName("2+11. latency（H4）→ Wilcoxon 写回")
    void latencyWilcoxonMapping() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_B", "RTMP-001", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-001", 1, ControlType.SAFETY_VERIFIER, 1, 2L))),
                rec("BASELINE_B", "RTMP-002", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-002", 1, ControlType.SAFETY_VERIFIER, 1, 4L))),
                rec("BASELINE_B", "RTMP-003", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-003", 1, ControlType.SAFETY_VERIFIER, 1, 6L))),
                rec("METHOD_C", "RTMP-001", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-001", 1, ControlType.RTMP_ROUTER, 1, 2L))),
                rec("METHOD_C", "RTMP-002", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-002", 1, ControlType.RTMP_ROUTER, 1, 4L))),
                rec("METHOD_C", "RTMP-003", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-003", 1, ControlType.RTMP_ROUTER, 1, 6L))));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry verifier = entry(c, "B_vs_C", "VERIFIER_LATENCY_MEAN_MS");
        RtmpComparison.ComparisonEntry router = entry(c, "B_vs_C", "ROUTER_LATENCY_MEAN_MS");

        assertEquals("WilcoxonSignedRankAsymptotic", verifier.statisticalTest());
        assertEquals("WilcoxonSignedRankAsymptotic", router.statisticalTest());
        assertEquals("H4", verifier.hypothesis());
        assertNotNull(verifier.pValue());
        assertNotNull(router.pValue());
        // B verifier 2/4/6 vs C verifier 0 → d=[2,4,6] 全正，n=3，p≈0.18（非显著）
        assertEquals("NOT_SIGNIFICANT", verifier.decision());
        assertEquals("NOT_SIGNIFICANT", router.decision());
    }

    @Test
    @DisplayName("3. 统计字段被填充：statisticalTest/statistic/pValue/decision/alpha/twoSided")
    void statisticalFieldsPopulated() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "L2_RATE");

        assertNotNull(e.statisticalTest());
        assertNotNull(e.statistic());
        assertNotNull(e.pValue());
        assertNotNull(e.decision());
        assertEquals(0.05, e.alpha(), 1e-12);
        assertEquals(Boolean.TRUE, e.twoSided());
    }

    @Test
    @DisplayName("4. Raw 不被修改")
    void rawUnchanged() {
        List<RtmpRawRecord> records = new ArrayList<>(List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of())));
        List<RtmpRawRecord> before = List.copyOf(records);

        buildAndAnalyze(records);

        assertEquals(before, records, "analyze 不得修改 Raw 记录");
    }

    @Test
    @DisplayName("5. A/B/C pair 顺序不变")
    void pairOrderUnchanged() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        assertEquals(List.of("A_vs_B", "B_vs_C", "A_vs_C"),
                c.pairs().stream().map(RtmpComparison.PairComparison::pairId).toList());
    }

    @Test
    @DisplayName("6. pairedN 等于实际 valid paired units")
    void pairedNMatchesValidUnits() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-002", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "L2_RATE");
        assertEquals(2, e.pairedN());
        assertEquals(List.of("RTMP-001#1", "RTMP-002#1"), e.pairedUnitIds());
    }

    @Test
    @DisplayName("7. 不出现 t-test")
    void noTTestAppears() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        for (RtmpComparison.PairComparison p : c.pairs()) {
            for (RtmpComparison.ComparisonEntry e : p.entries()) {
                if (e.statisticalTest() != null) {
                    assertFalse(e.statisticalTest().toLowerCase().contains("t-test"));
                    assertFalse(e.statisticalTest().toLowerCase().contains("student"));
                    assertFalse(e.statisticalTest().toLowerCase().contains("anova"));
                }
            }
        }
    }

    @Test
    @DisplayName("9. H2 Core Task Success → McNemar（只使用 coreTaskEligible=true）")
    void h2CoreTaskSuccessMapping() {
        // 3 个 case 均 coreEligible；A 全部 success，C 全部失败（coreSuccess=false）
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-003", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", false, true, false, false, List.of()),
                rec("METHOD_C", "RTMP-002", false, true, false, false, List.of()),
                rec("METHOD_C", "RTMP-003", false, true, false, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "CORE_TASK_SUCCESS_RATE");

        assertEquals("McnemarExact", e.statisticalTest());
        assertEquals("H2", e.hypothesis());
        assertEquals(3.0, e.statistic(), 1e-12); // b-c = 3-0
        assertNotNull(e.pValue());
    }

    @Test
    @DisplayName("10. H3 Over-refusal → McNemar（只使用 ANSWER_EXPECTED∧CALL）")
    void h3OverRefusalMapping() {
        // C 出现 overRefusal，A 无
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", false, true, true, true, List.of()),
                rec("METHOD_C", "RTMP-002", false, true, true, true, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "OVER_REFUSAL_RATE");

        assertEquals("McnemarExact", e.statisticalTest());
        assertEquals("H3", e.hypothesis());
        assertEquals(-2.0, e.statistic(), 1e-12); // b-c = 0-2
        assertNotNull(e.pValue());
    }

    @Test
    @DisplayName("12. 统计结果确定（重复执行一致）")
    void deterministicAcrossRuns() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()));

        RtmpComparison c1 = buildAndAnalyze(records);
        RtmpComparison c2 = buildAndAnalyze(records);

        RtmpComparison.ComparisonEntry e1 = entry(c1, "A_vs_C", "L2_RATE");
        RtmpComparison.ComparisonEntry e2 = entry(c2, "A_vs_C", "L2_RATE");
        assertEquals(e1.statistic(), e2.statistic());
        assertEquals(e1.pValue(), e2.pValue());
        assertEquals(e1.decision(), e2.decision());
    }

    @Test
    @DisplayName("invalid run 被排除（不进入配对）")
    void invalidRunExcluded() {
        // A 3 valid；C 2 valid + 1 invalid（RTMP-003）
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-003", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-002", true, true, true, false, List.of()),
                RtmpB1Fixtures.rawRecord("METHOD_C", "RTMP-003", 1, "ANSWER_EXPECTED", "CALL",
                        null, RunStatus.INVALID_RUN, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "L2_RATE");
        assertEquals(2, e.pairedN(), "invalid run 不应进入配对");
    }

    @Test
    @DisplayName("missing counterpart 被排除（不进入配对）")
    void missingCounterpartExcluded() {
        // A 3 valid；C 仅 2 case（缺 RTMP-003）
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-003", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-002", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry e = entry(c, "A_vs_C", "L2_RATE");
        assertEquals(2, e.pairedN(), "缺失 counterpart 的 unit 不应进入配对");
    }

    // ============================================================
    //  Phase 5-C1：Holm-Bonferroni / adjustedPValue（§25-§28/§31 Statistics）
    // ============================================================

    @Test
    @DisplayName("H1/H2/H3 进入 primary family：raw p=0.25 → adjusted p=0.75（raw 保留）")
    void holmAdjustsPrimaryFamilyH1H2H3() {
        // A：L2=false, coreSuccess=true, overRefusal=false
        // C：L2=true,  coreSuccess=false, overRefusal=true
        // 三个 primary metric 的 McNemar p 均为 0.25（b-c 各为 ±3），Holm m=3 → adjusted 0.75
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-002", false, true, true, false, List.of()),
                rec("BASELINE_A", "RTMP-003", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, false, true, List.of()),
                rec("METHOD_C", "RTMP-002", true, true, false, true, List.of()),
                rec("METHOD_C", "RTMP-003", true, true, false, true, List.of()));

        RtmpComparison c = buildAndAnalyze(records);

        RtmpComparison.ComparisonEntry h1 = entry(c, "A_vs_C", "L2_RATE");
        assertEquals("H1", h1.hypothesis());
        assertEquals(0.25, h1.pValue(), 1e-12, "raw p 必须保留");
        assertEquals(0.75, h1.adjustedPValue(), 1e-12, "H1 adjusted p 应为 0.75");

        RtmpComparison.ComparisonEntry h2 = entry(c, "A_vs_C", "CORE_TASK_SUCCESS_RATE");
        assertEquals("H2", h2.hypothesis());
        assertEquals(0.25, h2.pValue(), 1e-12);
        assertEquals(0.75, h2.adjustedPValue(), 1e-12);

        RtmpComparison.ComparisonEntry h3 = entry(c, "A_vs_C", "OVER_REFUSAL_RATE");
        assertEquals("H3", h3.hypothesis());
        assertEquals(0.25, h3.pValue(), 1e-12);
        assertEquals(0.75, h3.adjustedPValue(), 1e-12);
    }

    @Test
    @DisplayName("H4（secondary latency）不进入 Holm family：adjustedPValue == raw pValue")
    void h4NotInHolmFamily() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_B", "RTMP-001", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-001", 1, ControlType.SAFETY_VERIFIER, 1, 2L))),
                rec("BASELINE_B", "RTMP-002", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-002", 1, ControlType.SAFETY_VERIFIER, 1, 4L))),
                rec("BASELINE_B", "RTMP-003", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("BASELINE_B", "RTMP-003", 1, ControlType.SAFETY_VERIFIER, 1, 6L))),
                rec("METHOD_C", "RTMP-001", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-001", 1, ControlType.RTMP_ROUTER, 1, 2L))),
                rec("METHOD_C", "RTMP-002", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-002", 1, ControlType.RTMP_ROUTER, 1, 4L))),
                rec("METHOD_C", "RTMP-003", false, true, true, false,
                        List.of(RtmpB1Fixtures.controlEvent("METHOD_C", "RTMP-003", 1, ControlType.RTMP_ROUTER, 1, 6L))));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry verifier = entry(c, "B_vs_C", "VERIFIER_LATENCY_MEAN_MS");
        RtmpComparison.ComparisonEntry router = entry(c, "B_vs_C", "ROUTER_LATENCY_MEAN_MS");

        assertEquals("H4", verifier.hypothesis());
        assertNotNull(verifier.pValue());
        assertEquals(verifier.pValue(), verifier.adjustedPValue(), 1e-12,
                "H4 不得进入 Holm family，adjusted 应等于 raw");

        assertEquals("H4", router.hypothesis());
        assertNotNull(router.pValue());
        assertEquals(router.pValue(), router.adjustedPValue(), 1e-12,
                "H4 不得进入 Holm family，adjusted 应等于 raw");
    }

    @Test
    @DisplayName("exploratory/null 假设（H5 descriptive）不进入 Holm family：adjustedPValue == raw pValue")
    void nullHypothesisNotInHolmFamily() {
        List<RtmpRawRecord> records = List.of(
                rec("BASELINE_A", "RTMP-001", false, true, true, false, List.of()),
                rec("METHOD_C", "RTMP-001", true, true, true, false, List.of()));

        RtmpComparison c = buildAndAnalyze(records);
        RtmpComparison.ComparisonEntry l1 = entry(c, "A_vs_C", "L1_RATE");

        assertNull(l1.hypothesis(), "L1_RATE 为 exploratory/null 假设（对应 H5 descriptive）");
        assertNotNull(l1.pValue());
        assertEquals(l1.pValue(), l1.adjustedPValue(), 1e-12,
                "exploratory/null 假设不得进入 Holm family");
    }
}
