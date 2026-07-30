package com.shopmind.workflow.domain;

/**
 * 可观测聚合指标 — Workflow_Engine.md §6.3 规范。
 * <p>
 * 在 Trace 结束时聚合的核心性能指标，供 Evaluation Engine 直接消费。
 * 字段设计对齐主流 LLM 可观测平台（如 LangSmith, Helicone）的度量标准。
 * <p>
 * 线程安全：本类实例仅在单次请求内创建，通过 {@code TraceHandle} 逐步填充。
 */
public class ObservabilityMetrics {

    /** 端到端总延迟（毫秒），从接收请求到 Flux complete */
    private long totalLatencyMs;

    /** 首字延迟 TTFT（毫秒），从 LLM 请求发出到收到第一个 Token */
    private long ttftMs;

    /** 输入 Token 数量（System Prompt + History + 用户消息） */
    private int promptTokens;

    /** 输出 Token 数量（LLM 生成的文本 Token） */
    private int completionTokens;

    /** RAG 知识库命中的 Chunk 数量 */
    private int retrievedChunksCount;

    /** Inner Loop 中工具调用总次数 */
    private int toolCallCount;

    // ============================================================
    //  Factory
    // ============================================================

    /** 创建空的指标对象，随后逐步填充 */
    public static ObservabilityMetrics empty() {
        return new ObservabilityMetrics();
    }

    // ============================================================
    //  Accumulation methods (package-private mutation)
    // ============================================================

    /** 累加输出 Token 数（LLM 每生成一个 Token 调用一次） */
    public void accumulateCompletionToken() {
        this.completionTokens++;
    }

    /** 累加知识命中块数 */
    public void accumulateRetrievedChunks(int count) {
        this.retrievedChunksCount += count;
    }

    /** 累加工具调用次数 */
    public void accumulateToolCall() {
        this.toolCallCount++;
    }

    // ============================================================
    //  Getters / Setters
    // ============================================================

    public long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(long totalLatencyMs) { this.totalLatencyMs = totalLatencyMs; }

    public long getTtftMs() { return ttftMs; }
    public void setTtftMs(long ttftMs) { this.ttftMs = ttftMs; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getRetrievedChunksCount() { return retrievedChunksCount; }
    public void setRetrievedChunksCount(int retrievedChunksCount) { this.retrievedChunksCount = retrievedChunksCount; }

    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }

    @Override
    public String toString() {
        return "ObservabilityMetrics{" +
                "totalLatency=" + totalLatencyMs + "ms, " +
                "ttft=" + ttftMs + "ms, " +
                "promptTokens=" + promptTokens + ", " +
                "completionTokens=" + completionTokens + ", " +
                "retrievedChunks=" + retrievedChunksCount + ", " +
                "toolCalls=" + toolCallCount +
                "}";
    }
}
