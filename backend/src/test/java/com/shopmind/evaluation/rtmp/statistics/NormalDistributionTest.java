package com.shopmind.evaluation.rtmp.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 正态分布 CDF / erf 的 reference-value 测试 — Phase 5-B1。
 * <p>
 * 参考值来自标准正态分布表（权威值，精确到 15 位有效数字）。erf 采用
 * Abramowitz & Stegun 7.1.26 近似（最大绝对误差 &lt; 1.5e-7），故断言容差取 1e-6。
 */
class NormalDistributionTest {

    @Test
    @DisplayName("Φ(0) = 0.5")
    void cdfAtZero() {
        assertEquals(0.5, NormalDistribution.cdf(0.0), 1e-6);
    }

    @Test
    @DisplayName("Φ(1.0) = 0.841344746")
    void cdfAtOne() {
        assertEquals(0.8413447460685429, NormalDistribution.cdf(1.0), 1e-6);
    }

    @Test
    @DisplayName("Φ(-1.0) = 0.158655254（对称性）")
    void cdfAtNegativeOne() {
        assertEquals(0.15865525393145707, NormalDistribution.cdf(-1.0), 1e-6);
    }

    @Test
    @DisplayName("Φ(1.96) = 0.975002105")
    void cdfAtOnePointNineSix() {
        assertEquals(0.9750021048517795, NormalDistribution.cdf(1.96), 1e-6);
    }

    @Test
    @DisplayName("Φ(2.0) = 0.977249868")
    void cdfAtTwo() {
        assertEquals(0.9772498680518208, NormalDistribution.cdf(2.0), 1e-6);
    }

    @Test
    @DisplayName("erf(1.0) = 0.842700793")
    void erfAtOne() {
        assertEquals(0.8427007929497149, NormalDistribution.erf(1.0), 1e-6);
    }
}
