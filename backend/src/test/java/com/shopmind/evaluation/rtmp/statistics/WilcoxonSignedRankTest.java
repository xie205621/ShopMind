package com.shopmind.evaluation.rtmp.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wilcoxon signed-rank（asymptotic, two-sided）测试 — Phase 5-B1（覆盖 §34 的 1–10 项）。
 */
class WilcoxonSignedRankTest {

    private static List<Double> ds(double... v) {
        List<Double> l = new java.util.ArrayList<>();
        for (double d : v) {
            l.add(d);
        }
        return l;
    }

    @Test
    @DisplayName("1. positive-only differences：W+>0, W-=0, W=0")
    void positiveOnly() {
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 2, 3), ds(0, 0, 0), 0);
        assertEquals(6.0, r.wPlus(), 1e-12);
        assertEquals(0.0, r.wMinus(), 1e-12);
        assertEquals(0.0, r.statistic(), 1e-12);
        assertEquals(3, r.nonZeroN());
        assertEquals(0, r.zeroDifferenceN());
    }

    @Test
    @DisplayName("2. negative-only differences：W-=6, W+=0")
    void negativeOnly() {
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(0, 0, 0), ds(1, 2, 3), 0);
        assertEquals(0.0, r.wPlus(), 1e-12);
        assertEquals(6.0, r.wMinus(), 1e-12);
        assertEquals(0.0, r.statistic(), 1e-12);
    }

    @Test
    @DisplayName("3. mixed signs：W+ / W- 分别累加")
    void mixedSigns() {
        // d = [1, -1, 3]；|d|=[1,1,3]，midrank: 1.5,1.5,3
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 2, 3), ds(0, 3, 0), 0);
        assertEquals(4.5, r.wPlus(), 1e-12);
        assertEquals(1.5, r.wMinus(), 1e-12);
        assertEquals(1.5, r.statistic(), 1e-12);
    }

    @Test
    @DisplayName("4. zero differences removed（不计入 effective N）")
    void zeroDifferencesRemoved() {
        // d = [0, 2, 3, 0] → 非零 [2,3]，zero=2
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 2, 3, 4), ds(1, 0, 0, 4), 0);
        assertEquals(2, r.nonZeroN());
        assertEquals(2, r.zeroDifferenceN());
    }

    @Test
    @DisplayName("5. tied absolute differences 使用 midrank（1.5）")
    void tiedMidrank() {
        // d = [1, -1] → |d|=[1,1] 均为 rank 1.5；W+ = 1.5, W- = 1.5, W = 1.5
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(2, 1), ds(1, 2), 0);
        assertEquals(1.5, r.wPlus(), 1e-12);
        assertEquals(1.5, r.wMinus(), 1e-12);
        assertEquals(1.5, r.statistic(), 1e-12);
    }

    @Test
    @DisplayName("6. n=0（全零差值）→ p=1, statistic=0")
    void allZeroDifferences() {
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 2, 3), ds(1, 2, 3), 0);
        assertEquals(1.0, r.pValue());
        assertEquals(0.0, r.statistic(), 1e-12);
        assertEquals(StatisticalDecision.NOT_SIGNIFICANT, r.decision());
        assertEquals(0, r.nonZeroN());
        assertEquals(3, r.zeroDifferenceN());
    }

    @Test
    @DisplayName("7. n=1 → p=null, INSUFFICIENT_PAIRS")
    void insufficientPairs() {
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(5, 1), ds(0, 1), 0); // d=[5,0] → nonZeroN=1
        assertNull(r.pValue());
        assertEquals(StatisticalDecision.INSUFFICIENT_PAIRS, r.decision());
        assertEquals(1, r.nonZeroN());
    }

    @Test
    @DisplayName("8. continuity correction：全正与全负镜像 p-value 相同")
    void continuityCorrectionSymmetry() {
        StatisticalResult pos = WilcoxonSignedRank.compute(
                ds(1, 2, 3, 4), ds(0, 0, 0, 0), 0);
        StatisticalResult neg = WilcoxonSignedRank.compute(
                ds(0, 0, 0, 0), ds(1, 2, 3, 4), 0);
        assertEquals(pos.pValue(), neg.pValue(), 1e-12);
    }

    @Test
    @DisplayName("9. tie-corrected variance：有 tie 时 p 小于无 tie 对照")
    void tieCorrectedVariance() {
        // 无 tie：d=[1,2,3,4,5] 全正
        StatisticalResult noTie = WilcoxonSignedRank.compute(
                ds(1, 2, 3, 4, 5), ds(0, 0, 0, 0, 0), 0);
        // 有 tie：d=[1,1,3,4,5] 全正（tie group {1,1}），W+ 相同但 Var 更小
        StatisticalResult tied = WilcoxonSignedRank.compute(
                ds(1, 2, 3, 4, 5), ds(0, 1, 0, 0, 0), 0); // d=[1,1,3,4,5]
        assertEquals(15.0, noTie.wPlus(), 1e-12);
        assertEquals(15.0, tied.wPlus(), 1e-12);
        assertNotNull(noTie.pValue());
        assertNotNull(tied.pValue());
        assertTrue(tied.pValue() < noTie.pValue(),
                "tie 使 Var 更小、|z| 更大，故 p 更小");
    }

    @Test
    @DisplayName("9b. tie-corrected variance 精确值：d=[1,1,2] 全正 → p≈0.1736")
    void tieCorrectedVarianceExact() {
        // n=3，tie group {1,1} 大小 2：Var = 3·4·7/24 − (2³−2)/48 = 3.5 − 0.125 = 3.375
        // W+=6, E=3, z=(6−3−0.5)/√3.375 ≈ 1.3608, p = 2(1−Φ(z)) ≈ 0.1736
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 1, 2), ds(0, 0, 0), 0);
        assertEquals(6.0, r.wPlus(), 1e-12);
        assertEquals(0.1736, r.pValue(), 1e-3);
    }

    @Test
    @DisplayName("10. two-sided p-value：n=6 全正 → p≈0.036（显著）")
    void twoSidedPValue() {
        StatisticalResult r = WilcoxonSignedRank.compute(
                ds(1, 2, 3, 4, 5, 6), ds(0, 0, 0, 0, 0, 0), 0);
        // z = (21-10.5-0.5)/sqrt(22.75) ≈ 2.0966 → p ≈ 0.036
        assertNotNull(r.pValue());
        assertEquals(0.0360, r.pValue(), 1e-3);
        assertEquals(StatisticalDecision.SIGNIFICANT, r.decision());
    }

    @Test
    @DisplayName("输入长度不一致抛异常")
    void mismatchedLength() {
        assertThrows(IllegalArgumentException.class, () ->
                WilcoxonSignedRank.compute(ds(1, 2), ds(1), 0));
    }
}
