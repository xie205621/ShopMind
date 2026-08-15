package com.shopmind.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * 对话流式事件 — Orchestrator 向前端/调用方透传的结构化 SSE 事件。
 * <p>
 * 使用 Jackson 多态序列化：每个事件 JSON 均带 {@code "type"} 字段，
 * 与前端 {@code SSEEvent} 协议一一对应。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatStreamEvent.Token.class, name = "token"),
        @JsonSubTypes.Type(value = ChatStreamEvent.Intent.class, name = "intent"),
        @JsonSubTypes.Type(value = ChatStreamEvent.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ChatStreamEvent.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ChatStreamEvent.Done.class, name = "done"),
        @JsonSubTypes.Type(value = ChatStreamEvent.Error.class, name = "error")
})
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Token, ChatStreamEvent.Intent, ChatStreamEvent.ToolCall,
                ChatStreamEvent.ToolResult, ChatStreamEvent.Done, ChatStreamEvent.Error {

    /** 纯文本 token 增量 */
    record Token(String content) implements ChatStreamEvent {}

    /** 意图分析结果 */
    record Intent(String category, boolean requiresKnowledge, boolean requiresTools, double confidence)
            implements ChatStreamEvent {}

    /** 工具调用开始 */
    record ToolCall(String callId, String toolName, Map<String, Object> args) implements ChatStreamEvent {}

    /** 工具执行结果 */
    record ToolResult(String callId, boolean success, String output, long latencyMs) implements ChatStreamEvent {}

    /** 流结束（含统计信息） */
    record Done(String sessionId, Stats stats) implements ChatStreamEvent {}

    /** 流异常终止 */
    record Error(String code, String message) implements ChatStreamEvent {}

    /** 流统计信息 */
    record Stats(long ttftMs, long totalMs, TokenUsage tokens) {}

    /** Token 用量统计 */
    record TokenUsage(int prompt, int completion) {}
}
