package com.shopmind.evaluation.rtmp.statistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Holm-Bonferroni 校正 — Phase 5-C1。
 * <p>
 * 对 confirmatory primary family（H1/H2/H3）的一组 two-sided p-value 做
 * step-down 校正，控制 family-wise error rate。{@code null}（配对不足，无法检验）
 * 不参与排序，其 adjusted p-value 保持 {@code null}。
 * <p>
 * 算法（对升序 p(1) ≤ p(2) ≤ … ≤ p(m)）：
 * <pre>
 * adjusted p(k) = min(1, max_{j≤k} (m − j + 1) · p(j))
 * </pre>
 */
public final class HolmBonferroni {

    private HolmBonferroni() {
    }

    /**
     * 对一组 p-value 做 Holm 校正，返回与输入同序的 adjusted p-value。
     *
     * @param pValues 原始 p-value（可含 null，表示无法检验）
     * @return 与输入等长的 adjusted p-value（null 位置保持 null）
     */
    public static Double[] adjust(Double[] pValues) {
        int n = pValues.length;
        Double[] result = new Double[n];

        List<Integer> nonNull = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (pValues[i] != null) {
                nonNull.add(i);
            }
        }

        int m = nonNull.size();
        if (m == 0) {
            return result;
        }

        List<Integer> order = new ArrayList<>(nonNull);
        order.sort(Comparator.comparingDouble(i -> pValues[i]));

        double[] adjusted = new double[m];
        double prev = 0.0;
        for (int k = 0; k < m; k++) {
            int originalIndex = order.get(k);
            double candidate = (m - k) * pValues[originalIndex];
            prev = Math.max(prev, candidate);
            adjusted[k] = Math.min(1.0, prev);
        }

        for (int k = 0; k < m; k++) {
            result[order.get(k)] = adjusted[k];
        }
        return result;
    }
}
