package com.shopmind.workflow.pipeline;

import com.shopmind.knowledge.model.KnowledgeChunk;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.workflow.domain.Policy;
import com.shopmind.workflow.domain.PolicyLevel;
import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowRendererImpl 单元测试 — Phase C 假设 H4 验证。
 *
 * <p>验证渲染器是纯函数（相同输入 = 相同输出）且正确渲染所有 section。</p>
 */
@DisplayName("WorkflowRendererImpl — WorkflowInstance → System Prompt 渲染")
class WorkflowRendererImplTest {

    private WorkflowRendererImpl renderer;
    private WorkflowDefinition baseDefinition;

    @BeforeEach
    void setUp() {
        renderer = new WorkflowRendererImpl();

        baseDefinition = new WorkflowDefinition(
                "test-wf",
                "v1.0",
                "【角色】你是测试助手。\n【规则】回答简洁。",
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    // ============================================================
    //  Persona 渲染
    // ============================================================

    @Test
    @DisplayName("仅 persona → 渲染结果 = persona")
    void shouldRenderPersonaOnly() {
        WorkflowInstance instance = new WorkflowInstance(
                baseDefinition, Collections.emptyList(), null, "你好"
        );

        String prompt = renderer.render(instance);

        assertTrue(prompt.contains("【角色】你是测试助手"));
        assertTrue(prompt.contains("【规则】回答简洁"));
        // 不含用户问题（那是 UserMessage 的职责）
        assertFalse(prompt.contains("【用户问题】"));
    }

    // ============================================================
    //  Constraints 渲染
    // ============================================================

    @Nested
    @DisplayName("安全约束渲染")
    class ConstraintsRendering {

        @Test
        @DisplayName("无 constraints → 不输出【安全约束】section")
        void shouldNotRenderConstraintsWhenEmpty() {
            WorkflowInstance instance = new WorkflowInstance(
                    baseDefinition, Collections.emptyList(), null, "hello"
            );

            String prompt = renderer.render(instance);

            assertFalse(prompt.contains("【安全约束】"));
        }

        @Test
        @DisplayName("HARD + SOFT constraints → 均渲染且标注级别")
        void shouldRenderHardAndSoftConstraints() {
            WorkflowDefinition def = new WorkflowDefinition(
                    "test", "v1", "persona",
                    Collections.emptyList(),
                    List.of(
                            new Policy("no_fake", "禁止编造", PolicyLevel.HARD),
                            new Policy("polite", "语气友好", PolicyLevel.SOFT)
                    )
            );

            WorkflowInstance instance = new WorkflowInstance(
                    def, Collections.emptyList(), null, "q"
            );

            String prompt = renderer.render(instance);

            assertTrue(prompt.contains("【安全约束】"));
            assertTrue(prompt.contains("[HARD]"));
            assertTrue(prompt.contains("[SOFT]"));
            assertTrue(prompt.contains("禁止编造"));
            assertTrue(prompt.contains("语气友好"));
            // 应为编号列表
            assertTrue(prompt.contains("1. [HARD]"));
            assertTrue(prompt.contains("2. [SOFT]"));
        }
    }

    // ============================================================
    //  ToolRules 渲染
    // ============================================================

    @Nested
    @DisplayName("工具规则渲染")
    class ToolRulesRendering {

        @Test
        @DisplayName("无 toolRules → 不输出【可用工具】section")
        void shouldNotRenderToolsWhenEmpty() {
            WorkflowInstance instance = new WorkflowInstance(
                    baseDefinition, Collections.emptyList(), null, "q"
            );

            String prompt = renderer.render(instance);

            assertFalse(prompt.contains("【可用工具】"));
        }

        @Test
        @DisplayName("工具含描述 → 渲染工具名和描述")
        void shouldRenderToolNameAndDescription() {
            WorkflowDefinition def = new WorkflowDefinition(
                    "test", "v1", "p",
                    List.of(new ToolRule("queryOrder", "查询订单", false)),
                    Collections.emptyList()
            );

            WorkflowInstance instance = new WorkflowInstance(
                    def, Collections.emptyList(), null, "q"
            );

            String prompt = renderer.render(instance);

            assertTrue(prompt.contains("【可用工具】"));
            assertTrue(prompt.contains("queryOrder"));
            assertTrue(prompt.contains("查询订单"));
        }

        @Test
        @DisplayName("required=true 工具 → 标注（必须调用）")
        void shouldMarkRequiredTool() {
            WorkflowDefinition def = new WorkflowDefinition(
                    "test", "v1", "p",
                    List.of(new ToolRule("confirmPayment", "确认付款", true)),
                    Collections.emptyList()
            );

            WorkflowInstance instance = new WorkflowInstance(
                    def, Collections.emptyList(), null, "付款"
            );

            String prompt = renderer.render(instance);

            assertTrue(prompt.contains("confirmPayment"));
            assertTrue(prompt.contains("必须调用"));
        }
    }

    // ============================================================
    //  Knowledge 渲染
    // ============================================================

    @Nested
    @DisplayName("参考知识渲染")
    class KnowledgeRendering {

        @Test
        @DisplayName("无 knowledge → 不输出【参考知识】section")
        void shouldNotRenderKnowledgeWhenNull() {
            WorkflowInstance instance = new WorkflowInstance(
                    baseDefinition, Collections.emptyList(), null, "q"
            );

            String prompt = renderer.render(instance);

            assertFalse(prompt.contains("【参考知识】"));
        }

        @Test
        @DisplayName("knowledge 有结果 → 输出【参考知识】section")
        void shouldRenderKnowledgeWhenPresent() {
            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .id("k1")
                    .text("退货政策：7天无理由退货。")
                    .metadata(Map.of("source", "售后.md"))
                    .build();

            RetrievedContext knowledge = RetrievedContext.builder()
                    .chunks(List.of(chunk)).build();

            WorkflowInstance instance = new WorkflowInstance(
                    baseDefinition, Collections.emptyList(), knowledge, "怎么退货"
            );

            String prompt = renderer.render(instance);

            assertTrue(prompt.contains("【参考知识（从知识库召回）】"));
            assertTrue(prompt.contains("退货政策"));
        }
    }

    // ============================================================
    //  渲染顺序
    // ============================================================

    @Test
    @DisplayName("渲染顺序: persona → constraints → tools → knowledge")
    void shouldRenderInCorrectOrder() {
        WorkflowDefinition def = new WorkflowDefinition(
                "test", "v1", "PERSONA",
                List.of(new ToolRule("t1", "desc", false)),
                List.of(new Policy("c1", "constraint", PolicyLevel.HARD))
        );

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id("k1").text("知识内容").metadata(Map.of()).build();
        RetrievedContext knowledge = RetrievedContext.builder()
                .chunks(List.of(chunk)).build();

        WorkflowInstance instance = new WorkflowInstance(
                def, Collections.emptyList(), knowledge, "q"
        );

        String prompt = renderer.render(instance);

        int personaIdx = prompt.indexOf("PERSONA");
        int constraintIdx = prompt.indexOf("【安全约束】请严格遵守");
        int toolIdx = prompt.indexOf("【可用工具】你可以调用");
        int knowledgeIdx = prompt.indexOf("【参考知识（从知识库召回）】");

        assertTrue(personaIdx < constraintIdx, "persona should be first");
        assertTrue(constraintIdx < toolIdx, "constraints should be before tools");
        assertTrue(toolIdx < knowledgeIdx, "tools should be before knowledge");
    }

    // ============================================================
    //  H4: 纯函数 — 相同输入 = 相同输出
    // ============================================================

    @Test
    @DisplayName("H4: 纯函数 — 相同输入两次渲染结果一致")
    void shouldProduceIdenticalOutputForSameInput() {
        WorkflowDefinition def = new WorkflowDefinition(
                "test", "v1", "persona text",
                List.of(new ToolRule("t1", "desc", false)),
                List.of(new Policy("p1", "c", PolicyLevel.HARD))
        );

        WorkflowInstance instance = new WorkflowInstance(
                def, Collections.emptyList(), null, "hello"
        );

        String result1 = renderer.render(instance);
        String result2 = renderer.render(instance);

        assertEquals(result1, result2, "纯函数: 相同输入应产生相同输出");
    }

    @Test
    @DisplayName("H4: 无副作用 — 多次渲染不修改 WorkflowInstance")
    void shouldNotModifyInstanceOnRender() {
        List<ToolRule> originalRules = List.of(new ToolRule("t1", "desc", false));
        WorkflowDefinition def = new WorkflowDefinition(
                "test", "v1", "p", originalRules, Collections.emptyList()
        );

        WorkflowInstance instance = new WorkflowInstance(
                def, Collections.emptyList(), null, "q"
        );

        renderer.render(instance);
        renderer.render(instance);
        renderer.render(instance);

        // 验证 WorkflowInstance 的 definition 未被修改
        assertEquals(1, instance.definition().toolRules().size());
        assertEquals("t1", instance.definition().toolRules().get(0).toolName());
    }

    // ============================================================
    //  完整渲染
    // ============================================================

    @Test
    @DisplayName("完整 WorkflowInstance（含所有 section）→ 渲染非空且包含所有关键内容")
    void shouldRenderCompleteInstance() {
        WorkflowDefinition def = new WorkflowDefinition(
                "customer-service", "v2.1", "【角色】智能客服\n1. 简洁回答\n2. 禁止编造",
                List.of(
                        new ToolRule("queryOrder", "查询订单状态", false),
                        new ToolRule("refund", "处理退款", false)
                ),
                List.of(
                        new Policy("no_hallucination", "严禁编造", PolicyLevel.HARD),
                        new Policy("polite_tone", "语气礼貌", PolicyLevel.SOFT)
                )
        );

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id("k1").text("退货政策：7天无理由").metadata(Map.of()).build();
        RetrievedContext knowledge = RetrievedContext.builder()
                .chunks(List.of(chunk)).build();

        WorkflowInstance instance = new WorkflowInstance(
                def,
                List.of(new UserMessage("之前的消息")),
                knowledge,
                "我要退款"
        );

        String prompt = renderer.render(instance);

        assertNotNull(prompt);
        assertFalse(prompt.isBlank());
        assertTrue(prompt.contains("【角色】智能客服"));
        assertTrue(prompt.contains("【安全约束】"));
        assertTrue(prompt.contains("【可用工具】"));
        assertTrue(prompt.contains("【参考知识（从知识库召回）】"));
        assertTrue(prompt.contains("queryOrder"));
        assertTrue(prompt.contains("refund"));
        assertTrue(prompt.contains("[HARD]"));
        assertTrue(prompt.contains("[SOFT]"));
    }
}
