package com.shopmind.mcp.tools;

import com.shopmind.mcp.annotation.McpParam;
import com.shopmind.mcp.annotation.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单服务工具 — 生产可用的真实 MCP 业务工具（售前/售后场景）。
 * <p>
 * 通过 {@code @McpTool} 暴露给 LLM，由 {@link com.shopmind.mcp.registry.ToolRegistry}
 * 在启动时自动扫描注册。数据为内存态示例业务数据，可替换为订单服务真实接口。
 */
@Component
public class OrderServiceTools {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceTools.class);

    /** 内存订单数据（示例业务数据） */
    private static final Map<String, Map<String, String>> ORDERS = new LinkedHashMap<>();

    static {
        ORDERS.put("ORD20240722001", Map.of(
                "status", "已发货",
                "logistics", "顺丰速运 SF1234567890",
                "progress", "运输中，预计明日送达",
                "amount", "299.00"));
        ORDERS.put("ORD20240315007", Map.of(
                "status", "已签收",
                "logistics", "中通快递 ZT9876543210",
                "progress", "已签收（2024-03-18 14:22）",
                "amount", "158.00"));
        ORDERS.put("ORD20240531012", Map.of(
                "status", "待发货",
                "logistics", "暂无物流信息",
                "progress", "仓库备货中",
                "amount", "69.90"));
    }

    /**
     * 查询订单状态与物流信息。
     *
     * @param orderId 订单号
     * @return 订单状态描述文本
     */
    @McpTool(name = "queryOrder", description = "查询用户订单状态、物流信息、发货进度。输入订单号。")
    public String queryOrder(@McpParam(name = "orderId", required = true, description = "订单号") String orderId) {
        log.info("[OrderServiceTools] queryOrder(orderId={})", orderId);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "未查询到订单 " + orderId + "，请核对订单号是否正确。";
        }
        return String.format("订单 %s：状态=%s，物流=%s，进度=%s，金额=%s 元。",
                orderId, order.get("status"), order.get("logistics"),
                order.get("progress"), order.get("amount"));
    }

    /**
     * 处理退款申请。
     *
     * @param orderId 订单号
     * @param reason  退款原因
     * @return 退款处理结果描述文本
     */
    @McpTool(name = "refund", description = "处理退款申请。需要提供订单号和退款原因。")
    public String refund(@McpParam(name = "orderId", required = true, description = "订单号") String orderId,
                         @McpParam(name = "reason", description = "退款原因") String reason) {
        log.info("[OrderServiceTools] refund(orderId={}, reason={})", orderId, reason);
        Map<String, String> order = ORDERS.get(orderId);
        if (order == null) {
            return "退款失败：未查询到订单 " + orderId + "，请核对订单号。";
        }
        if ("已签收".equals(order.get("status"))) {
            return "订单 " + orderId + " 已签收，退款需人工审核。已为您提交退款申请（原因：" + reason + "），退款将在 3 个工作日内原路退回。";
        }
        return "订单 " + orderId + " 退款申请已受理（原因：" + reason + "），预计 3 个工作日内原路退回。";
    }
}
