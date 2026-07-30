package com.shopmind.evaluation.domain;

/**
 * 实验指标汇总 — 6_Evaluation_Engine.md §5.3 规范（v2.1 新增）。
 * <p>
 * 聚合所有 TestCase 的评估结果后计算出的统计指标。
 * 每一项均为 0.0 ~ 1.0 的比率或毫秒值，直接对应 §3 的八个评估维度。
 * <p>
 * 使用 Java record 确保不可变语义——Report 一旦生成，指标不应被修改。
 *
 * @param intentAccuracy          意图识别准确率（正确数 / 总数）
 * @param avgRecallAtK            平均知识召回率
 * @param hallucinationRate       幻觉率（幻觉数 / 总数，越低越好）
 * @param toolAccuracy            工具调用准确率
 * @param taskSuccessRate         端到端任务成功率
 * @param avgTtftMs               平均首字延迟（毫秒）
 * @param p95LatencyMs            端到端延迟 P95（毫秒）
 * @param workflowCompletionRate  工作流完整执行率（未因异常断裂的比例）
 */
public record MetricSummary(
        double intentAccuracy,
        double avgRecallAtK,
        double hallucinationRate,
        double toolAccuracy,
        double taskSuccessRate,
        double avgTtftMs,
        double p95LatencyMs,
        double workflowCompletionRate
) {

    /**
     * 创建一个全零的初始指标（用于 reduce 聚合的种子值）。
     */
    public static MetricSummary empty() {
        return new MetricSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * 格式化输出为可读的多行字符串（用于 Markdown 报告）。
     */
    public String toMarkdownTable() {
        return String.format("""
                | 维度 | 指标 | 值 |
                |------|------|-----|
                | 能力 | 意图准确率 | %.1f%% |
                | 知识 | 平均召回率@K | %.3f |
                | 可靠性 | 幻觉率 | %.1f%% |
                | 执行 | 工具准确率 | %.1f%% |
                | 成功率 | 任务成功率 | %.1f%% |
                | 性能 | 平均TTFT | %.0f ms |
                | 性能 | P95延迟 | %.0f ms |
                | 鲁棒性 | 工作流完成率 | %.1f%% |
                """,
                intentAccuracy * 100,
                avgRecallAtK,
                hallucinationRate * 100,
                toolAccuracy * 100,
                taskSuccessRate * 100,
                avgTtftMs,
                p95LatencyMs,
                workflowCompletionRate * 100
        );
    }
}
