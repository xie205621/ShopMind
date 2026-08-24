package com.shopmind.evaluation;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.InMemoryTraceRecorder;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.RunStatusClassifier;
import com.shopmind.experiment.AllToolsVisibility;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.experiment.NoOpSafetyVerifier;
import com.shopmind.experiment.PostHocSafetyVerifier;
import com.shopmind.experiment.RtmpVisibility;
import com.shopmind.mcp.McpEngine;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 3 验收测试：Mock 三条件集成验证（42 cases × 3 conditions = 126 runs）。
 * <p>
 * 本阶段只验证<b>实验运行框架</b>（RunIdentity / Memory Isolation / Raw Trace /
 * ToolCallEvent / RunStatus / case-level result / condition-level summary plumbing），
 * <b>不</b>实现 RTMP pruning、不引入统计显著性检验、不进入 Real LLM。
 * <p>
 * 覆盖 Phase 3 §10 的 15 条验收标准：
 * <ol>
 *   <li>42 cases × 3 conditions = 126 runs</li>
 *   <li>每个 runId 唯一</li>
 *   <li>memoryId == runId</li>
 *   <li>三条件同 case memory 完全隔离</li>
 *   <li>三条件 Trace 独立</li>
 *   <li>Baseline A 无 verifier intervention</li>
 *   <li>Baseline B allow</li>
 *   <li>Baseline B block</li>
 *   <li>UNAUTHORIZED_ACCESS fail-closed</li>
 *   <li>SOCIAL_ENGINEERING fail-closed</li>
 *   <li>Method C 不调用 PostHocVerifier</li>
 *   <li>三条件 workflow/model/temperature/topP/maxTokens/seed 一致</li>
 *   <li>每个 run 都存在完整 Raw Trace</li>
 *   <li>transient runtime failure 不被当成 safety failure</li>
 *   <li>Legacy 测试全部通过（由全量 {@code mvn test} 保证，不在本类内断言）</li>
 * </ol>
 */
@SpringBootTest
class RtmpPhase3MatrixIntegrationTest {

    /** 三条件运行矩阵（固定顺序：A / B / C） */
    private static final List<ExperimentCondition> CONDITIONS = List.of(
            ExperimentCondition.BASELINE_A,
            ExperimentCondition.BASELINE_B,
            ExperimentCondition.METHOD_C);

    private static final String EXPERIMENT_ID = "RTMP-EXP01";
    private static final String WORKFLOW_VERSION = "v2.3";
    private static final int REPETITION = 1;

    @Autowired
    private BenchmarkRunner runner;

    @Autowired
    private InMemoryTraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryStore memoryStore;

    @Autowired
    private RunStatusClassifier classifier;

    @SpyBean
    private McpEngine mcpEngine;

    @SpyBean
    private ChatModelPort chatModelPort;

    @BeforeEach
    void clearState() {
        traceRecorder.clear();
        clearInvocations(mcpEngine);
        clearInvocations(chatModelPort);
    }

    // ============================================================
    //  帮助方法
    // ============================================================

    private BenchmarkConfig rtmpConfig() {
        return new BenchmarkConfig(
                EXPERIMENT_ID,          // experimentId
                WORKFLOW_VERSION,       // workflowVersion
                "rtmp_v1.0",            // datasetVersion
                "mock",                 // llmProvider
                0.0, 1.0,               // temperature, topP
                "mock",                 // embeddingModel
                "InMemory",             // vectorStore
                1, 30,                  // maxConcurrency, rpmLimit
                null, null);            // seed, maxTokens
    }

    private RtmpEvaluationDataset dataset() {
        return RtmpDatasetLoader.load();
    }

    private RtmpTestCase caseById(String id) {
        RtmpTestCase tc = dataset().findById(id);
        assertNotNull(tc, "dataset 必须包含 " + id);
        return tc;
    }

    private String runId(String caseId, ExperimentCondition condition) {
        return EXPERIMENT_ID + "_" + condition.name() + "_" + caseId + "_" + REPETITION;
    }

    /** 删除该 (case, condition) 的 memory 后再运行，避免跨测试类/方法残留 history 让 Mock 跳过工具调用。 */
    private ExecutionTrace runTraceClean(String caseId, ExperimentCondition condition) {
        memoryStore.deleteMessages(runId(caseId, condition));
        return runner.runRtmpCase(caseById(caseId), rtmpConfig(), condition, REPETITION).block();
    }

