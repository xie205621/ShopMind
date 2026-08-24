package com.shopmind.experiment;

import com.shopmind.memory.message.ChatMessage;

import java.util.List;

/**
 * 运行时上下文风险评分 — Phase 4 (P4-2.1)。
 * <p>
 * 只使用真实 runtime 文本（userQuery + conversationHistory），
 * <b>禁止</b>读取 GT authorization / contextRisk / riskLabel。
 * <p>
 * 离散风险：
 * <ul>
 *   <li>{@code NORMAL}（无可疑信号）= 0.0</li>
 *   <li>{@code AMBIGUOUS}（所有权不清）= 0.5</li>
 *   <li>{@code EXPLICITLY_SUSPICIOUS}（明确越权/攻击）= 1.0</li>
 * </ul>
 * <p>
 * <b>P4-2.1 修订：</b>可疑风险改为 <b>per-tool</b>（可疑模式 × 工具能力域），
 * 修复「显式可疑 → 所有工具 effectiveRisk=1 → 全裁」缺陷：
 * <ul>
 *   <li>他人订单 / 冒充 / 越权 → queryOrder + refund = 1.0，其余 0.0</li>
 *   <li>批量 → refund = 1.0，其余 0.0</li>
 * </ul>
 * 命中可疑域后不再落入 ambiguous；提示注入类模式（忽略指令）已删除。
 * runtimeIntent 当前不携带文本级安全信号，故不参与匹配。
 * <p>
 * <b>Phase 5-C1.1 修订（Tool Capability / Authorization Semantics Closure）：</b>
 * runtime signal 映射改为 <b>tool-specific</b>，严格依据 {@link ToolStaticRiskCatalog} 能力审计，
 * 先定义权限语义、再映射风险（不得由研究者手工指定 exception）：
 * <ul>
 *   <li>{@code UNAUTHORIZED} → 仅 OWN_DATA 工具 1.0（未认证主体不可访问账户数据）；</li>
 *   <li>{@code OTHER_USER} → 仅 OWN_DATA 工具 1.0（跨用户访问账户数据越权）；</li>
 *   <li>{@code SYSTEM_SCOPE} → 仅非 SYSTEM 能力工具 1.0（目标系统资源，无系统能力工具越界）；</li>
 *   <li>{@code OWN_DATA / UNKNOWN} → 0.0（本人/不确定目标不越权）。</li>
 * </ul>
 * 能力审计证据（workflow v2.3）：queryOrder/refund 输入「订单号」，queryPoints/queryCoupons 输入「会员ID」，
 * 四者均访问账户归属数据，permissionScope 均为 OWN_DATA 且无 SYSTEM 能力。因此
 * UNAUTHORIZED / OTHER_USER / SYSTEM_SCOPE 三者的映射结果对四工具恰好一致（均为 1.0），
 * 其一致性来自统一授权边界（OWN_DATA 权限边界 / 无 SYSTEM 能力），而非无条件的全局风险传播。
 * <p>
 * runtime signal 存在时以它为准（覆盖 query pattern）；缺失时回退 query pattern。
 */
public final class RuntimeContextRiskScorer {

    /** 他人订单 / 冒充 / 越权 模式（命中 → queryOrder + refund = 1.0）。 */
    private static final List<String> AUTHORIZATION_PATTERNS = List.of(
            "别人的", "他人的", "他人订单", "别人订单",
            "替别人", "替他人",
            "冒充", "我是管理员", "我是客服", "我是主管",
            "修改其他用户", "查询其他用户", "其他用户",
            "越权", "绕过");

    /** 批量模式（命中 → refund = 1.0）。 */
    private static final List<String> BATCH_PATTERNS = List.of(
            "批量审批", "批量操作");

    /** 所有权不清/模糊模式（命中即 0.5）。 */
    private static final List<String> AMBIGUOUS_PATTERNS = List.of(
            "别人", "他人", "朋友", "同事", "家人", "亲戚");

    /** 计算指定工具的运行时上下文风险（runtime signal 优先，query pattern 回退，per-tool）。 */
    public double score(RouterContext ctx, String toolName) {
        if (ctx.runtimeAuthorization().isPresent() || ctx.runtimeTargetScope().isPresent()) {
            return runtimeSignalRisk(ctx, toolName);
        }
        return queryPatternRisk(ctx, toolName);
    }

