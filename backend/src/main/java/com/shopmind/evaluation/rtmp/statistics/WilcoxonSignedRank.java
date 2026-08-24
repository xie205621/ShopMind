package com.shopmind.evaluation.rtmp.statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Wilcoxon signed-rank test（asymptotic, two-sided）— Phase 5-B1。
 * <p>
 * 冻结定义（§11–§17）：
 * <ul>
 *   <li>输入为 paired scalar observations（{@code x_i, y_i}），{@code d_i = x_i - y_i}；</li>
 *   <li>zero differences 在 ranking 前丢弃，不计入 effective N；</li>
 *   <li>非零 |d| 升序排序，相同绝对差值使用 midrank / average rank；</li>
 *   <li>W+ = Σ rank(d&gt;0)，W- = Σ rank(d&lt;0)，statistic = W = min(W+, W-)；</li>
 *   <li>E(W+) = n(n+1)/4；</li>
 *   <li>tie-corrected variance：Var(W+) = [n(n+1)(2n+1) - Σ_j (t_j³ - t_j)] / 24；</li>
 *   <li>continuity correction：|W+ - E| 减 0.5 后标准化为 z；</li>
 *   <li>p = 2(1 - Φ(|z|))，alpha = 0.05；</li>
 *   <li>edge cases：n=0 → p=1；n&lt;2 → p=null（INSUFFICIENT_PAIRS）。</li>
 * </ul>
 */
public final class WilcoxonSignedRank {

    public static final String TEST_NAME = "WilcoxonSignedRankAsymptotic";
    public static final double ALPHA = StatisticalDecision.ALPHA;

    private WilcoxonSignedRank() {
    }

    /**
     * 计算 two-sided asymptotic Wilcoxon signed-rank test。
     *
     * @param x              conditionA 的 paired scalar observations
     * @param y              conditionB 的 paired scalar observations（与 x 等长、同序）
     * @param excludedCount  因无效/缺失而排除的 paired unit 数
     */
    public static StatisticalResult compute(List<Double> x, List<Double> y, int excludedCount) {
        if (x == null || y == null || x.size() != y.size()) {
            throw new IllegalArgumentException("Wilcoxon 输入必须为等长的 paired scalar 序列");
        }

        int pairedN = x.size();
        int n = 0;
        for (int i = 0; i < pairedN; i++) {
            if (x.get(i) - y.get(i) != 0.0) {
                n++;
            }
        }
        int zeroN = pairedN - n;

        if (n == 0) {
            return new StatisticalResult(TEST_NAME, 0.0, 1.0, StatisticalDecision.NOT_SIGNIFICANT,
                    pairedN, excludedCount, 0, 0, 0, 0.0, 0.0, 0, zeroN);
        }
        if (n < 2) {
            return new StatisticalResult(TEST_NAME, 0.0, null, StatisticalDecision.INSUFFICIENT_PAIRS,
                    pairedN, excludedCount, 0, 0, 0, 0.0, 0.0, n, zeroN);
        }

        // 提取非零差值及其符号
        double[] absD = new double[n];
        boolean[] positive = new boolean[n];
        int idx = 0;
        for (int i = 0; i < pairedN; i++) {
            double d = x.get(i) - y.get(i);
            if (d != 0.0) {
                absD[idx] = Math.abs(d);
                positive[idx] = d > 0.0;
                idx++;
            }
        }

        // 按 |d| 升序排序（稳定索引排序）
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> absD[i]));

        // 分配 midrank + 收集 tie group 大小
        double[] rank = new double[n];
        List<Integer> tieSizes = new ArrayList<>();
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && absD[order[j]] == absD[order[i]]) {
                j++;
            }
            int groupSize = j - i;
            double midrank = (i + j + 1) / 2.0;
            for (int k = i; k < j; k++) {
                rank[order[k]] = midrank;
            }
            if (groupSize > 1) {
                tieSizes.add(groupSize);
            }
            i = j;
        }

        double wPlus = 0.0;
        double wMinus = 0.0;
        for (int k = 0; k < n; k++) {
            if (positive[k]) {
                wPlus += rank[k];
            } else {
                wMinus += rank[k];
            }
        }
        double w = Math.min(wPlus, wMinus);

        double e = n * (n + 1.0) / 4.0;

        // tie-corrected variance：Var(W+) = n(n+1)(2n+1)/24 − Σ_j (t_j³ − t_j)/48
        //（标准定义，与 R / SciPy 的 asymptotic Wilcoxon 行为一致）
        double tieCorr = 0.0;
        for (int t : tieSizes) {
            tieCorr += (double) t * t * t - t;
        }
        double variance = n * (n + 1.0) * (2.0 * n + 1.0) / 24.0 - tieCorr / 48.0;

        // 退化防护：全部差值相同导致 variance 为 0 时，无法标准化，判定为配对不足
        if (variance <= 1e-12) {
            return new StatisticalResult(TEST_NAME, w, null, StatisticalDecision.INSUFFICIENT_PAIRS,
                    pairedN, excludedCount, 0, 0, 0, wPlus, wMinus, n, zeroN);
        }

        double z;
        if (wPlus > e) {
            z = (wPlus - e - 0.5) / Math.sqrt(variance);
        } else if (wPlus < e) {
            z = (wPlus - e + 0.5) / Math.sqrt(variance);
        } else {
            z = 0.0;
        }

        double pValue = 2.0 * (1.0 - NormalDistribution.cdf(Math.abs(z)));
        StatisticalDecision decision = StatisticalDecision.fromPValue(pValue);

        return new StatisticalResult(TEST_NAME, w, pValue, decision,
                pairedN, excludedCount, 0, 0, 0, wPlus, wMinus, n, zeroN);
    }
}
