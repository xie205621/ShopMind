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
 * Mock 大模型适配器 — 用于开发/测试环境（默认 Profile，无需外部 API Key）。
 * <p>
 * 根据用户输入返回模拟的 Token 流，支持工具调用标记 {@code __TOOL_CALL__}。
 * 当 System Prompt 中已包含工具执行结果时，返回最终总结，避免 inner loop 无限循环。
 * 生产环境请使用 {@code @Profile("deepseek")} 或 {@code @Profile("qwen")}。
 */
@Component
@Profile("!prod & !qwen & !deepseek")
public class MockChatModelAdapter implements ChatModelPort {

    private static final Logger log = LoggerFactory.getLogger(MockChatModelAdapter.class);

    private static final String TOOL_RESULT_MARKER = "[工具执行结果:";

    @Override
    public Flux<String> stream(List<ChatMessage> messages, List<ToolSpecification> tools) {
        // 获取最后一条用户消息
        String lastUserMsg = extractLastUserMessage(messages);

        log.debug("[MockLLM] Streaming for user query: '{}'", truncate(lastUserMsg));

        // 已执行过工具（System Prompt 含工具结果）→ 生成最终总结，避免循环
        if (hasToolResult(messages)) {
            return simulateFinalAnswer();
        }

        // 根据关键词模拟工具调用（工具名与 OrderServiceTools / MemberServiceTools 保持一致）
        if (lastUserMsg.contains("退款") || lastUserMsg.contains("付款") || lastUserMsg.contains("支付")) {
            return simulateToolCall("refund", "{\"orderId\":\"ORD20240722001\",\"reason\":\"用户申请退款\"}");
        }
        if (lastUserMsg.contains("订单") || lastUserMsg.contains("查订单")
                || lastUserMsg.contains("物流") || lastUserMsg.contains("快递")) {
            return simulateToolCall("queryOrder", "{\"orderId\":\"ORD20240722001\"}");
        }
        if (lastUserMsg.contains("积分") || lastUserMsg.contains("会员")) {
            return simulateToolCall("queryPoints", "{\"userId\":\"USER1001\"}");
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
     * 模拟工具调用回复：先确认意图，再发射 TOOL_CALL 标记。
     */
    private Flux<String> simulateToolCall(String toolName, String jsonArgs) {
        return Flux.just(
                "正在为您处理", "，请稍候...",
                "\n\n__TOOL_CALL__" + toolName + jsonArgs
        );
    }

    /**
     * 工具执行完成后的最终总结回复。
     */
    private Flux<String> simulateFinalAnswer() {
        return Flux.just("已为您", "处理完成，", "相关结果请见上方工具执行结果。",
                "请问还有其他需要帮助的吗？");
    }

    /**
     * 判断 System Prompt 中是否已包含工具执行结果（用于终止 inner loop）。
     */
    private boolean hasToolResult(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return false;
        return messages.stream().anyMatch(m ->
                "SYSTEM".equals(m.getType())
                        && m.getContent() != null
                        && m.getContent().contains(TOOL_RESULT_MARKER));
    }

    /**
     * 从消息列表中提取最后一条用户消息文本。
     */
    private String extractLastUserMessage(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("USER".equals(messages.get(i).getType())) {
                return messages.get(i).getContent();
            }
        }
        return messages.get(messages.size() - 1).getContent();
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
