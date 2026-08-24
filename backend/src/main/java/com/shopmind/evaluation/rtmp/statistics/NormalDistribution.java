package com.shopmind.evaluation.rtmp.statistics;

/**
 * 标准正态分布累积分布函数 Φ(z) — Phase 5-B1。
 * <p>
 * 仅用于 Wilcoxon signed-rank 的 asymptotic two-sided p-value 计算：
 * {@code p = 2(1 - Φ(|z|))}。项目无 Apache Commons Math / Statistics 等统计依赖，
 * 故本地实现确定性 erf 近似（Abramowitz & Stegun 7.1.26，最大绝对误差 &lt; 1.5e-7），
 * 满足 p-value 的 double 精度要求，且不引入新依赖。
 */
public final class NormalDistribution {

    private NormalDistribution() {
    }

    /**
     * 标准正态 CDF：Φ(z) = 0.5 * (1 + erf(z / √2))。
     */
    public static double cdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /**
     * 误差函数 erf(x)，Abramowitz & Stegun 7.1.26 近似。
     * 对负 x 使用 erf(-x) = -erf(x)。
     */
    public static double erf(double x) {
        boolean negative = x < 0.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double poly = (((((1.061405429 * t) - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t;
        double y = 1.0 - poly * Math.exp(-ax * ax);
        return negative ? -y : y;
    }
}