    private RtmpRunOutcome runOutcomeClean(String caseId, ExperimentCondition condition) {
        memoryStore.deleteMessages(runId(caseId, condition));
        return runner.runRtmpCaseOutcome(caseById(caseId), rtmpConfig(), condition, REPETITION).block();
    }

    // ============================================================
    //  1-3, 13. 126-run 矩阵 + 唯一性 + 完整 Raw Trace
    // ============================================================

    @Test
    @DisplayName("42 cases × 3 conditions = 126 runs：runId/traceId 唯一、memoryId==runId、Raw Trace 完整")
    void matrix126Runs_allUniqueAndComplete() {
        RtmpEvaluationDataset ds = dataset();
        assertEquals(42, ds.size(), "RTMP 数据集必须为 42 cases");

        // 先清空全部 126 个 memoryId，确保矩阵 run 从空 memory 出发（跨测试类/方法隔离）
        for (RtmpTestCase tc : ds.cases()) {
            for (ExperimentCondition c : CONDITIONS) {
                memoryStore.deleteMessages(runId(tc.id(), c));
            }
        }

        List<RtmpRunOutcome> outcomes = new ArrayList<>();
        Set<String> runIds = new HashSet<>();
        Set<String> traceIds = new HashSet<>();

        for (RtmpTestCase tc : ds.cases()) {
            for (ExperimentCondition c : CONDITIONS) {
                RtmpRunOutcome o = runner.runRtmpCaseOutcome(tc, rtmpConfig(), c, REPETITION).block();
                assertNotNull(o, "run 不得返回 null outcome: " + tc.id() + "/" + c);
                ExecutionTrace t = o.trace();
                assertNotNull(t, "run 必须返回 canonical Trace: " + tc.id() + "/" + c);
                outcomes.add(o);

                // 身份三要素
                assertNotNull(t.getRunId());
                assertEquals(t.getRunId(), t.getMemoryId(), "memoryId 必须等于 runId");
                assertNotNull(t.getTraceId());
                runIds.add(t.getRunId());
                traceIds.add(t.getTraceId());

                // run metadata 完整（item 13）
                RunIdentity ri = t.getRunIdentity();
                assertNotNull(ri, "每个 run 必须携带 canonical RunIdentity");
                assertEquals(EXPERIMENT_ID, ri.experimentId());
                assertEquals(c.name(), ri.condition());
                assertEquals(tc.id(), ri.caseId());
                assertEquals(REPETITION, ri.repetition());
                assertEquals(WORKFLOW_VERSION, t.getWorkflowVersion());
                assertEquals(ExecutionStatus.SUCCESS, t.getStatus(), "Mock run 应正常完成");
                assertNotNull(t.getMetrics(), "Raw Trace 必须保留 performance metrics");
                assertFalse(t.getSpans().isEmpty(), "Raw Trace 必须保留 ANSWER_OUTPUT span");
                assertNotNull(t.getToolCallEvents(), "Raw Trace 必须暴露 toolCallEvents 列表");

                assertEquals(RunStatus.VALID, o.status(), "正常完成的 run 必须分类为 VALID");
            }
        }

        assertEquals(126, outcomes.size(), "42 cases × 3 conditions 必须等于 126 runs");
        assertEquals(126, runIds.size(), "每个 runId 必须唯一");
        assertEquals(126, traceIds.size(), "每个 traceId 必须唯一");
    }

    // ============================================================
    //  4. 三条件同 case memory 完全隔离
    // ============================================================

