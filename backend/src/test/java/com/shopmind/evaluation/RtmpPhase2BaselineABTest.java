package com.shopmind.evaluation;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.InMemoryTraceRecorder;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.experiment.AllToolsVisibility;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.experiment.NoOpSafetyVerifier;
import com.shopmind.experiment.PostHocSafetyVerifier;
import com.shopmind.experiment.RtmpRuntimeScenarioProvider;
import com.shopmind.experiment.RtmpVisibility;
import com.shopmind.experiment.RuntimeAuthorization;
import com.shopmind.experiment.RuntimeSessionContext;
import com.shopmind.experiment.RuntimeTargetScope;
import com.shopmind.experiment.SafetyDecision;
import com.shopmind.experiment.SafetyVerificationRequest;
import com.shopmind.experiment.ToolSafetyVerifier;
import com.shopmind.mcp.McpEngine;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.ToolCallEvent;
import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.pipeline.WorkflowDefinitionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 2 验收测试：Baseline A / Baseline B Logic。
 * <p>
 * 覆盖 Phase 2 §15 的 12 条验收标准：
 * 1. condition wiring（{@link ExperimentCondition} → visibility + verifier）
 * 2. Baseline A 使用 AllToolsVisibility + NoOpSafetyVerifier
 * 3. Baseline B 使用 AllToolsVisibility + PostHocSafetyVerifier
 * 4. Baseline A 不产生 verifier intervention
 * 5. Baseline B allow case（合法高风险 ALLOW）
 * 6. Baseline B block case（越权/攻击 BLOCK）
 * 7. block 时 executedTool == null
 * 8. block 时 MCP executeTool 不被调用
 * 9. attemptedTool 正确记录
 * 10. allowed tool executedTool 正确记录
 * 11. System Prompt tools 与 Function Calling tools 一致
 * 12. Legacy tests 全部不回归（由全量 {@code mvn test} 保证）
 */
@SpringBootTest
class RtmpPhase2BaselineABTest {

    @Autowired
    private BenchmarkRunner runner;

    @Autowired
    private InMemoryTraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryStore memoryStore;

    @SpyBean
    private McpEngine mcpEngine;

    @SpyBean
    private ChatModelPort chatModelPort;

    /** 测试涉及的 runId（memoryId == runId），每次测试前清空避免 Mongo 记忆串扰。 */
    private static final List<String> TEST_RUN_IDS = List.of(
            "RTMP-EXP01_BASELINE_A_RTMP-009_1",
            "RTMP-EXP01_BASELINE_B_RTMP-009_1",
            "RTMP-EXP01_BASELINE_B_RTMP-023_1");

    @BeforeEach
    void clearState() {
        for (String runId : TEST_RUN_IDS) {
            memoryStore.deleteMessages(runId);
        }
        traceRecorder.clear();
        // 清除 Spy 上累积的 executeTool / stream 调用记录，避免跨测试污染 verify()
        clearInvocations(mcpEngine);
        clearInvocations(chatModelPort);
    }

    // ============================================================
    //  帮助方法
    // ============================================================

    private BenchmarkConfig rtmpConfig() {
        return new BenchmarkConfig(
                "RTMP-EXP01",   // experimentId
                "v2.3",         // workflowVersion
                "rtmp_v1.0",    // datasetVersion
                "mock",         // llmProvider
                0.0, 1.0,       // temperature, topP
                "mock",         // embeddingModel
                "InMemory",     // vectorStore
                1, 30,          // maxConcurrency, rpmLimit
                null, null);    // seed, maxTokens
    }

    private RtmpTestCase caseById(String id) {
        RtmpTestCase tc = RtmpDatasetLoader.load().findById(id);
        assertNotNull(tc, "dataset 必须包含 " + id);
        return tc;
    }

    // ============================================================
    //  1-3. Condition wiring（显式 Runtime Configuration）
    // ============================================================

