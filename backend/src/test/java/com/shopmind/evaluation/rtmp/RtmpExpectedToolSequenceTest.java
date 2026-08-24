package com.shopmind.evaluation.rtmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5-C1.1：{@code expectedToolSequence} 数据集 schema 验证测试。
 * <p>
 * 验证 explicit GT 合法工具序列已正确落地（C1.1-B）：
 * <ul>
 *   <li>MULTI_TOOL case 显式标注序列（≥2）；</li>
 *   <li>普通 CALL 派生单元素序列；NOT_CALL 派生空序列；</li>
 *   <li>序列元素均为合法生产工具；</li>
 *   <li>6 个 MULTI_TOOL case 的冻结序列与 query 语义一致。</li>
 * </ul>
 */
class RtmpExpectedToolSequenceTest {

    private final RtmpEvaluationDataset dataset = RtmpDatasetLoader.load();

    @Test
    @DisplayName("1. sequence 字段加载：MULTI_TOOL case 显式 expectedToolSequence")
    void sequenceFieldLoads() {
        RtmpTestCase tc = dataset.findById("RTMP-033");
        assertNotNull(tc);
        assertEquals(List.of("queryOrder", "refund"), tc.expectedToolSequence());
    }

    @Test
    @DisplayName("2. 普通 CALL 派生单元素序列")
    void ordinaryCallGetsOneElementSequence() {
        RtmpTestCase tc = dataset.findById("RTMP-001");
        assertNotNull(tc);
        assertEquals(ExpectedToolAction.CALL, tc.expectedToolAction());
        assertEquals(1, tc.expectedToolSequence().size());
        assertEquals(List.of(tc.expectedTool()), tc.expectedToolSequence());
    }

    @Test
    @DisplayName("3. NOT_CALL 派生空序列")
    void notCallGetsEmptySequence() {
        RtmpTestCase tc = dataset.findById("RTMP-023");
        assertNotNull(tc);
        assertEquals(ExpectedToolAction.NOT_CALL, tc.expectedToolAction());
        assertTrue(tc.expectedToolSequence().isEmpty());
    }

    @Test
    @DisplayName("4. MULTI_TOOL 序列长度 ≥ 2")
    void multiToolRequiresAtLeastTwo() {
        dataset.cases().stream()
                .filter(tc -> tc.taskCategory() == RtmpTaskCategory.MULTI_TOOL)
                .forEach(tc -> assertTrue(tc.expectedToolSequence().size() >= 2,
                        tc.id() + " MULTI_TOOL 序列应 ≥2"));
    }

    @Test
    @DisplayName("5. 非 MULTI_TOOL 序列长度 ≤ 1")
    void nonMultiToolSequenceAtMostOne() {
        dataset.cases().stream()
                .filter(tc -> tc.taskCategory() != RtmpTaskCategory.MULTI_TOOL)
                .forEach(tc -> assertTrue(tc.expectedToolSequence().size() <= 1,
                        tc.id() + " 非 MULTI_TOOL 序列应 ≤1"));
    }

    @Test
    @DisplayName("6. 序列元素均为合法生产工具")
    void sequenceElementsAreValidTools() {
        for (RtmpTestCase tc : dataset.cases()) {
            for (String tool : tc.expectedToolSequence()) {
                assertTrue(RtmpDatasetLoader.EXPECTED_TOOL_POOL.contains(tool),
                        tc.id() + " 序列含非法工具 " + tool);
            }
        }
    }

    @Test
    @DisplayName("7. CALL 序列非空 / NOT_CALL 序列空（全局一致）")
    void callNotEmptyNotCallEmpty() {
        for (RtmpTestCase tc : dataset.cases()) {
            if (tc.expectedToolAction() == ExpectedToolAction.CALL) {
                assertFalse(tc.expectedToolSequence().isEmpty(), tc.id() + " CALL 应非空");
            } else {
                assertTrue(tc.expectedToolSequence().isEmpty(), tc.id() + " NOT_CALL 应为空");
            }
        }
    }

    @Test
    @DisplayName("8. 6 个 MULTI_TOOL case 的冻结序列与 query 语义一致")
    void multiToolSequencesMatchFrozenAnnotations() {
        assertEquals(List.of("queryOrder", "refund"), seq("RTMP-033"));
        assertEquals(List.of("queryOrder", "queryCoupons"), seq("RTMP-034"));
        assertEquals(List.of("queryPoints", "queryOrder"), seq("RTMP-035"));
        assertEquals(List.of("queryPoints", "queryOrder"), seq("RTMP-036"));
        assertEquals(List.of("queryOrder", "queryCoupons"), seq("RTMP-037"));
        assertEquals(List.of("queryOrder", "refund"), seq("RTMP-038"));
    }

    private List<String> seq(String id) {
        RtmpTestCase tc = dataset.findById(id);
        assertNotNull(tc, "missing case " + id);
        return tc.expectedToolSequence();
    }
}
