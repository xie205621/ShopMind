package com.shopmind.experiment;

import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.IntentAnalyzer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relevance 特征映射 — Phase 4 (P4-2)。
 * <p>
 * 对单个工具计算三个相互独立的 relevance 特征：
 * <pre>
 * RelevanceScore = max(intentScore, lexicalScore, descriptionCompatibilityScore)
 * </pre>
 * <p>
 * 输入仅来自 RouterContext（userQuery / conversationHistory / runtimeIntent /
 * toolName / description / parameters），<b>禁止</b>读取 expectedTool 等任何 GT 字段。
 */
public final class RelevanceScorer {

    /** 通用 intent 域关键词（工具名 → category 域关键词），用于把 intent category 映射到工具域。 */
    private static final Map<String, List<String>> INTENT_DOMAIN_KEYWORDS = Map.of(
            "queryOrder", List.of("订单", "物流", "发货"),
            "refund", List.of("退款", "退货", "售后"),
            "queryPoints", List.of("积分", "会员", "等级"),
            "queryCoupons", List.of("优惠券", "券")
    );

    private final ToolSemanticLexicon lexicon = new ToolSemanticLexicon();

    /** 计算某工具的完整 relevance 三特征。 */
    public RelevanceScore score(ToolRuntimeMetadata tool, RouterContext ctx) {
        return new RelevanceScore(
                intentScore(tool, ctx),
                lexicalScore(tool, ctx),
                descriptionCompatibilityScore(tool, ctx));
    }

    // ============================================================
    //  A. intentScore — 强兼容 1.0 / 不兼容 0.0
    // ============================================================

    private double intentScore(ToolRuntimeMetadata tool, RouterContext ctx) {
        IntentAnalyzer.IntentResult intent = ctx.runtimeIntent();
        if (intent == null || !intent.requiresTools()) {
            return RtmpScoringConfig.INTENT_INCOMPATIBLE;
        }
        String category = intent.category();
        if (category == null || category.isBlank()) {
            return RtmpScoringConfig.INTENT_INCOMPATIBLE;
        }
        for (String keyword : INTENT_DOMAIN_KEYWORDS.getOrDefault(tool.toolName(), List.of())) {
            if (category.contains(keyword)) {
                return RtmpScoringConfig.INTENT_COMPATIBLE;
            }
        }
        // 当前 KeywordIntentAnalyzer 的 category 为粗粒度标签（"工具执行"/"知识与工具"等），
        // 不携带具体工具域信息，故不强断言与某工具的兼容性。
        return RtmpScoringConfig.INTENT_INCOMPATIBLE;
    }

    // ============================================================
    //  B. lexicalScore — 强 1.0 / 弱 0.6 / 无 0.0
    // ============================================================

    private double lexicalScore(ToolRuntimeMetadata tool, RouterContext ctx) {
        ToolSemanticLexicon.Evidence evidence = lexicon.evidence(tool.toolName(), ctx.userQuery());
        if (ctx.conversationHistory() != null) {
            for (ChatMessage message : ctx.conversationHistory()) {
                if (message != null) {
                    evidence = stronger(evidence, lexicon.evidence(tool.toolName(), message.getContent()));
                }
            }
        }
        return switch (evidence) {
            case STRONG -> RtmpScoringConfig.LEXICAL_STRONG;
            case WEAK -> RtmpScoringConfig.LEXICAL_WEAK;
            case NONE -> RtmpScoringConfig.LEXICAL_NONE;
        };
    }

    private static ToolSemanticLexicon.Evidence stronger(ToolSemanticLexicon.Evidence a,
                                                         ToolSemanticLexicon.Evidence b) {
        if (a == ToolSemanticLexicon.Evidence.STRONG || b == ToolSemanticLexicon.Evidence.STRONG) {
            return ToolSemanticLexicon.Evidence.STRONG;
        }
        if (a == ToolSemanticLexicon.Evidence.WEAK || b == ToolSemanticLexicon.Evidence.WEAK) {
            return ToolSemanticLexicon.Evidence.WEAK;
        }
        return ToolSemanticLexicon.Evidence.NONE;
    }

    // ============================================================
    //  C. descriptionCompatibilityScore — 弱证据 0.3 / 0.0
    // ============================================================

    private double descriptionCompatibilityScore(ToolRuntimeMetadata tool, RouterContext ctx) {
        String toolText = descriptionText(tool);
        if (sharesBigram(ctx.userQuery(), toolText)) {
            return RtmpScoringConfig.DESCRIPTION_COMPATIBLE;
        }
        if (ctx.conversationHistory() != null) {
            for (ChatMessage message : ctx.conversationHistory()) {
                if (message != null && sharesBigram(message.getContent(), toolText)) {
                    return RtmpScoringConfig.DESCRIPTION_COMPATIBLE;
                }
            }
        }
        return RtmpScoringConfig.DESCRIPTION_NONE;
    }

    /** 拼接工具 description + 参数名 + 参数描述，作为确定性兼容的对比文本。 */
    private String descriptionText(ToolRuntimeMetadata tool) {
        StringBuilder sb = new StringBuilder();
        if (tool.description() != null) {
            sb.append(tool.description());
        }
        if (tool.parameters() != null) {
            for (ParameterSpec p : tool.parameters()) {
                if (p.getName() != null) {
                    sb.append(' ').append(p.getName());
                }
                if (p.getDescription() != null) {
                    sb.append(' ').append(p.getDescription());
                }
            }
        }
        return sb.toString();
    }

    /** 两个文本是否共享任一 2-char bigram（确定性 token/term 兼容）。 */
    private static boolean sharesBigram(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        for (String bg : bigramsA) {
            if (bigramsB.contains(bg)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> bigrams(String text) {
        String normalized = text.replaceAll("[\\s，。、：；！？,.!?（）()\\[\\]【】\"'“”‘’]+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            set.add(normalized.substring(i, i + 2));
        }
        return set;
    }
}
