package com.shopmind.experiment;

import java.util.List;
import java.util.Map;

/**
 * 工具语义词典 — Phase 4 (P4-2) lexical 证据源。
 * <p>
 * 通用、手写的工具语义词典（<b>非</b>从 42-case 数据集反向生成），用于把
 * query / history 文本映射为对某个工具的 lexical 证据：
 * <ul>
 *   <li>{@link Evidence#STRONG}：强操作/查询证据（如 "申请退款"、"查一下订单"）→ 1.0</li>
 *   <li>{@link Evidence#WEAK}：弱/信息性证据（如仅出现 "退款"、"订单"）→ 0.6</li>
 *   <li>{@link Evidence#NONE}：无证据 → 0.0</li>
 * </ul>
 * <p>
 * <b>关键约束：</b>仅出现工具相关词不自动视为强操作意图。例如 "看看有没有退款按钮"
 * 不得仅因包含 "退款" 而给出 refund 强证据——"退款" 是弱证据，强证据必须为明确操作短语。
 */
public final class ToolSemanticLexicon {

    public enum Evidence {
        STRONG, WEAK, NONE
    }

    /** 强操作/查询证据词（命中即 STRONG）。 */
    private static final Map<String, List<String>> STRONG_TERMS = Map.of(
            "queryOrder", List.of(
                    "查订单", "查询订单", "查一下订单", "订单状态", "物流状态",
                    "发货进度", "查物流", "订单到哪", "查我的订单", "订单查询"),
            "refund", List.of(
                    "申请退款", "我要退款", "帮我退款", "退款申请", "办理退款",
                    "退货退款", "取消订单", "我要退货", "帮我退货"),
            "queryPoints", List.of(
                    "查积分", "查询积分", "查一下积分", "积分余额", "我的积分",
                    "积分多少", "会员等级", "查会员"),
            "queryCoupons", List.of(
                    "查优惠券", "查询优惠券", "优惠券列表", "我的优惠券",
                    "可用优惠券", "领券", "有什么优惠券")
    );

    /** 弱/信息性证据词（仅命中即 WEAK）。 */
    private static final Map<String, List<String>> WEAK_TERMS = Map.of(
            "queryOrder", List.of("订单", "物流", "发货"),
            "refund", List.of("退款", "退货", "退款按钮", "退款政策", "退款规则", "退款流程", "退款原因"),
            "queryPoints", List.of("积分", "等级", "会员"),
            "queryCoupons", List.of("优惠券", "券", "满减")
    );

    /**
     * 计算某文本对某工具的 lexical 证据。
     * <p>
     * 强证据优先于弱证据：先扫描 STRONG 词，命中即 STRONG；否则扫描 WEAK 词。
     */
    public Evidence evidence(String toolName, String text) {
        if (text == null || text.isBlank()) {
            return Evidence.NONE;
        }
        for (String term : STRONG_TERMS.getOrDefault(toolName, List.of())) {
            if (text.contains(term)) {
                return Evidence.STRONG;
            }
        }
        for (String term : WEAK_TERMS.getOrDefault(toolName, List.of())) {
            if (text.contains(term)) {
                return Evidence.WEAK;
            }
        }
        return Evidence.NONE;
    }
}
