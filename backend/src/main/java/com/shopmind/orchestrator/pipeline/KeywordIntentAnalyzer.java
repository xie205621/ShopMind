package com.shopmind.orchestrator.pipeline;

import com.shopmind.orchestrator.port.IntentAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于关键词的意图分析器（初期实现）— §10 架构评审建议。
 * <p>
 * 使用正则匹配快速预判用户意图，零外部依赖、< 1ms 延迟。
 * 未来可替换为基于轻量分类模型（如微调 BERT）的实现。
 */
@Component
public class KeywordIntentAnalyzer implements IntentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(KeywordIntentAnalyzer.class);

    /** 知识库索引的关键词 */
    private static final Set<String> KNOWLEDGE_KEYWORDS = Set.of(
            "退货", "换货", "退款", "保修", "售后", "政策", "规则",
            "满减", "优惠", "折扣", "活动", "包邮", "运费", "配送",
            "支付", "怎么", "如何", "什么", "哪些", "步骤", "流程"
    );

    /** 工具调用的关键词 */
    private static final Set<String> TOOL_KEYWORDS = Set.of(
            "付款", "支付", "下单", "购买", "订单", "查订单",
            "退", "帮我", "取消", "修改"
    );

    /** 闲聊关键词（纯文本对话，无需 RAG 和 Tool） */
    private static final Pattern CHITCHAT_PATTERN = Pattern.compile(
            "^(你好|hi|hello|谢谢|再见|bye|嗯|哦|好的|ok|知道了).*",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public Mono<IntentResult> analyze(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Mono.just(IntentResult.textOnly("空消息"));
        }

        // 1. 闲聊快速通道
        if (CHITCHAT_PATTERN.matcher(userMessage).matches()) {
            log.debug("[IntentAnalyzer] Chitchat detected: '{}'", substring(userMessage));
            return Mono.just(IntentResult.textOnly("闲聊"));
        }

        // 2. 匹配工具和知识词
        boolean needsKnowledge = containsAny(userMessage, KNOWLEDGE_KEYWORDS);
        boolean needsTools = containsAny(userMessage, TOOL_KEYWORDS);

        IntentResult result;
        if (needsKnowledge && needsTools) {
            result = IntentResult.both("知识与工具");
        } else if (needsKnowledge) {
            result = IntentResult.knowledge("知识检索");
        } else if (needsTools) {
            result = IntentResult.tool("工具执行");
        } else {
            result = IntentResult.textOnly("通用对话");
        }

        log.debug("[IntentAnalyzer] Query='{}', requiresKnowledge={}, requiresTools={}, category={}",
                substring(userMessage), result.requiresKnowledge(), result.requiresTools(), result.category());
        return Mono.just(result);
    }

    private boolean containsAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String substring(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
