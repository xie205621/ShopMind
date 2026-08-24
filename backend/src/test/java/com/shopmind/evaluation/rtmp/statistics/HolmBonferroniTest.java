package com.shopmind.evaluation.rtmp.statistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-C1：Holm-Bonferroni 校正纯算法单元测试（§26/§27/§31 Statistics）。
 * <p>
 * 验证 step-down 校正公式：
 * <pre>
 * adjusted p(k) = min(1, max_{j≤k} (m − j + 1) · p(j))
 * </pre>
 * 且 null（无法检验）不参与排序、保持 null。
 */
class HolmBonferroniTest {

    @Test
    @DisplayName("升序 p-values [0.01,0.02,0.03] → adjusted [0.03,0.04,0.04]")
    void ascendingInput() {
        Double[] result = HolmBonferroni.adjust(new Double[]{0.01, 0.02, 0.03});
        assertAdjusted(new Double[]{0.03, 0.04, 0.04}, result);
    }

    @Test
    @DisplayName("乱序 p-values 保持原始顺序映射")
    void unorderedInputPreservesOrder() {
        Double[] result = HolmBonferroni.adjust(new Double[]{0.03, 0.01, 0.02});
        assertAdjusted(new Double[]{0.04, 0.03, 0.04}, result);
    }

    @Test
    @DisplayName("null 不参与排序且保持 null")
    void nullPreserved() {
        Double[] result = HolmBonferroni.adjust(new Double[]{0.01, null, 0.02});
        assertEquals(0.02, result[0], 1e-12);
        assertNull(result[1]);
        assertEquals(0.02, result[2], 1e-12);
    }

    @Test
    @DisplayName("adjusted p 上限 cap 到 1.0")
    void cappedAtOne() {
        Double[] result = HolmBonferroni.adjust(new Double[]{0.9, 0.9, 0.9});
        assertAdjusted(new Double[]{1.0, 1.0, 1.0}, result);
    }

    @Test
    @DisplayName("空输入 / 全 null → 全 null（配对不足不产生 adjusted p）")
    void emptyOrAllNull() {
        assertAdjusted(new Double[]{}, HolmBonferroni.adjust(new Double[]{}));
        assertAdjusted(new Double[]{null, null}, HolmBonferroni.adjust(new Double[]{null, null}));
    }

    @Test
    @DisplayName("单调非递减：adjusted p 不随原始 p 下降")
    void monotonicNonDecreasing() {
        Double[] result = HolmBonferroni.adjust(new Double[]{0.005, 0.03, 0.02, 0.07});
        for (int i = 1; i < result.length; i++) {
            assertTrue(result[i] >= result[i - 1] - 1e-12, "adjusted p 必须单调非递减");
        }
        assertTrue(result[0] <= 1.0);
    }

    /** 逐元素比较（double 运算误差容忍 + null 语义）。 */
    private static void assertAdjusted(Double[] expected, Double[] actual) {
        assertEquals(expected.length, actual.length, "长度不一致");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == null) {
                assertNull(actual[i], "index " + i + " 应为 null");
            } else {
                assertNotNull(actual[i], "index " + i + " 不应为 null");
                assertEquals(expected[i], actual[i], 1e-12, "index " + i + " adjusted p 不符");
            }
        }
    }
}
