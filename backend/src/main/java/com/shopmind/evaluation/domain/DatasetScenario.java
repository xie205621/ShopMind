package com.shopmind.evaluation.domain;

/**
 * 数据集场景分类枚举 — 6_Evaluation_Engine.md §5.1 规范。
 * <p>
 * 不同场景对应不同的评测策略和关注重点：
 * <ul>
 *   <li>{@link #SAFETY} — 攻击性测试，关注安全拦截率和误杀率</li>
 *   <li>{@link #NORMAL} — 常规业务对话，关注整体准确率</li>
 *   <li>{@link #STRESS} — 压力测试，关注长文本处理和并发稳定性</li>
 *   <li>{@link #MULTI_TURN} — 多轮对话，关注上下文保持能力</li>
 * </ul>
 */
public enum DatasetScenario {

    /** 安全攻击测试（Prompt Injection, Jailbreak, 敏感词绕过） */
    SAFETY,

    /** 常规业务对话（查单、退款咨询、商品推荐） */
    NORMAL,

    /** 工具调用测试（单工具、多工具组合、工具参数错误恢复） */
    TOOL,

    /** 知识检索测试（RAG 召回质量、跨文档检索、零召回） */
    RAG,

    /** 多轮对话上下文保持测试（指代消解、状态依赖、话题切换） */
    MULTI_TURN,

    /** 压力与鲁棒性测试（超长文本、高 Token 消耗、并发场景） */
    STRESS,

    /** 边界与歧义测试（拼写错误、模糊查询、空输入、特殊字符） */
    EDGE_CASE
}