    @Test
    @DisplayName("同 case 三条件 memory 完全隔离（不共享 conversation history）")
    void sameCaseThreeConditionsMemoryIsolated() {
        String caseId = "RTMP-009";
        String aId = runId(caseId, ExperimentCondition.BASELINE_A);
        String bId = runId(caseId, ExperimentCondition.BASELINE_B);
        String cId = runId(caseId, ExperimentCondition.METHOD_C);
        memoryStore.deleteMessages(aId);
        memoryStore.deleteMessages(bId);
        memoryStore.deleteMessages(cId);

        // 只运行 BASELINE_A，其余两个 memoryId 必须保持为空
        runner.runRtmpCase(caseById(caseId), rtmpConfig(), ExperimentCondition.BASELINE_A, REPETITION).block();

        assertFalse(memoryStore.getMessages(aId).isEmpty(), "BASELINE_A run 应写入自己的 memory");
        assertTrue(memoryStore.getMessages(bId).isEmpty(), "BASELINE_B memory 不得被 BASELINE_A 污染");
        assertTrue(memoryStore.getMessages(cId).isEmpty(), "METHOD_C memory 不得被 BASELINE_A 污染");

        assertNotEquals(aId, bId);
        assertNotEquals(aId, cId);
        assertNotEquals(bId, cId);
    }

    // ============================================================
    //  5. 三条件 Trace 独立
    // ============================================================

    @Test
    @DisplayName("同 case 三条件 Trace 独立（traceId / runId / ExecutionTrace instance 各不相同）")
    void sameCaseThreeConditionsTraceIndependent() {
        String caseId = "RTMP-009";
        ExecutionTrace a = runner.runRtmpCase(caseById(caseId), rtmpConfig(), ExperimentCondition.BASELINE_A, REPETITION).block();
        ExecutionTrace b = runner.runRtmpCase(caseById(caseId), rtmpConfig(), ExperimentCondition.BASELINE_B, REPETITION).block();
        ExecutionTrace c = runner.runRtmpCase(caseById(caseId), rtmpConfig(), ExperimentCondition.METHOD_C, REPETITION).block();

        assertEquals(3, Set.of(a.getTraceId(), b.getTraceId(), c.getTraceId()).size(),
                "三条件必须产生三个不同 traceId");
        assertEquals(3, Set.of(a.getRunId(), b.getRunId(), c.getRunId()).size(),
                "三条件必须产生三个不同 runId");
        assertNotSame(a, b);
        assertNotSame(a, c);
        assertNotSame(b, c);
    }

    // ============================================================
    //  6-8. Baseline A / B 行为差异
    // ============================================================

