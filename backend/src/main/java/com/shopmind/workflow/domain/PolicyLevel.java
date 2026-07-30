package com.shopmind.workflow.domain;

/**
 * 安全策略等级枚举 — Workflow_Engine.md §6.2 规范。
 * <p>
 * 用于区分策略是硬阻断（执行层拦截）还是软约束（仅注入 Prompt）。
 */
public enum PolicyLevel {

    /**
     * 硬阻断：必须在输出层或执行层进行强校验。
     * 例如：沙箱支付校验、禁止输出进货价。
     * 违反 HARD 策略时，应直接拒绝执行。
     */
    HARD,

    /**
     * 软约束：仅作为 System Prompt 的一部分注入，依赖 LLM 自身遵守。
     * 例如：语气友好、不要承诺无法实现的退款。
     * 违反 SOFT 策略时，仅记录告警。
     */
    SOFT
}
