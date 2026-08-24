package com.shopmind.evaluation.rtmp.statistics;

import java.util.List;

/**
 * Exact two-sided McNemar test — Phase 5-B1。
 * <p>
 * 冻结定义（§6–§10）：
 * <ul>
 *   <li>输入为 paired binary outcomes（{@code x_i, y_i ∈ {false,true}}）；</li>
 *   <li>{@code b} = A=true,B=false；{@code c} = A=false,B=true；{@code n = b+c}；</li>
 *   <li>statistic = signed discordance {@code b-c}（非传统 χ²）；</li>
 *   <li>p-value 使用 exact two-sided binomial：{@code p = min(1, 2·P(X ≤ min(b,c)))}，
 *       {@code X ~ Binomial(n, 0.5)}；</li>
 *   <li>无配对 unit（pairedN=0）→ p=null（INSUFFICIENT_PAIRS），不伪造 p 值；</li>
 *   <li>zero-discordance（pairedN&gt;0 且 b=c=0）→ p=1.0, statistic=0；</li>
 *   <li>alpha = 0.05，双侧。</li>
 * </ul>
 */
public final class McNemarExact {

    public static final String TEST_NAME = "McnemarExact";
    public static final double ALPHA = StatisticalDecision.ALPHA;

    private McNemarExact() {
    }

    /**
     * 计算 exact two-sided McNemar。
     *
     * @param a              conditionA 的 paired binary outcomes
     * @param b              conditionB 的 paired binary outcomes（与 a 等长、同序）
     * @param excludedCount  因无效/缺失而排除的 paired unit 数
     */
    public static StatisticalResult compute(List<Boolean> a, List<Boolean> b, int excludedCount) {
        if (a == null || b == null || a.size() != b.size()) {
            throw new IllegalArgumentException("McNemar 输入必须为等长的 paired binary 序列");
        }

        if (a.isEmpty()) {
            // 无配对 unit：配对不足，不伪造 p 值（Holm 校正需据此排除）
            return new StatisticalResult(TEST_NAME, 0.0, null, StatisticalDecision.INSUFFICIENT_PAIRS,
                    0, excludedCount, 0, 0, 0, 0.0, 0.0, 0, 0);
        }

        int bCount = 0; // A=true, B=false
        int cCount = 0; // A=false, B=true
        for (int i = 0; i < a.size(); i++) {
            boolean x = a.get(i);
            boolean y = b.get(i);
            if (x && !y) {
                bCount++;
            } else if (!x && y) {
                cCount++;
            }
        }

        int discordantN = bCount + cCount;
        double statistic = (double) (bCount - cCount);

        double pValue;
        StatisticalDecision decision;
        if (discordantN == 0) {
            pValue = 1.0;
            decision = StatisticalDecision.NOT_SIGNIFICANT;
        } else {
            int m = Math.min(bCount, cCount);
            double cum = binomialCdf(m, discordantN, 0.5);
            pValue = Math.min(1.0, 2.0 * cum);
            decision = StatisticalDecision.fromPValue(pValue);
        }

        return new StatisticalResult(TEST_NAME, statistic, pValue, decision,
                a.size(), excludedCount,
                bCount, cCount, discordantN,
                0.0, 0.0, 0, 0);
    }

    /** P(X ≤ k) for X ~ Binomial(n, p)，累加 PMF。 */
    static double binomialCdf(int k, int n, double p) {
        double sum = 0.0;
        for (int i = 0; i <= k; i++) {
            sum += binomialPmf(i, n, p);
        }
        return sum;
    }

    private static double binomialPmf(int k, int n, double p) {
        return binomialCoefficient(n, k) * Math.pow(p, k) * Math.pow(1.0 - p, n - k);
    }

    private static double binomialCoefficient(int n, int k) {
        if (k < 0 || k > n) {
            return 0.0;
        }
        k = Math.min(k, n - k);
        double result = 1.0;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }
}
