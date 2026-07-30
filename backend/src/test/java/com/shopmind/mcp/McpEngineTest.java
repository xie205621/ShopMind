package com.shopmind.mcp;

import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.mcp.registry.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Engine 测试套件 — 严格对应 MCP_Engine.md 第 12 节 Test Plan。
 */
@SpringBootTest
class McpEngineTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private McpEngine mcpEngine;

    // ============================================================
    //  Test 1: 启动注册测试（第 12 节）
    // ============================================================

    @Test
    @DisplayName("启动测试：Spring Boot 启动时 ToolRegistry 正确扫描 @McpTool 注解")
    void shouldScanMcpToolAnnotationsOnStartup() {
        List<ToolSpecification> tools = mcpEngine.discoverTools();
        assertThat(tools).hasSize(4); // searchProduct + confirmPayment + queryOrder + slowTask

        assertThat(registry.getToolCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("启动测试：注册的工具名与 @McpTool.name() 一致")
    void shouldRegisterCorrectToolNames() {
        assertThat(registry.getTool("searchProduct")).isNotNull();
        assertThat(registry.getTool("confirmPayment")).isNotNull();
        assertThat(registry.getTool("queryOrder")).isNotNull();
        assertThat(registry.getTool("slowTask")).isNotNull();
    }

    @Test
    @DisplayName("启动测试：工具参数描述被正确解析（@McpParam.required + description）")
    void shouldParseParameterSpecsCorrectly() {
        ToolSpecification payOrder = registry.getTool("confirmPayment");

        List<ParameterSpec> params = payOrder.getParameters();
        assertThat(params).hasSize(2);

        // 参数1: orderNo — required=true, type=String
        assertThat(params.get(0).getName()).isEqualTo("orderNo");
        assertThat(params.get(0).getType()).isEqualTo("String");
        assertThat(params.get(0).isRequired()).isTrue();
        assertThat(params.get(0).getDescription()).contains("18位订单编号");

        // 参数2: amount — required=true, type=double
        assertThat(params.get(1).getName()).isEqualTo("amount");
        assertThat(params.get(1).getType()).isEqualTo("double");
        assertThat(params.get(1).isRequired()).isTrue();
        assertThat(params.get(1).getDescription()).contains("付款金额");
    }

    // ============================================================
    //  Test 2: 正常调用测试
    // ============================================================

    @Test
    @DisplayName("正常调用：无参 Tool 正确返回结果")
    void shouldExecuteSimpleTool() {
        String result = mcpEngine.executeTool("queryOrder",
                "{\"orderNo\": \"202401010000001001\"}");
        assertThat(result).contains("已发货");
        assertThat(result).contains("SF1234567890");
    }

    @Test
    @DisplayName("正常调用：多参 Tool 正确返回结果")
    void shouldExecuteMultiParamTool() {
        String result = mcpEngine.executeTool("confirmPayment",
                "{\"orderNo\": \"202401010000001001\", \"amount\": 5999}");
        assertThat(result).contains("付款成功");
        assertThat(result).contains("5999");
    }

    // ============================================================
    //  Test 3: 异常回环测试（第 12 节）
    // ============================================================

    @Nested
    @DisplayName("异常回环测试 — §11 降级策略")
    class ExceptionLoopbackTests {

        @Test
        @DisplayName("ToolNotFoundException：不存在的工具名，返回降级提示")
        void shouldReturnDegradedMessageForUnknownTool() {
            String result = mcpEngine.executeTool("ghostTool", "{}");
            assertThat(result).isEqualTo("工具不存在，请重新规划");
        }

        @Test
        @DisplayName("ParameterBindingException：缺少必填参数，返回降级提示")
        void shouldReturnDegradedMessageForMissingRequiredParam() {
            String result = mcpEngine.executeTool("confirmPayment",
                    "{\"orderNo\": \"123\"}");
            // 缺少 amount 参数
            assertThat(result).contains("参数错误");
            assertThat(result).contains("请向用户追问");
            assertThat(result).contains("amount");
        }

        @Test
        @DisplayName("ParameterBindingException：非法 JSON，返回降级提示")
        void shouldReturnDegradedMessageForMalformedJson() {
            String result = mcpEngine.executeTool("searchProduct",
                    "not-a-json");
            assertThat(result).contains("参数格式错误");
        }

        @Test
        @DisplayName("业务异常降级：business exception → 原样返回 message 给 LLM")
        void shouldReturnBusinessExceptionMessageToLLM() {
            String result = mcpEngine.executeTool("confirmPayment",
                    "{\"orderNo\": \"202401010000001001\", \"amount\": 99999}");
            // Mock 业务规则：单笔付款 >10000 抛出异常
            assertThat(result).contains("不能超过10000元");
        }
    }

    // ============================================================
    //  Test 4: 超时熔断测试（第 13 节验收标准）
    // ============================================================

    @Test
    @DisplayName("超时熔断：模拟 5 秒延时 → 超时降级")
    void shouldTimeoutAndReturnDegradedMessage() {
        long start = System.currentTimeMillis();
        String result = mcpEngine.executeTool("slowTask", "{}");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("工具执行超时，请稍后重试");
        // 超时应在 4000ms 内返回（3000ms 超时 + 1s 容差）
        assertThat(elapsed).isLessThan(4000);
    }

    // ============================================================
    //  Test 5: 验收标准（第 13 节）
    // ============================================================

    @Test
    @DisplayName("验收标准 §13.2：非法参数拦截返回规范错误 JSON")
    void shouldInterceptIllegalParameters() {
        String result = mcpEngine.executeTool("searchProduct", "{}");
        assertThat(result).contains("参数错误");
        assertThat(result).contains("keyword");
    }

    @Test
    @DisplayName("验收标准 §13.4：新增业务模块无需修改 MCP 代码即可被 Agent 发现")
    void shouldAutoDiscoverNewlyRegisteredTools() {
        // 所有 @McpTool 标记的方法都在 discoverTools() 中可见
        List<ToolSpecification> tools = mcpEngine.discoverTools();
        List<String> toolNames = tools.stream()
                .map(ToolSpecification::getToolName)
                .toList();

        assertThat(toolNames).containsExactlyInAnyOrder(
                "searchProduct", "confirmPayment", "queryOrder", "slowTask");
    }
}
