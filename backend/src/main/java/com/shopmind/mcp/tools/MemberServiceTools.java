package com.shopmind.mcp.tools;

import com.shopmind.mcp.annotation.McpParam;
import com.shopmind.mcp.annotation.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会员服务工具 — 生产可用的真实 MCP 业务工具（积分/优惠券场景）。
 */
@Component
public class MemberServiceTools {

    private static final Logger log = LoggerFactory.getLogger(MemberServiceTools.class);

    /** 内存会员数据（示例业务数据） */
    private static final Map<String, Map<String, String>> MEMBERS = new LinkedHashMap<>();

    static {
        MEMBERS.put("USER1001", Map.of("points", "3560", "level", "黄金会员"));
        MEMBERS.put("USER1002", Map.of("points", "12800", "level", "铂金会员"));
        MEMBERS.put("USER1003", Map.of("points", "320", "level", "普通会员"));
    }

    /**
     * 查询会员积分与等级。
     *
     * @param userId 会员ID
     * @return 积分与等级描述文本
     */
    @McpTool(name = "queryPoints", description = "查询会员积分余额与会员等级。输入会员ID。")
    public String queryPoints(@McpParam(name = "userId", required = true, description = "会员ID") String userId) {
        log.info("[MemberServiceTools] queryPoints(userId={})", userId);
        Map<String, String> member = MEMBERS.get(userId);
        if (member == null) {
            return "未查询到会员 " + userId + "，请核对会员ID。";
        }
        return String.format("会员 %s：等级=%s，当前积分=%s。", userId, member.get("level"), member.get("points"));
    }

    /**
     * 查询用户可用优惠券。
     *
     * @param userId 会员ID
     * @return 可用优惠券描述文本
     */
    @McpTool(name = "queryCoupons", description = "查询会员名下可用优惠券列表。输入会员ID。")
    public String queryCoupons(@McpParam(name = "userId", required = true, description = "会员ID") String userId) {
        log.info("[MemberServiceTools] queryCoupons(userId={})", userId);
        if (!MEMBERS.containsKey(userId)) {
            return "未查询到会员 " + userId + "，请核对会员ID。";
        }
        return String.format("会员 %s 可用优惠券：满199减20、满399减50、新人首单减30（不可叠加使用）。", userId);
    }
}
