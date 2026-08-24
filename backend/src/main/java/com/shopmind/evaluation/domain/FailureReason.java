package com.shopmind.evaluation.domain;

/**
 * 失败归因枚举 — 6_Evaluation_Engine.md §5.3 规范。
 * <p>
 * 对齐 LLM4SE 顶会的失败分类学（Failure Taxonomy），
 * 覆盖从意图识别到工具执行的全链路失败原因。
 * <p>
 * 每个枚举值带有中文标签，便于实验报告的可读性输出。
 */
public enum FailureReason {

    /** 意图识别错误：Agent 未能正确分类用户意图（如将"退货"误判为"闲聊"） */
    WRONG_INTENT("意图识别错误"),

    /** 工具选择错误：Agent 调用了错误的工具（如查订单时调用了退款工具） */
    WRONG_TOOL("工具选择错误"),

    /** 工具参数提取错误：工具名称正确，但 JSON 参数与预期不符 */
    WRONG_PARAMETER("工具参数错误"),

    /** 知识未召回：RAG 引擎未能召回 Ground Truth 所需的知识片段 */
    KNOWLEDGE_MISS("知识未召回"),

    /** 幻觉：回答中包含了 RAG 知识库之外、或与 Ground Truth 矛盾的事实 */
    HALLUCINATION("出现幻觉"),

    /** 安全拦截：执行被 Workflow Policy / 沙箱安全策略阻断 */
    SAFETY_BLOCKED("安全策略拦截"),

    /** 知识未找到：知识库中无相关内容，Agent 正确拒答（Guardrails 生效） */
    KNOWLEDGE_NOT_FOUND("知识未找到-正确拒答"),

    /** API 超时：LLM 推理或工具执行超时 */
    TIMEOUT("API超时");

    private final String label;

    FailureReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 判断该失败原因是否属于"正确拒答"（Agent 行为符合预期）。
     * <p>
     * KNOWLEDGE_NOT_FOUND 表示 Agent 因知识不足正确拒答，
     * SAFETY_BLOCKED 表示 Agent 因安全策略正确拦截。
     * 两者均为 Agent 的正面行为，不应被计为 Task Failure。
     */
    public boolean isCorrectRefusal() {
        return this == KNOWLEDGE_NOT_FOUND || this == SAFETY_BLOCKED;
    }
}