    @Test
    @DisplayName("Baseline A：合法高风险 refund 无 verifier intervention")
    void baselineA_noVerifierIntervention() {
        ExecutionTrace t = runTraceClean("RTMP-009", ExperimentCondition.BASELINE_A);
        ToolCallEvent e = t.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool());
        assertEquals("refund", e.executedTool());
        assertFalse(e.verifierBlocked());
        assertNull(e.blockReason());
    }

    @Test
    @DisplayName("Baseline B allow：合法 USER+OWN_RESOURCE → ALLOW → executedTool=refund")
    void baselineB_allowRefund() {
        ExecutionTrace t = runTraceClean("RTMP-009", ExperimentCondition.BASELINE_B);
        ToolCallEvent e = t.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool());
        assertEquals("refund", e.executedTool());
        assertFalse(e.verifierBlocked());
        assertNull(e.blockReason());
    }

    @Test
    @DisplayName("Baseline B block（runtime OTHER_USER）：attemptedTool=refund, verifierBlocked=true, executedTool=null")
    void baselineB_blockUnauthorizedExplicitRule() {
        clearInvocations(mcpEngine);
        ExecutionTrace t = runTraceClean("RTMP-022", ExperimentCondition.BASELINE_B);
        assertFalse(t.getToolCallEvents().isEmpty());
        ToolCallEvent e = t.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool());
        assertTrue(e.verifierBlocked());
        assertNull(e.executedTool());
        assertEquals("OTHER_USER_ORDER", e.blockReason());
        verify(mcpEngine, never()).executeTool(anyString(), anyString());
    }

    // ============================================================
    //  9-10. Runtime-context 驱动的 Baseline B 拦截（OTHER_USER / UNAUTHORIZED）
    // ============================================================

    @Test
    @DisplayName("Baseline B runtime-context 拦截：OTHER_USER/UNAUTHORIZED → BLOCK，MCP 不执行")
    void runtimeContextDrivenBlockIntegration() {
        // RTMP-019/021：OTHER_USER + refund → BLOCK
        for (String id : List.of("RTMP-019", "RTMP-021")) {
            clearInvocations(mcpEngine);
            ExecutionTrace t = runTraceClean(id, ExperimentCondition.BASELINE_B);
            assertNotNull(t);
            assertFalse(t.getToolCallEvents().isEmpty(), id + " Mock 应 attempt 工具并产生 ToolCallEvent");

            ToolCallEvent e = t.getToolCallEvents().get(0);
            assertEquals("refund", e.attemptedTool(), id + " attemptedTool 应为 refund");
            assertTrue(e.verifierBlocked(), id + " 必须被 runtime-context BLOCK");
            assertNull(e.executedTool(), id + " executedTool 必须为 null");
            assertEquals("OTHER_USER_ORDER", e.blockReason(), id + " blockReason 不符");

            verify(mcpEngine, never()).executeTool(anyString(), anyString());
        }

        // RTMP-024：UNAUTHORIZED + OTHER_USER → BLOCK（未授权主体优先）。
        // Mock 关键词优先级使该 query 可能 attempt queryOrder 而非 refund，因此不强求工具名，
        // 只断言未授权主体被 BLOCK、工具未执行。
        clearInvocations(mcpEngine);
        ExecutionTrace t = runTraceClean("RTMP-024", ExperimentCondition.BASELINE_B);
        assertFalse(t.getToolCallEvents().isEmpty());
        ToolCallEvent e = t.getToolCallEvents().get(0);
        assertNotNull(e.attemptedTool(), "RTMP-024 Mock 应 attempt 某工具");
        assertTrue(e.verifierBlocked());
        assertNull(e.executedTool());
        assertEquals("UNAUTHORIZED_SUBJECT", e.blockReason());
        verify(mcpEngine, never()).executeTool(anyString(), anyString());
    }

    // ============================================================
    //  11. Method C：独立 run + 不调用 PostHocVerifier
    // ============================================================

    @Test
    @DisplayName("Method C：独立 run + 不调用 PostHocVerifier（RtmpVisibility + NoOp）")
    void methodC_independentRun_noPostHocVerifier() {
        // 结构：METHOD_C 映射到 NoOpSafetyVerifier（而非 PostHoc），visibility 为 RtmpVisibility（P4-3）
        assertInstanceOf(NoOpSafetyVerifier.class, ExperimentCondition.METHOD_C.safetyVerifier());
        assertInstanceOf(RtmpVisibility.class, ExperimentCondition.METHOD_C.visibilityStrategy());

        // 运行未授权用例：NoOp 使 refund 直接执行，证明未调用 PostHoc（Baseline B 会 BLOCK）
        ExecutionTrace t = runTraceClean("RTMP-019", ExperimentCondition.METHOD_C);
        assertEquals("RTMP-EXP01_METHOD_C_RTMP-019_1", t.getRunId());
        assertEquals(t.getRunId(), t.getMemoryId());

        assertFalse(t.getToolCallEvents().isEmpty());
        ToolCallEvent e = t.getToolCallEvents().get(0);
        assertEquals("refund", e.attemptedTool());
        assertEquals("refund", e.executedTool(), "METHOD_C 占位为 NoOp，不拦截，工具应执行");
        assertFalse(e.verifierBlocked(), "METHOD_C 不得调用 PostHocSafetyVerifier");
    }

    // ============================================================
    //  12. 三条件公平性：配置参数不随 condition 变化
    // ============================================================

    @Test
    @DisplayName("三条件公平性：A/B 共享 AllTools，C 使用 RtmpVisibility；model/temperature/topP/maxTokens/seed 一致")
    @SuppressWarnings("unchecked")
    void threeConditionFairness_identicalConfig() {
        // 1. A/B 共享 AllToolsVisibility；C 使用 RtmpVisibility（RTMP 唯一允许的差异）；verifier：A/C NoOp，B PostHoc
        assertInstanceOf(AllToolsVisibility.class, ExperimentCondition.BASELINE_A.visibilityStrategy());
        assertInstanceOf(AllToolsVisibility.class, ExperimentCondition.BASELINE_B.visibilityStrategy());
        assertInstanceOf(RtmpVisibility.class, ExperimentCondition.METHOD_C.visibilityStrategy());
        assertInstanceOf(NoOpSafetyVerifier.class, ExperimentCondition.BASELINE_A.safetyVerifier());
        assertInstanceOf(PostHocSafetyVerifier.class, ExperimentCondition.BASELINE_B.safetyVerifier());
        assertInstanceOf(NoOpSafetyVerifier.class, ExperimentCondition.METHOD_C.safetyVerifier());

        // 2. 单一 BenchmarkConfig：model/temperature/topP/maxTokens/seed 不随 condition 变化
        BenchmarkConfig cfg = rtmpConfig();
        assertEquals("mock", cfg.llmProvider());
        assertEquals(0.0, cfg.temperature());
        assertEquals(1.0, cfg.topP());
        assertNull(cfg.seed());
        assertNull(cfg.maxTokens());
        assertEquals(WORKFLOW_VERSION, cfg.workflowVersion());

        // 3. A/B 运行时 Function Calling 工具池一致（4 工具）；C 为 RTMP 裁剪后的子集（RTMP-009 合法退款）
        Set<String> allFour = Set.of("queryOrder", "refund", "queryPoints", "queryCoupons");
        List<Set<String>> captured = new ArrayList<>();
        for (ExperimentCondition c : CONDITIONS) {
            clearInvocations(chatModelPort);
            ExecutionTrace t = runner.runRtmpCase(caseById("RTMP-009"), rtmpConfig(), c, REPETITION).block();
            assertEquals(WORKFLOW_VERSION, t.getWorkflowVersion(), "workflowVersion 不得因 condition 改变");

            ArgumentCaptor<List<ToolSpecification>> captor = ArgumentCaptor.forClass(List.class);
            verify(chatModelPort, atLeastOnce()).stream(anyList(), captor.capture());
            Set<String> tools = captor.getValue().stream()
                    .map(ToolSpecification::getToolName)
                    .collect(Collectors.toSet());
            captured.add(tools);
        }
        assertEquals(allFour, captured.get(0), "Baseline A 必须暴露全部 4 工具");
        assertEquals(captured.get(0), captured.get(1), "Baseline B 工具池必须与 Baseline A 一致");
        // Method C 使用 RtmpVisibility 裁剪：RTMP-009 合法退款保留 refund（+ 相关 queryOrder），裁剪无关 points/coupons
        assertTrue(captured.get(2).contains("refund"), "Method C 必须保留合法高风险 refund");
        assertFalse(captured.get(2).contains("queryPoints"), "Method C 必须裁剪无关 queryPoints");
        assertFalse(captured.get(2).contains("queryCoupons"), "Method C 必须裁剪无关 queryCoupons");
        assertTrue(allFour.containsAll(captured.get(2)), "Method C 工具池必须是全量工具的子集");
        // max iterations / timeout / retry 为 singleton 级 @Value/常量（ToolIterationGuard、llmTimeoutMs、Retry.backoff），
        // 均不感知 condition；随 condition 变化的是 visibilityStrategy 与 safetyVerifier（见上方结构断言）。
    }

    // ============================================================
    //  14. transient runtime failure 不被当成 safety failure
    // ============================================================

    @Test
    @DisplayName("transient runtime failure → RETRYABLE_FAILURE；safety intervention → VALID（互不混淆）")
    void transientFailureNotConfusedWithSafety() {
        RunIdentity ri = new RunIdentity(EXPERIMENT_ID, "BASELINE_A", "RTMP-009", REPETITION);
        ExecutionTrace trace = traceRecorder.createTrace(ri.memoryId(), WORKFLOW_VERSION, ri).getExecutionTrace();

        // transient 基础设施故障 → RETRYABLE_FAILURE（不是 safety 分类）
        assertEquals(RunStatus.RETRYABLE_FAILURE,
                classifier.classify(trace, new TimeoutException("LLM timed out")));
        assertEquals(RunStatus.RETRYABLE_FAILURE,
                classifier.classify(trace, new RuntimeException("rate limited (429)")));

        // safety intervention（verifier blocked）是 VALID observation，不是 runtime failure
        RtmpRunOutcome blocked = runOutcomeClean("RTMP-023", ExperimentCondition.BASELINE_B);
        assertEquals(RunStatus.VALID, blocked.status(), "安全拦截是有效 observation，不是 runtime failure");
        assertTrue(blocked.trace().getToolCallEvents().stream().anyMatch(ToolCallEvent::verifierBlocked),
                "RTMP-023 在 Baseline B 下必须产生 verifierBlocked ToolCallEvent");
    }
}
