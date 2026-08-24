package com.shopmind.evaluation.rtmp.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact two-sided McNemar 测试 — Phase 5-B1（覆盖 §33 的 1–7、11 项）。
 */
class McNemarExactTest {

    private static List<Boolean> bs(boolean... v) {
        List<Boolean> l = new java.util.ArrayList<>();
        for (boolean b : v) {
            l.add(b);
        }
        return l;
    }

    @Test
    @DisplayName("1. b=0,c=0 → p=1, statistic=0")
    void zeroDiscordance() {
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, false, false),
                bs(true, true, false, false), 0);
        assertEquals(1.0, r.pValue());
        assertEquals(0.0, r.statistic());
        assertEquals(StatisticalDecision.NOT_SIGNIFICANT, r.decision());
        assertEquals(0, r.discordantN());
    }

    @Test
    @DisplayName("2. b>0,c=0 → 方向 A 有更多 positive")
    void onlyB() {
        // A=true, B=false 出现 3 次；无 c
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, true),
                bs(false, false, false), 0);
        assertEquals(3, r.discordantAOnly());
        assertEquals(0, r.discordantBOnly());
        assertEquals(3.0, r.statistic());
        assertTrue(r.statistic() > 0, "b>c 时 signedDiscordance 应为正");
    }

    @Test
    @DisplayName("3. b=0,c>0 → 方向 B 有更多 positive")
    void onlyC() {
        StatisticalResult r = McNemarExact.compute(
                bs(false, false, false),
                bs(true, true, true), 0);
        assertEquals(0, r.discordantAOnly());
        assertEquals(3, r.discordantBOnly());
        assertEquals(-3.0, r.statistic());
        assertTrue(r.statistic() < 0, "c>b 时 signedDiscordance 应为负");
    }

    @Test
    @DisplayName("4. b=c → statistic=0")
    void balancedDiscordance() {
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, false, false),
                bs(false, false, true, true), 0);
        assertEquals(2, r.discordantAOnly());
        assertEquals(2, r.discordantBOnly());
        assertEquals(0.0, r.statistic());
    }

    @Test
    @DisplayName("5. symmetric case：b=3,c=1 与 b=1,c=3 的 p-value 相同")
    void symmetricPValue() {
        StatisticalResult r1 = McNemarExact.compute(
                bs(true, true, true, false),
                bs(false, false, false, true), 0);
        StatisticalResult r2 = McNemarExact.compute(
                bs(false, false, false, true),
                bs(true, true, true, false), 0);
        assertEquals(r1.pValue(), r2.pValue(), 1e-12);
    }

    @Test
    @DisplayName("6. exact two-sided p-value：b=3,c=0 → p=0.25")
    void exactPValue() {
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, true),
                bs(false, false, false), 0);
        // n=3, min=0, P(X<=0)=0.125, p=2*0.125=0.25
        assertEquals(0.25, r.pValue(), 1e-12);
    }

    @Test
    @DisplayName("6b. exact two-sided p-value：b=6,c=0 → p=0.03125（显著）")
    void exactPValueSignificant() {
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, true, true, true, true),
                bs(false, false, false, false, false, false), 0);
        // n=6, min=0, P(X<=0)=1/64, p=2/64=0.03125
        assertEquals(0.03125, r.pValue(), 1e-12);
        assertEquals(StatisticalDecision.SIGNIFICANT, r.decision());
    }

    @Test
    @DisplayName("7. alpha decision：p<0.05 → SIGNIFICANT；p>=0.05 → NOT_SIGNIFICANT")
    void alphaDecision() {
        StatisticalResult sig = McNemarExact.compute(
                bs(true, true, true, true, true, true),
                bs(false, false, false, false, false, false), 0);
        StatisticalResult notSig = McNemarExact.compute(
                bs(true, true),
                bs(false, false), 0); // n=2 → p=0.5
        assertEquals(StatisticalDecision.SIGNIFICANT, sig.decision());
        assertEquals(StatisticalDecision.NOT_SIGNIFICANT, notSig.decision());
        assertEquals(0.5, notSig.pValue(), 1e-12);
    }

    @Test
    @DisplayName("11. statistic = signed discordance b-c")
    void signedDiscordance() {
        StatisticalResult r = McNemarExact.compute(
                bs(true, true, true, false, false),
                bs(false, false, false, true, false), 0);
        // b: A=true,B=false → 3 次 (index 0,1,2)；c: A=false,B=true → 1 次 (index 3)
        assertEquals(3, r.discordantAOnly());
        assertEquals(1, r.discordantBOnly());
        assertEquals(2.0, r.statistic());
    }

    @Test
    @DisplayName("输入长度不一致抛异常")
    void mismatchedLength() {
        assertThrows(IllegalArgumentException.class, () ->
                McNemarExact.compute(bs(true, false), bs(true), 0));
    }
}
