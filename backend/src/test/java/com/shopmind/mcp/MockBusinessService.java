package com.shopmind.mcp;

import com.shopmind.mcp.annotation.McpParam;
import com.shopmind.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

/**
 * MCP Engine 测试用 Mock 业务 Service。
 * <p>
 * 模拟真实业务场景：商品搜索、模拟付款、订单查询。
 * 各方法被 @McpTool 标记，由 ToolRegistry 启动时自动扫描注册。
 */
@Service
public class MockBusinessService {

    /**
     * 模拟商品搜索。
     */
    @McpTool(name = "searchProduct", description = "根据关键词搜索商品，返回匹配的商品列表")
    public String searchProduct(
            @McpParam(required = true, description = "搜索关键词，如「手机」") String keyword) {
        return "[{\"name\": \"华为Mate 60\", \"price\": 5999}, {\"name\": \"小米14\", \"price\": 3999}]";
    }

    /**
     * 模拟付款，需提供订单号和金额。
     */
    @McpTool(name = "confirmPayment", description = "执行模拟付款，需提供订单号")
    public String payOrder(
            @McpParam(required = true, description = "18位订单编号") String orderNo,
            @McpParam(required = true, description = "付款金额（元）") double amount) {
        if (amount > 10000) {
            throw new IllegalArgumentException("单笔付款金额不能超过10000元");
        }
        return "付款成功：订单号 " + orderNo + "，金额 " + amount + " 元";
    }

    /**
     * 模拟订单查询。
     */
    @McpTool(name = "mockQueryOrder", description = "根据订单号查询订单状态（测试专用，避免与生产 queryOrder 冲突）")
    public String mockQueryOrder(
            @McpParam(required = true, description = "18位订单编号") String orderNo) {
        return "{\"orderNo\": \"" + orderNo + "\", \"status\": \"已发货\", \"expressNo\": \"SF1234567890\"}";
    }

    /**
     * 长耗时方法 — 模拟业务延时，用于测试超时熔断。
     */
    @McpTool(name = "slowTask", description = "模拟耗时任务（测试超时熔断）")
    public String slowTask() throws InterruptedException {
        Thread.sleep(5000); // 5 秒，超过默认 3000ms 超时
        return "任务完成";
    }
}