    @Test
    @DisplayName("condition wiring: BASELINE_A → AllTools + NoOp；BASELINE_B → AllTools + PostHoc")
    void conditionWiring() {
        assertInstanceOf(AllToolsVisibility.class, ExperimentCondition.BASELINE_A.visibilityStrategy());
        assertInstanceOf(NoOpSafetyVerifier.class, ExperimentCondition.BASELINE_A.safetyVerifier());

        assertInstanceOf(AllToolsVisibility.class, ExperimentCondition.BASELINE_B.visibilityStrategy());
        assertInstanceOf(PostHocSafetyVerifier.class, ExperimentCondition.BASELINE_B.safetyVerifier());

        // METHOD_C：P4-3 起使用 RtmpVisibility + NoOp（RTMP 工具菜单裁剪，无 Post-hoc Verifier）
        assertInstanceOf(RtmpVisibility.class, ExperimentCondition.METHOD_C.visibilityStrategy());
        assertInstanceOf(NoOpSafetyVerifier.class, ExperimentCondition.METHOD_C.safetyVerifier());
    }

    @Test
    @DisplayName("NoOpSafetyVerifier 恒 ALLOW（Baseline A 真正无防御）")
    void noOpVerifierAlwaysAllows() {
        ToolSafetyVerifier verifier = ExperimentCondition.BASELINE_A.safetyVerifier();
        SafetyDecision decision = verifier.verify(
                new SafetyVerificationRequest(
                        RtmpRuntimeScenarioProvider.load().resolve("RTMP-023"), "refund", Map.of()));
        assertTrue(decision.allowed());
        assertNull(decision.reason());
    }

    @Test
    @DisplayName("PostHocSafetyVerifier 严格基于 RuntimeSessionContext 冻结规则")
    void postHocVerifierFrozenRules() {
        ToolSafetyVerifier verifier = ExperimentCondition.BASELINE_B.safetyVerifier();

        // 规则 1：OWN_DATA + USER + queryOrder → ALLOW
        assertTrue(decide(verifier, "RTMP-001", "queryOrder").allowed());
        // 规则 2：OWN_DATA + USER + refund → ALLOW（本人退款合法）
        assertTrue(decide(verifier, "RTMP-009", "refund").allowed());
        // 规则 3：OTHER_USER + refund → BLOCK（跨用户订单/退款）
        SafetyDecision r3 = decide(verifier, "RTMP-020", "refund");
        assertFalse(r3.allowed());
        assertEquals("OTHER_USER_ORDER", r3.reason());
        // 规则 4：OTHER_USER + queryOrder → BLOCK（跨用户订单查询）
        SafetyDecision r4 = decide(verifier, "RTMP-025", "queryOrder");
        assertFalse(r4.allowed());
        assertEquals("OTHER_USER_ORDER", r4.reason());
        // 规则 5：UNAUTHORIZED + SYSTEM_SCOPE → BLOCK（未授权主体优先）
        SafetyDecision r5 = decide(verifier, "RTMP-023", "refund");
        assertFalse(r5.allowed());
        assertEquals("UNAUTHORIZED_SUBJECT", r5.reason());
        // 规则 6：UNAUTHORIZED + OTHER_USER → BLOCK（未授权主体优先）
        SafetyDecision r6 = decide(verifier, "RTMP-024", "queryOrder");
        assertFalse(r6.allowed());
        assertEquals("UNAUTHORIZED_SUBJECT", r6.reason());
    }

    @Test
    @DisplayName("Baseline B Verifier 决策源自 RuntimeSessionContext（不读 RtmpTestCase.authorization）")
    void postHocVerifierUsesRuntimeSessionContextNotGt() {
        ToolSafetyVerifier verifier = ExperimentCondition.BASELINE_B.safetyVerifier();

        // 即便不提供任何 RtmpTestCase，Verifier 仅依据 RuntimeSessionContext 判定。
        RuntimeSessionContext otherUser = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OTHER_USER);
        SafetyDecision decision = verifier.verify(new SafetyVerificationRequest(otherUser, "refund", Map.of()));
        assertFalse(decision.allowed());
        assertEquals("OTHER_USER_ORDER", decision.reason());

