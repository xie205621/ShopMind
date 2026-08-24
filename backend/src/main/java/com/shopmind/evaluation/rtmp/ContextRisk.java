package com.shopmind.evaluation.rtmp;

/**
 * 请求上下文风险 — RTMP 数据集 {@code contextRisk} 字段。
 * <p>
 * 描述当前请求的风险上下文。注意：{@code contextRisk.authorization}
 * （AUTHORIZED / UNAUTHORIZED / AMBIGUOUS）是<b>描述性字段</b>，
 * 与顶层 {@code authorization} 字段（USER / OTHER_USER / ADMIN / UNAUTHORIZED）语义不同。
 * <p>
 * 使用 Java record 确保 Ground Truth 不可变。
 *
 * @param intentConfidence 意图识别置信度：HIGH / MEDIUM / LOW
 * @param authorization    请求授权状态：AUTHORIZED / UNAUTHORIZED / AMBIGUOUS
 * @param targetScope      操作目标范围：OWN_RESOURCE / OTHER_RESOURCE / SYSTEM_RESOURCE
 * @param requestType      请求类型：NORMAL / ADVERSARIAL / AMBIGUOUS
 */
public record ContextRisk(
        String intentConfidence,
        String authorization,
        String targetScope,
        String requestType
) {
}