package com.shopmind.evaluation.domain;

/**
 * 框架无关的 Agent 输入 — Phase F: Framework-Agnostic Evaluation。
 * <p>
 * 统一的评测输入格式，屏蔽不同 Agent 框架（ShopMind / LangChain / OpenAI SDK）
 * 的请求格式差异。每个 Adapter 负责将 AgentInput 转换为其目标框架的请求。
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param sessionId   会话隔离标识（对应 ShopMind 的 memoryId）
 * @param userMessage  用户当前轮输入的自然语言文本
 */
public record AgentInput(
        String sessionId,
        String userMessage
) {}
