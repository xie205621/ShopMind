package com.shopmind.orchestrator.port;

import reactor.core.publisher.Mono;

/**
 * 意图分析器抽象接口 — 架构评审新增。
 * <p>
 * 在调用 Memory / RAG 之前，轻量级预判当前用户 Query 的意图类型，
 * 避免不必要的知识检索开销（例如纯闲聊不需要 RAG）。
 * <p>
 * 初期提供基于关键词的规则引擎实现 {@link com.shopmind.orchestrator.pipeline.KeywordIntentAnalyzer}，
 * 未来可替换为微调 BERT 模型。
 */
public interface IntentAnalyzer {

    /**
     * 分析用户意图。
     *
     * @param userMessage 用户当前轮输入
     * @return IntentResult，包含意图分类
     */
    Mono<IntentResult> analyze(String userMessage);

    /**
     * 意图分析结果。
     */
    record IntentResult(
            /** 是否需要检索知识库 */
            boolean requiresKnowledge,
            /** 是否需要调用业务工具 */
            boolean requiresTools,
            /** 用户意图分类标签（如 "商品咨询", "售后问题", "闲聊"） */
            String category
    ) {
        /** 纯文本对话（闲聊），无需 RAG 和 Tool */
        public static IntentResult textOnly(String category) {
            return new IntentResult(false, false, category);
        }

        /** 需要知识检索 */
        public static IntentResult knowledge(String category) {
            return new IntentResult(true, false, category);
        }

        /** 需要工具执行 */
        public static IntentResult tool(String category) {
            return new IntentResult(false, true, category);
        }

        /** 既需要知识又需要工具 */
        public static IntentResult both(String category) {
            return new IntentResult(true, true, category);
        }
    }
}
