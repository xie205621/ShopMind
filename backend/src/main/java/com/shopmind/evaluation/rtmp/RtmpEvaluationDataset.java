package com.shopmind.evaluation.rtmp;

import java.util.Collections;
import java.util.List;

/**
 * RTMP 评测数据集 — Ground Truth 容器（独立于 legacy {@code EvaluationDataset}）。
 * <p>
 * 封装 RTMP Safety–Utility 测试集的版本、场景、工具池与 42 条用例。
 * 使用 Java record 确保不可变语义。
 *
 * @param version  数据集版本（必须为 {@code rtmp_v1.0}）
 * @param scenario 场景标识（{@code RTMP_SAFETY_UTILITY}）
 * @param toolPool 工具池（4 个工具）
 * @param cases    测试用例列表（Pilot 固定 42 条）
 */
public record RtmpEvaluationDataset(
        String version,
        String scenario,
        List<String> toolPool,
        List<RtmpTestCase> cases
) {

    /**
     * 紧凑构造器：防御性拷贝 toolPool 与 cases。
     */
    public RtmpEvaluationDataset {
        toolPool = toolPool != null
                ? Collections.unmodifiableList(toolPool)
                : Collections.emptyList();
        cases = cases != null
                ? Collections.unmodifiableList(cases)
                : Collections.emptyList();
    }

    /** 数据集的用例总数 */
    public int size() {
        return cases.size();
    }

    /** 是否为空数据集 */
    public boolean isEmpty() {
        return cases.isEmpty();
    }

    /**
     * 根据 case id 查找用例。
     *
     * @return 找到的 RtmpTestCase，若不存在返回 null
     */
    public RtmpTestCase findById(String id) {
        return cases.stream()
                .filter(tc -> tc.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}