    /**
     * Phase 5-C1.1：基于 runtime signal 的 tool-specific 确定性风险（优先级高于 query pattern）。
     * <p>
     * 每个 (runtimeContext, tool) 组合显式映射到 runtime risk，依据 C1.1 冻结真值表
     * 与 {@link ToolStaticRiskCatalog} 能力审计。
     */
    private double runtimeSignalRisk(RouterContext ctx, String toolName) {
        RuntimeAuthorization authorization = ctx.runtimeAuthorization().orElse(null);
        RuntimeTargetScope targetScope = ctx.runtimeTargetScope().orElse(null);

        if (authorization == RuntimeAuthorization.UNAUTHORIZED) {
            return unauthorizedRisk(toolName);
        }
        if (targetScope == RuntimeTargetScope.SYSTEM_SCOPE) {
            return systemScopeRisk(toolName);
        }
        if (targetScope == RuntimeTargetScope.OTHER_USER) {
            return otherUserRisk(toolName);
        }
        // OWN_DATA / UNKNOWN → 本人/不确定目标不越权
        return RtmpScoringConfig.RISK_NORMAL;
    }

    /**
     * UNAUTHORIZED：未认证主体不可访问任何 OWN_DATA 工具。
     * 能力审计：四工具 permissionScope 均为 OWN_DATA → 均需认证 → 均 1.0；
     * 未知工具无能力记录 → 不越权（0.0）。
     */
    private static double unauthorizedRisk(String toolName) {
        boolean requiresAuth = ToolStaticRiskCatalog.forTool(toolName)
                .map(p -> "OWN_DATA".equals(p.permissionScope()))
                .orElse(false);
        return requiresAuth ? RtmpScoringConfig.RISK_SUSPICIOUS : RtmpScoringConfig.RISK_NORMAL;
    }

    /**
     * SYSTEM_SCOPE：仅当工具 permissionScope == SYSTEM 时才具备 system-scope 合法能力。
     * 能力审计：四工具 permissionScope 均非 SYSTEM → system-scope 目标对四工具均越界 → 均 1.0。
     */
    private static double systemScopeRisk(String toolName) {
        boolean supportsSystemScope = ToolStaticRiskCatalog.forTool(toolName)
                .map(p -> "SYSTEM".equals(p.permissionScope()))
                .orElse(false);
        return supportsSystemScope ? RtmpScoringConfig.RISK_NORMAL : RtmpScoringConfig.RISK_SUSPICIOUS;
    }

    /**
     * OTHER_USER：跨用户账户数据访问越权。能力审计：四工具 permissionScope 均为 OWN_DATA，
     * 且 workflow 语义上四者都要求账户归属标识输入（订单号/会员ID），
     * 故跨用户访问任一账户数据工具均越权 → 四工具 1.0。
     * 与 {@link #unauthorizedRisk} 逻辑一致（均违反 OWN_DATA 权限边界），语义注释不同。
     */
    private static double otherUserRisk(String toolName) {
        boolean ownDataTool = ToolStaticRiskCatalog.forTool(toolName)
                .map(p -> "OWN_DATA".equals(p.permissionScope()))
                .orElse(false);
        return ownDataTool ? RtmpScoringConfig.RISK_SUSPICIOUS : RtmpScoringConfig.RISK_NORMAL;
    }

    /** P4-2.1 原有：query pattern（可疑模式 × 工具能力域，per-tool）。 */
    private double queryPatternRisk(RouterContext ctx, String toolName) {
        String text = combinedText(ctx);

        boolean authorizationHit = matchesAny(text, AUTHORIZATION_PATTERNS);
        boolean batchHit = matchesAny(text, BATCH_PATTERNS);

        // 显式可疑：命中后按工具能力域返回 1.0 / 0.0，不再落入 ambiguous
        if (authorizationHit || batchHit) {
            boolean affected = "refund".equals(toolName)
                    || (authorizationHit && "queryOrder".equals(toolName));
            return affected ? RtmpScoringConfig.RISK_SUSPICIOUS : RtmpScoringConfig.RISK_NORMAL;
        }

        // 所有权不清（ambiguous，context-level 0.5）
        if (matchesAny(text, AMBIGUOUS_PATTERNS)) {
            return RtmpScoringConfig.RISK_AMBIGUOUS;
        }
        return RtmpScoringConfig.RISK_NORMAL;
    }

    private static boolean matchesAny(String text, List<String> patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String combinedText(RouterContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.userQuery() != null) {
            sb.append(ctx.userQuery());
        }
        if (ctx.conversationHistory() != null) {
            for (ChatMessage message : ctx.conversationHistory()) {
                if (message != null && message.getContent() != null) {
                    sb.append(' ').append(message.getContent());
                }
            }
        }
        return sb.toString();
    }
}
