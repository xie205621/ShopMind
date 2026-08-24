package com.shopmind.evaluation;

import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.pipeline.InMemoryTraceRecorder;
import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.ToolCallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1-B 验收测试：Runtime Instrumentation Wiring。
 * <p>
 * 证明<b>真实</b> BenchmarkRunner → ShopAgentOrchestrator → MCP Engine 执行路径
 * 会基于 canonical RunIdentity 生成 runId（memoryId == runId），并围绕唯一 canonical
 * {@link ExecutionTrace} 追加真实 {@link ToolCallEvent}。
 * <p>
 * 本阶段<b>禁止</b>任何 Verifier / pruning / 策略切换 —— 这里只验证 instrumentation。
 */
@SpringBootTest
class RtmpInstrumentationPhase1BTest {

    @Autowired
    private BenchmarkRunner runner;

    @Autowired
    private InMemoryTraceRecorder traceRecorder;

    @Autowired
    private ChatMemoryStore memoryStore;

    /** 测试涉及的 runId（memoryId == runId），每次测试前清空避免 Mongo 记忆串扰。 */
    private static final List<String> TEST_RUN_IDS = List.of(
            "RTMP-EXP01_BASELINE_A_RTMP-009_1",
            "RTMP-EXP01_BASELINE_A_RTMP-009_2",
            "RTMP-EXP01_BASELINE_A_RTMP-009_3",
            "RTMP-EXP01_BASELINE_B_RTMP-009_1",
            "RTMP-EXP01_METHOD_C_RTMP-009_1");

    @BeforeEach
    void clearMemory() {
        for (String runId : TEST_RUN_IDS) {
            memoryStore.deleteMessages(runId);
        }
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

    @Test
    @DisplayName("RunIdentity 进入真实 Benchmark Run: memoryId == runId 且格式 canonical")
    void runIdentityEntersRealBenchmarkRun() {
        ExecutionTrace trace = runner.runRtmpCase(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        assertNotNull(trace);
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-009_1", trace.getRunId());
        assertEquals(trace.getRunId(), trace.getMemoryId(), "memoryId 必须等于 runId");
        assertEquals(trace.getRunId(), trace.getRunIdentity().memoryId());
    }

    @Test
    @DisplayName("同一 case 的 repetition=1/2/3 生成不同 runId")
    void repetitionGeneratesDistinctRunIds() {
        RtmpTestCase tc = refundCase();
        BenchmarkConfig config = rtmpConfig();

        ExecutionTrace r1 = runner.runRtmpCase(tc, config, ExperimentCondition.BASELINE_A, 1).block();
        ExecutionTrace r2 = runner.runRtmpCase(tc, config, ExperimentCondition.BASELINE_A, 2).block();
        ExecutionTrace r3 = runner.runRtmpCase(tc, config, ExperimentCondition.BASELINE_A, 3).block();

        Set<String> ids = Set.of(r1.getRunId(), r2.getRunId(), r3.getRunId());
        assertEquals(3, ids.size(), "repetition=1/2/3 必须生成三个不同 runId");
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-009_2", r2.getRunId());
        assertEquals("RTMP-EXP01_BASELINE_A_RTMP-009_3", r3.getRunId());
    }

    @Test
    @DisplayName("不同 condition 生成不同 runId")
    void conditionGeneratesDistinctRunIds() {
        RtmpTestCase tc = refundCase();
        BenchmarkConfig config = rtmpConfig();

        ExecutionTrace a = runner.runRtmpCase(tc, config, ExperimentCondition.BASELINE_A, 1).block();
        ExecutionTrace b = runner.runRtmpCase(tc, config, ExperimentCondition.BASELINE_B, 1).block();
        ExecutionTrace c = runner.runRtmpCase(tc, config, ExperimentCondition.METHOD_C, 1).block();

        assertNotEquals(a.getRunId(), b.getRunId());
        assertNotEquals(a.getRunId(), c.getRunId());
        assertNotEquals(b.getRunId(), c.getRunId());
    }

    @Test
    @DisplayName("真实 Tool Call 产生 ToolCallEvent: attempted=refund, executed=refund, verifierBlocked=false")
    void realToolCallProducesToolCallEvent() {
        ExecutionTrace trace = runner.runRtmpCase(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        assertNotNull(trace);
        assertFalse(trace.getToolCallEvents().isEmpty(), "真实工具调用必须产生 ToolCallEvent");

        ToolCallEvent event = trace.getToolCallEvents().get(0);
        assertEquals("refund", event.attemptedTool());
        assertEquals("refund", event.executedTool(), "执行成功时 executedTool == attemptedTool");
        assertFalse(event.verifierBlocked(), "Phase 1 无 Verifier，verifierBlocked 必须为 false");
        assertEquals(1, event.iteration(), "首次工具调用 iteration 必须为 1");
        assertEquals(trace.getRunId(), event.runId());
        assertNotNull(event.timestamp());
        assertTrue(event.latencyMs() >= 0);
        assertNull(event.blockReason());
    }

    @Test
    @DisplayName("Canonical Trace: runtime mutation 与 save 使用同一 ExecutionTrace 实例")
    void canonicalTraceInstanceIsShared() {
        ExecutionTrace trace = runner.runRtmpCase(refundCase(), rtmpConfig(), ExperimentCondition.BASELINE_A, 1).block();

        assertNotNull(trace);
        ExecutionTrace saved = traceRecorder.getTrace(trace.getTraceId());
        assertNotNull(saved, "run 结束后 Trace 应已落盘");
        assertSame(trace, saved, "runtime mutation 与 save 必须围绕同一 canonical ExecutionTrace");
        assertFalse(saved.getToolCallEvents().isEmpty(), "落盘的 Trace 必须包含 runtime 追加的 ToolCallEvent");
    }
}
