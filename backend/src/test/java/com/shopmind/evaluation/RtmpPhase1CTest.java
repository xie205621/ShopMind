package com.shopmind.evaluation;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.InMemoryTraceRecorder;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpRunOutcome;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.RunStatusClassifier;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.exception.LlmProviderTimeoutException;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1-C 验收测试：Run Outcome Classification / Raw Persistence / Evaluation Plumbing。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>正常 run → {@link RunStatus#VALID}</li>
 *   <li>transient failure → {@link RunStatus#RETRYABLE_FAILURE}</li>
 *   <li>unrecoverable corruption → {@link RunStatus#INVALID_RUN}</li>
 *   <li>persisted Raw Trace 保留 ToolCallEvents</li>
 *   <li>run metadata 持久化完整</li>
 *   <li>Raw 不依赖 Summary 即可解释工具调用事实</li>
 * </ol>
 * <p>
 * 第 7 项（Legacy Evaluation 测试继续全部通过）通过运行全量 {@code mvn test} 验证，
 * 不在本类内单独断言。
 * <p>
 * 本阶段<b>禁止</b>任何 Verifier / pruning / 策略切换 / safety metric / statistical testing。
 */
@SpringBootTest
class RtmpPhase1CTest {

    @Autowired
    private BenchmarkRunner runner;

    @Autowired
    private InMemoryTraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryStore memoryStore;

    @Autowired
    private RunStatusClassifier classifier;

    /** 测试涉及的 runId（memoryId == runId），每次测试前清空避免 Mongo 记忆串扰。 */
    private static final List<String> TEST_RUN_IDS = List.of(
            "RTMP-EXP01_BASELINE_A_RTMP-009_1",
            "RTMP-EXP01_BASELINE_A_RTMP-009_2",
            "RTMP-EXP01_BASELINE_A_RTMP-009_3");

    @BeforeEach
    void clearMemory() {
        for (String runId : TEST_RUN_IDS) {
            memoryStore.deleteMessages(runId);
        }
        // 避免跨测试残留的已落盘 Trace 干扰断言（以 runId 维度读取）
        traceRecorder.clear();
    }

    /** 用于驱动 Mock LLM 触发 refund 工具调用的用例（query 含 "退款"）。 */
    private RtmpTestCase refundCase() {
        RtmpTestCase tc = RtmpDatasetLoader.load().findById("RTMP-009");
        assertNotNull(tc);
        assertEquals("refund", tc.expectedTool());
        return tc;
    }

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

    /** 构造带 canonical RunIdentity 的 Trace（供 classifier 单元断言使用）。 */
    private ExecutionTrace traceWithIdentity() {
        RunIdentity ri = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-009", 1);
        return traceRecorder.createTrace(ri.memoryId(), "v2.3", ri).getExecutionTrace();
    }

    /** 构造无 RunIdentity 的 Trace（模拟缺失强制 run metadata）。 */
    private ExecutionTrace traceWithoutIdentity() {
        return traceRecorder.createTrace("legacy_memory", "v2.3").getExecutionTrace();
    }

    // ============================================================
    //  1. 正常 run → VALID
    // ============================================================

    @Test
    @DisplayName("正常 run → VALID（真实 BenchmarkRunner → Orchestrator 路径）")
    void normalRunIsValid() {
        RtmpRunOutcome outcome = runner.runRtmpCaseOutcome(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        assertNotNull(outcome);
        assertNotNull(outcome.trace());
        assertEquals(RunStatus.VALID, outcome.status(), "正常完成的 run 必须分类为 VALID");
    }

    // ============================================================
    //  2. transient failure → RETRYABLE_FAILURE
    // ============================================================

    @Test
    @DisplayName("transient failure → RETRYABLE_FAILURE（timeout / 429 / transient MCP）")
    void transientFailureIsRetryable() {
        ExecutionTrace trace = traceWithIdentity();

        assertEquals(RunStatus.RETRYABLE_FAILURE,
                classifier.classify(trace, new TimeoutException("LLM timed out")));
        assertEquals(RunStatus.RETRYABLE_FAILURE,
                classifier.classify(trace, new LlmProviderTimeoutException("provider timeout")));
        assertEquals(RunStatus.RETRYABLE_FAILURE,
                classifier.classify(trace, new RuntimeException("rate limited (429)")));
    }

    // ============================================================
    //  3. unrecoverable corruption → INVALID_RUN
    // ============================================================

    @Test
    @DisplayName("unrecoverable corruption → INVALID_RUN（缺失 metadata / schema corruption / duplicate identity）")
    void unrecoverableCorruptionIsInvalidRun() {
        // 缺失强制 run metadata
        assertEquals(RunStatus.INVALID_RUN,
                classifier.classify(traceWithoutIdentity(), null));

        ExecutionTrace trace = traceWithIdentity();
        // 不可恢复的 instrumentation / schema corruption
        assertEquals(RunStatus.INVALID_RUN,
                classifier.classify(trace, new IllegalStateException("dataset schema corruption")));
        // duplicate run identity
        assertEquals(RunStatus.INVALID_RUN,
                classifier.classify(trace, new IllegalStateException("duplicate run identity")));
    }

    // ============================================================
    //  4. persisted Raw Trace 保留 ToolCallEvents
    // ============================================================

    @Test
    @DisplayName("persisted Raw Trace 保留 runtime 追加的 ToolCallEvents")
    void persistedRawTraceKeepsToolCallEvents() {
        RtmpRunOutcome outcome = runner.runRtmpCaseOutcome(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        ExecutionTrace saved = traceRecorder.getTrace(outcome.trace().getTraceId());
        assertNotNull(saved, "run 结束后 Raw Trace 应已落盘");
        assertSame(outcome.trace(), saved, "runtime mutation 与 save 必须围绕同一 canonical ExecutionTrace");

        assertFalse(saved.getToolCallEvents().isEmpty(), "落盘 Raw Trace 必须包含 ToolCallEvent");
        ToolCallEvent event = saved.getToolCallEvents().get(0);
        assertEquals("refund", event.attemptedTool());
        assertEquals("refund", event.executedTool());
        assertFalse(event.verifierBlocked());
        assertEquals(1, event.iteration());
    }

    // ============================================================
    //  5. run metadata 持久化完整
    // ============================================================

    @Test
    @DisplayName("run metadata 持久化完整（runId/memoryId/traceId/caseId/condition/repetition/spans/metrics）")
    void runMetadataPersistedCompletely() {
        RtmpRunOutcome outcome = runner.runRtmpCaseOutcome(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();
        ExecutionTrace saved = traceRecorder.getTrace(outcome.trace().getTraceId());
        assertNotNull(saved);

        // 身份三要素
        assertNotNull(saved.getRunId());
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-009_1", saved.getRunId());
        assertEquals(saved.getRunId(), saved.getMemoryId(), "memoryId 必须等于 runId");
        assertNotNull(saved.getTraceId());

        // caseId / condition / repetition metadata
        RunIdentity ri = saved.getRunIdentity();
        assertNotNull(ri, "run identity 必须随 Raw Trace 持久化");
        assertEquals("RTMP-EXP01", ri.experimentId());
        assertEquals("BASELINE_A", ri.condition());
        assertEquals("RTMP-009", ri.caseId());
        assertEquals(1, ri.repetition());

        // performance metrics + spans
        assertNotNull(saved.getMetrics());
        assertFalse(saved.getSpans().isEmpty(), "Raw Trace 必须保留 existing spans");
    }

    // ============================================================
    //  6. Raw 不依赖 Summary 才能解释工具调用事实
    // ============================================================

    @Test
    @DisplayName("Raw 不依赖 Summary 即可解释工具调用事实")
    void rawIndependentlyInterpretsToolCallFacts() {
        RtmpRunOutcome outcome = runner.runRtmpCaseOutcome(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        // 仅从 Raw Trace 读取，不构造任何 Summary / 聚合对象
        ExecutionTrace raw = traceRecorder.getTrace(outcome.trace().getTraceId());
        List<ToolCallEvent> events = raw.getToolCallEvents();
        assertFalse(events.isEmpty());

        // 工具调用事实自包含：attemptedTool / executedTool / verifierBlocked 直接可读
        boolean refundAttempted = events.stream()
                .anyMatch(e -> "refund".equals(e.attemptedTool()));
        boolean noVerifierBlock = events.stream()
                .noneMatch(ToolCallEvent::verifierBlocked);
        boolean executedMatchesAttempted = events.stream()
                .allMatch(e -> e.executedTool() == null || e.executedTool().equals(e.attemptedTool()));

        assertTrue(refundAttempted, "Raw 必须直接暴露 attemptedTool=refund");
        assertTrue(noVerifierBlock, "Phase 1 无 Verifier，Raw 中 verifierBlocked 必须全部为 false");
        assertTrue(executedMatchesAttempted, "Raw 中 executedTool 必须与 attemptedTool 自洽");
    }
}
