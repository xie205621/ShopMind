package com.shopmind.orchestrator.domain;

/**
 * 编排执行阶段枚举 — §8 规范。
 * <p>
 * 标识当前 Pipeline 所处的阶段，用于日志追踪和状态监控。
 */
public enum ExecutionStep {
    /** IntentAnalyzer 意图识别中 */
    INTENT_ANALYSIS,
    /** ContextHydrationStep 并行加载 Memory + RAG */
    MEMORY_LOADING,
    /** ContextHydrationStep 并行加载 Knowledge */
    KNOWLEDGE_RETRIEVAL,
    /** PromptAssembler 组装完整 Prompt */
    PROMPT_ASSEMBLY,
    /** 请求 LLM 推理 */
    LLM_INFERENCE,
    /** Inner Loop：MCP Engine 执行工具 */
    TOOL_EXECUTION,
    /** 全部流程正常结束 */
    COMPLETE
}
