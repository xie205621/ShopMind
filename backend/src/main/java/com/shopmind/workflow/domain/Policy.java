package com.shopmind.workflow.domain;

/**
 * 企业安全合规策略 — Workflow_Engine.md §6.2 规范。
 * <p>
 * 每个 Policy 代表一条企业约束规则，在 WorkflowDefinition 中声明，
 * 由 WorkflowRenderer 注入 System Prompt 或由执行层进行硬阻断。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param name    策略名称，如 "禁止泄露进货价"
 * @param content 策略的具体约束文本，将被注入 System Prompt
 * @param level   策略等级（HARD 硬阻断 / SOFT 软约束）
 */
public record Policy(
        String name,
        String content,
        PolicyLevel level
) {

    /**
     * 创建一个 HARD 级别的安全策略（执行层必须强制校验）。
     */
    public static Policy hard(String name, String content) {
        return new Policy(name, content, PolicyLevel.HARD);
    }

    /**
     * 创建一个 SOFT 级别的提示约束（仅注入 Prompt，依赖 LLM 自律）。
     */
    public static Policy soft(String name, String content) {
        return new Policy(name, content, PolicyLevel.SOFT);
    }
}
