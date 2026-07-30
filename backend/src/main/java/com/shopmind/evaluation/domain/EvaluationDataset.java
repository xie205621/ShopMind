package com.shopmind.evaluation.domain;

import java.util.Collections;
import java.util.List;

/**
 * 评测数据集 — 6_Evaluation_Engine.md §5.1 规范。
 * <p>
 * 封装一组属于同一场景（Scenario）的 TestCase 集合。
 * 支持按场景过滤、按用例 ID 查询等操作。
 * <p>
 * 使用 Java record 确保不可变语义——数据集加载后不应被修改。
 *
 * @param datasetId  数据集唯一标识
 * @param scenario   场景分类（SAFETY / NORMAL / STRESS / MULTI_TURN）
 * @param testCases  测试用例列表
 */
public record EvaluationDataset(
        String datasetId,
        DatasetScenario scenario,
        List<TestCase> testCases
) {

    /**
     * 紧凑构造器：防御性拷贝，确保 testCases 不为 null。
     */
    public EvaluationDataset {
        testCases = testCases != null
                ? Collections.unmodifiableList(testCases)
                : Collections.emptyList();
    }

    /** 数据集的用例总数 */
    public int size() {
        return testCases.size();
    }

    /** 是否为空数据集 */
    public boolean isEmpty() {
        return testCases.isEmpty();
    }

    /**
     * 过滤出需要工具调用的用例。
     */
    public List<TestCase> toolRequiredCases() {
        return testCases.stream()
                .filter(TestCase::requiresTool)
                .toList();
    }

    /**
     * 过滤出需要知识库召回的用例。
     */
    public List<TestCase> knowledgeRequiredCases() {
        return testCases.stream()
                .filter(TestCase::requiresKnowledge)
                .toList();
    }

    /**
     * 根据 testCaseId 查找用例。
     *
     * @return 找到的 TestCase，若不存在返回 null
     */
    public TestCase findById(String testCaseId) {
        return testCases.stream()
                .filter(tc -> tc.testCaseId().equals(testCaseId))
                .findFirst()
                .orElse(null);
    }
}
