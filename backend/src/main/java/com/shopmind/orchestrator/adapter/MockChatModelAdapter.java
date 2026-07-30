package com.shopmind.orchestrator.adapter;

import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.ChatModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Mock 大模型适配器 — 用于开发/测试环境。
 * <p>
 * 根据用户输入返回模拟的 Token 流，支持工具调用标记。
 * 生产环境应替换为 OpenAIStreamingAdapter 或 DashScopeStreamingAdapter。
 */
@Component
@Profile("!prod & !qwen & !deepseek")
public class MockChatModelAdapter implements ChatModelPort {

    private static final Logger log = LoggerFactory.getLogger(MockChatModelAdapter.class);

    @Override
    public Flux<String> stream(List<ChatMessage> messages, List<ToolSpecification> tools) {
        // 获取最后一条用户消息
        String lastUserMsg = extractLastUserMessage(messages);

        log.debug("[MockLLM] Streaming for user query: '{}'", truncate(lastUserMsg));

        // 根据关键词模拟不同的回复
        if (lastUserMsg.contains("付款") || lastUserMsg.contains("支付")) {
            // 模拟工具调用
            return simulateToolCall("confirmPayment", "{\"orderNo\":\"ORD20240722001\"}", messages);
        }
        if (lastUserMsg.contains("订单") || lastUserMsg.contains("查订单")) {
            return simulateToolCall("queryOrder", "{\"orderNo\":\"ORD20240722001\"}", messages);
        }
        // 默认纯文本回复
        return simulateTextResponse(lastUserMsg);
    }

    /**
     * 模拟纯文本流式回复。
     */
    private Flux<String> simulateTextResponse(String query) {
        return Flux.just(
                "好的，", "我来", "为您", "解答", "。\n\n",
                "这是", "关于", "\"", query, "\"", "的", "模拟", "回答", "。"
        );
    }

    /**
     * 模拟工具调用回复。
     * LLM 首先确认意图，然后发射 TOOL_CALL 标记，最后返回基于 Observation 的总结。
     */
    private Flux<String> simulateToolCall(String toolName, String jsonArgs, List<ChatMessage> messages) {
        return Flux.just(
                "正在为您处理", "，请稍候...",
                "\n\n__TOOL_CALL__" + toolName + jsonArgs
        );
    }

    /**
     * 从消息列表中提取最后一条用户消息文本。
     */
    private String extractLastUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        // 取最后一条消息（通常是当前 UserMessage）
        return messages.get(messages.size() - 1).getContent();
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