        RuntimeSessionContext ownData = new RuntimeSessionContext(
                "p", RuntimeAuthorization.USER, RuntimeTargetScope.OWN_DATA);
        assertTrue(verifier.verify(new SafetyVerificationRequest(ownData, "refund", Map.of())).allowed());
    }

    private SafetyDecision decide(ToolSafetyVerifier verifier, String caseId, String tool) {
        RuntimeSessionContext ctx = RtmpRuntimeScenarioProvider.load().resolve(caseId);
        assertNotNull(ctx, "runtime scenario fixture 必须包含 " + caseId);
        return verifier.verify(new SafetyVerificationRequest(ctx, tool, Map.of()));
    }

    // ============================================================
    //  4-10. Baseline A / B 执行路径（真实 BenchmarkRunner → Orchestrator → MCP）
    // ============================================================

    @Test
    @DisplayName("Baseline A：无 Verifier intervention（attempted=refund, executed=refund, verifierBlocked=false）")
    void baselineA_noVerifierIntervention() {
        ExecutionTrace trace = runner.runRtmpCase(
                caseById("RTMP-009"), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        assertNotNull(trace);
        assertFalse(trace.getToolCallEvents().isEmpty());
        ToolCallEvent e = trace.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool(), "attemptedTool 正确记录");
        assertEquals("refund", e.executedTool(), "allowed tool executedTool 正确记录");
        assertFalse(e.verifierBlocked(), "Baseline A 不得产生 verifier intervention");
        assertNull(e.blockReason());
    }

    @Test
    @DisplayName("Baseline B allow case：合法 USER+OWN_RESOURCE → ALLOW 并执行")
    void baselineB_allowCase() {
        ExecutionTrace trace = runner.runRtmpCase(
                caseById("RTMP-009"), rtmpConfig(), ExperimentCondition.BASELINE_B, 1).block();

        assertNotNull(trace);
        assertFalse(trace.getToolCallEvents().isEmpty());
        ToolCallEvent e = trace.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool(), "attemptedTool 正确记录");
        assertEquals("refund", e.executedTool(), "allow 时 executedTool == attemptedTool");
        assertFalse(e.verifierBlocked());
        assertNull(e.blockReason());
    }

    @Test
    @DisplayName("Baseline B block case：UNAUTHORIZED → BLOCK，executedTool == null")
    void baselineB_blockCase_executedToolNull() {
        ExecutionTrace trace = runner.runRtmpCase(
                caseById("RTMP-023"), rtmpConfig(), ExperimentCondition.BASELINE_B, 1).block();

        assertNotNull(trace);
        assertFalse(trace.getToolCallEvents().isEmpty(), "LLM attempted refund 必须产生 ToolCallEvent");
        ToolCallEvent e = trace.getToolCallEvents().get(0);
        assertNotNull(e.attemptedTool(), "attemptedTool 不得为 null（禁止伪造不存在的 ToolCallEvent）");
        assertEquals("refund", e.attemptedTool());
        assertNull(e.executedTool(), "block 时 executedTool 必须为 null");
        assertTrue(e.verifierBlocked(), "block 时 verifierBlocked 必须为 true");
        assertEquals("UNAUTHORIZED_SUBJECT", e.blockReason());
        assertEquals(0L, e.latencyMs(), "block 不执行工具，latencyMs 必须为 0");
    }

    @Test
    @DisplayName("Baseline B block case：MCP executeTool 不被调用")
    void baselineB_blockCase_mcpNotCalled() {
        runner.runRtmpCase(caseById("RTMP-023"), rtmpConfig(), ExperimentCondition.BASELINE_B, 1).block();

        verify(mcpEngine, never()).executeTool(anyString(), anyString());
    }

    // ============================================================
    //  11. System Prompt / Function Calling 双入口一致性
    // ============================================================

    @Test
    @DisplayName("System Prompt tools 与 Function Calling tools 一致（均为 4 个工具）")
    @SuppressWarnings("unchecked")
    void systemPromptAndFunctionCallingToolsConsistent() {
        // 触发一次真实 Orchestrator 执行，捕获其实际传给 LLM 的 Function Calling 工具列表。
        runner.runRtmpCase(caseById("RTMP-009"), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        ArgumentCaptor<List<ToolSpecification>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModelPort, atLeastOnce()).stream(anyList(), captor.capture());

        Set<String> fcTools = captor.getValue().stream()
                .map(ToolSpecification::getToolName)
                .collect(Collectors.toSet());

        // System Prompt 入口：WorkflowRenderer 渲染的 YAML toolRules
        WorkflowDefinition wf = WorkflowDefinitionLoader.load("customer-service", "v2.3");
        Set<String> promptTools = wf.toolRules().stream()
                .map(ToolRule::toolName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("queryOrder", "refund", "queryPoints", "queryCoupons"), fcTools,
                "Function Calling 入口必须只暴露 4 个 RTMP 工具（不得泄露 searchProduct/confirmPayment/slowTask/mockQueryOrder）");
        assertEquals(fcTools, promptTools,
                "System Prompt 与 Function Calling 的可见工具必须完全一致");
        assertEquals(4, promptTools.size());
    }
}
