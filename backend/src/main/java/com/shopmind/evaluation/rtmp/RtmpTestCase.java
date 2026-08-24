package com.shopmind.evaluation.rtmp;

import java.util.Collections;
import java.util.List;

/**
 * RTMP 测试用例 — Ground Truth（独立于 legacy {@code TestCase}）。
 * <p>
 * 承载 RTMP Safety–Utility 测试集的专有字段，避免将 RTMP 字段无限塞入 legacy
 * {@code TestCase}。Ground Truth 与 Runtime Observation / Evaluation Result 严格分层。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param id                 用例唯一标识，格式 {@code RTMP-NNN}
 * @param query              用户输入的自然语言查询
 * @param taskCategory       任务类别（7 类，见 {@link RtmpTaskCategory}）
 * @param expectedOutcome    预期结果：ANSWER_EXPECTED / REFUSE_EXPECTED
 * @param expectedTool       预期调用的工具名（null 表示不应调用工具）
 * @param expectedToolAction 预期工具行为：CALL / NOT_CALL
 * @param candidateTools     该用例假设可用的全部工具
 * @param toolRiskProfile    预期工具的风险属性
 * @param contextRisk        请求上下文风险
 * @param authorization      用户授权级别：USER / OTHER_USER / ADMIN / UNAUTHORIZED
 * @param riskLabel          安全风险标签：NONE / FINANCIAL / PRIVACY / ...
 * @param adversarial        是否为对抗性/攻击性请求
 * @param expectedReason       预期失败原因（SAFETY_BLOCKED / KNOWLEDGE_NOT_FOUND / null）
 * @param mockResponse         Mock 响应文本（仅作为 Mock 输入，非 runtime observation truth）
 * @param expectedToolSequence 任务允许/期望的合法工具执行序列（GT，Phase 5-C1.1）。
 *                             语义：NOT_CALL → 空；普通 CALL → 单元素；MULTI_TOOL → ≥2；
 *                             顺序有意义；Evaluator 可读，Router/Scorer/Pruner 禁止读取。
 */
public record RtmpTestCase(
        String id,
        String query,
        RtmpTaskCategory taskCategory,
        String expectedOutcome,
        String expectedTool,
        ExpectedToolAction expectedToolAction,
        List<String> candidateTools,
        ToolRiskProfile toolRiskProfile,
        ContextRisk contextRisk,
        String authorization,
        String riskLabel,
        boolean adversarial,
        String expectedReason,
        String mockResponse,
        List<String> expectedToolSequence
) {

    /**
     * 紧凑构造器：防御性拷贝 candidateTools / expectedToolSequence，确保不为 null。
     */
    public RtmpTestCase {
        candidateTools = candidateTools != null
                ? Collections.unmodifiableList(candidateTools)
                : Collections.emptyList();
        expectedToolSequence = expectedToolSequence != null
                ? Collections.unmodifiableList(expectedToolSequence)
                : Collections.emptyList();
    }

    /**
     * 本用例是否预期需要工具调用。
     */
    public boolean requiresTool() {
        return expectedTool != null && !expectedTool.isBlank();
    }
}