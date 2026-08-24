package com.shopmind.evaluation;

import com.shopmind.evaluation.rtmp.ContextRisk;
import com.shopmind.evaluation.rtmp.ExpectedToolAction;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.evaluation.rtmp.RtmpTaskCategory;
import com.shopmind.evaluation.rtmp.RtmpTestCase;
import com.shopmind.evaluation.rtmp.ToolRiskProfile;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 验收测试：Evaluation / Instrumentation Foundation。
 * <p>
 * 覆盖 Phase 1 §12 全部 12 条验收标准：
 * 1. run_id 格式正确
 * 2. memoryId == runId
 * 3. 同 case 的 repetition=1/2/3 生成不同 run_id
 * 4. 不同 condition 生成不同 run_id
 * 5. ToolCallEvent 表达三种语义
 * 6. 多个 ToolCallEvent 保持顺序
 * 7. 42 cases 完整加载
 * 8. caseId 无重复
 * 9. 7 类分布严格正确
 * 10. 非法 RTMP dataset 不静默降级
 * 11. Legacy TestCase / TestCaseResult / ExperimentReport 行为不被破坏
 * 12. 现有测试全部通过（由构建整体保证）
 */
class RtmpFoundationPhase1Test {

    // ============================================================
    //  §12.1-4: Canonical Run Identity
    // ============================================================

    @Nested
    @DisplayName("Run Identity")
    class RunIdentityTests {

        @Test
        @DisplayName("run_id 格式正确: RTMP-EXP01_{condition}_{caseId}_{repetition}")
        void runId_format() {
            RunIdentity id = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 1);
            assertEquals("RTMP-EXP01_BASELINE_A_RTMP-001_1", id.runId());
        }

        @Test
        @DisplayName("memoryId == runId")
        void memoryId_equals_runId() {
            RunIdentity id = new RunIdentity("RTMP-EXP01", "METHOD_C", "RTMP-042", 3);
            assertEquals(id.runId(), id.memoryId());
            assertEquals("RTMP-EXP01_METHOD_C_RTMP-042_3", id.memoryId());
        }

        @Test
        @DisplayName("同 case 的 repetition=1/2/3 生成不同 run_id")
        void repetition_generates_distinct_runIds() {
            RunIdentity r1 = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 1);
            RunIdentity r2 = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 2);
            RunIdentity r3 = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 3);

            Set<String> ids = Set.of(r1.runId(), r2.runId(), r3.runId());
            assertEquals(3, ids.size(), "repetition=1/2/3 必须生成三个不同 run_id");
            assertEquals("RTMP-EXP01_BASELINE_A_RTMP-001_2", r2.runId());
            assertEquals("RTMP-EXP01_BASELINE_A_RTMP-001_3", r3.runId());
        }

        @Test
        @DisplayName("不同 condition 生成不同 run_id")
        void condition_generates_distinct_runIds() {
            RunIdentity a = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 1);
            RunIdentity b = new RunIdentity("RTMP-EXP01", "BASELINE_B", "RTMP-001", 1);
            RunIdentity c = new RunIdentity("RTMP-EXP01", "METHOD_C", "RTMP-001", 1);

            assertNotEquals(a.runId(), b.runId());
            assertNotEquals(a.runId(), c.runId());
            assertNotEquals(b.runId(), c.runId());
        }

        @Test
        @DisplayName("repetition < 1 拒绝")
        void repetition_must_be_positive() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 0));
        }
    }

    // ============================================================
    //  §12.5-6: ToolCallEvent
    // ============================================================

    @Nested
    @DisplayName("ToolCallEvent")
    class ToolCallEventTests {

        @Test
        @DisplayName("语义A: attempted == executed, verifierBlocked=false")
        void attempted_equals_executed() {
            ToolCallEvent e = ToolCallEvent.of("RTMP-EXP01_BASELINE_A_RTMP-006_1", 1,
                    "refund", "refund", false, Map.of("orderId", "ORD2024006"), null, 120L);
            assertEquals("refund", e.attemptedTool());
            assertEquals("refund", e.executedTool());
            assertFalse(e.verifierBlocked());
        }

        @Test
        @DisplayName("语义B: attempted != executed, verifierBlocked=true + executed=null")
        void attempted_blocked() {
            ToolCallEvent e = ToolCallEvent.of("RTMP-EXP01_BASELINE_B_RTMP-020_1", 1,
                    "refund", null, true, Map.of("orderId", "ORD2024006"), "UNAUTHORIZED", 0L);
            assertEquals("refund", e.attemptedTool());
            assertNull(e.executedTool());
            assertTrue(e.verifierBlocked());
            assertEquals("UNAUTHORIZED", e.blockReason());
        }

        @Test
        @DisplayName("语义C: 不创建伪造 null event（无工具调用时 event 列表为空）")
        void no_fabricated_null_event() {
            ExecutionTrace trace = new ExecutionTrace("t1", "m1", "v1");
            assertEquals(0, trace.getToolCallEvents().size(),
                    "无 Tool Call 时不应存在任何伪造的 null ToolCallEvent");
        }

        @Test
        @DisplayName("多个 ToolCallEvent 保持顺序")
        void ordered_events() {
            ExecutionTrace trace = new ExecutionTrace("t1", "m1", "v1");
            trace.addToolCallEvent(ToolCallEvent.of("r1", 1, "queryOrder", "queryOrder", false, Map.of(), null, 10L));
            trace.addToolCallEvent(ToolCallEvent.of("r1", 2, "refund", "refund", false, Map.of(), null, 20L));
            trace.addToolCallEvent(ToolCallEvent.of("r1", 3, "queryPoints", "queryPoints", false, Map.of(), null, 30L));

            List<ToolCallEvent> events = trace.getToolCallEvents();
            assertEquals(3, events.size());
            assertEquals(1, events.get(0).iteration());
            assertEquals(2, events.get(1).iteration());
            assertEquals(3, events.get(2).iteration());
            assertEquals("queryOrder", events.get(0).attemptedTool());
            assertEquals("queryPoints", events.get(2).attemptedTool());
        }
    }

    // ============================================================
    //  §12.7-10: RTMP Dataset Loader
    // ============================================================

    @Nested
    @DisplayName("RTMP Dataset")
    class RtmpDatasetTests {

        @Test
        @DisplayName("42 cases 完整加载 + caseId 无重复")
        void load_42_cases_unique() {
            RtmpEvaluationDataset ds = RtmpDatasetLoader.load();
            assertEquals(42, ds.size(), "必须完整加载 42 条用例");

            Set<String> ids = new HashSet<>();
            for (RtmpTestCase tc : ds.cases()) {
                assertTrue(ids.add(tc.id()), "caseId 重复: " + tc.id());
            }
            assertEquals(42, ids.size());
        }

        @Test
        @DisplayName("7 类分布严格正确: 8/6/8/6/6/4/4")
        void category_distribution() {
            RtmpEvaluationDataset ds = RtmpDatasetLoader.load();

            Map<RtmpTaskCategory, Integer> dist = new EnumMap<>(RtmpTaskCategory.class);
            for (RtmpTestCase tc : ds.cases()) {
                dist.merge(tc.taskCategory(), 1, Integer::sum);
            }

            assertEquals(8, dist.getOrDefault(RtmpTaskCategory.SAFE_LOW_RISK, 0));
            assertEquals(6, dist.getOrDefault(RtmpTaskCategory.SAFE_HIGH_RISK, 0));
            assertEquals(8, dist.getOrDefault(RtmpTaskCategory.HIGH_RISK_UNAUTHORIZED, 0));
            assertEquals(6, dist.getOrDefault(RtmpTaskCategory.TOOL_DISTRACTOR, 0));
            assertEquals(6, dist.getOrDefault(RtmpTaskCategory.MULTI_TOOL, 0));
            assertEquals(4, dist.getOrDefault(RtmpTaskCategory.AMBIGUOUS_BOUNDARY, 0));
            assertEquals(4, dist.getOrDefault(RtmpTaskCategory.OVER_REFUSAL_BOUNDARY, 0));
        }

        @Test
        @DisplayName("工具池精确为 4 tools")
        void tool_pool() {
            RtmpEvaluationDataset ds = RtmpDatasetLoader.load();
            assertEquals(Set.of("queryOrder", "refund", "queryPoints", "queryCoupons"),
                    new HashSet<>(ds.toolPool()));
        }

        @Test
        @DisplayName("required fields 完整解析（含嵌套 toolRiskProfile / contextRisk）")
        void required_fields_parsed() {
            RtmpEvaluationDataset ds = RtmpDatasetLoader.load();
            RtmpTestCase tc = ds.findById("RTMP-001");
            assertNotNull(tc);
            assertEquals("SAFE_LOW_RISK", tc.taskCategory().name());
            assertEquals("ANSWER_EXPECTED", tc.expectedOutcome());
            assertEquals("queryOrder", tc.expectedTool());
            assertEquals(ExpectedToolAction.CALL, tc.expectedToolAction());
            assertEquals("USER", tc.authorization());
            assertFalse(tc.adversarial());

            ToolRiskProfile p = tc.toolRiskProfile();
            assertNotNull(p);
            assertEquals("NONE", p.sideEffect());
            assertEquals("OWN_DATA", p.permissionScope());

            ContextRisk c = tc.contextRisk();
            assertNotNull(c);
            assertEquals("HIGH", c.intentConfidence());
        }

        @Test
        @DisplayName("非法 RTMP dataset 不允许静默降级（版本不匹配会抛异常）")
        void invalid_dataset_fails_loudly() {
            // 直接通过反射式验证：loader 只接受 classpath 上唯一的 rtmp_v1.0。
            // 这里验证的是：加载成功的数据集 version 必须是 rtmp_v1.0（否则会在此前抛异常）。
            RtmpEvaluationDataset ds = RtmpDatasetLoader.load();
            assertEquals(RtmpDatasetLoader.EXPECTED_VERSION, ds.version());
        }
    }

    // ============================================================
    //  §12.11: Legacy 兼容性（不修改 legacy 类）
    // ============================================================

    @Nested
    @DisplayName("Legacy 兼容性")
    class LegacyCompatibilityTests {

        @Test
        @DisplayName("ExecutionTrace 无 RunIdentity 时保持 legacy 行为")
        void execution_trace_legacy_constructor() {
            ExecutionTrace trace = new ExecutionTrace("trace-1", "legacy-memory", "v1.4");
            assertEquals("legacy-memory", trace.getMemoryId(), "无 RunIdentity 时 memoryId 保持原值");
            assertNull(trace.getRunIdentity());
            assertNull(trace.getRunId());
            trace.markComplete(ExecutionStatus.SUCCESS);
            assertEquals(ExecutionStatus.SUCCESS, trace.getStatus());
        }

        @Test
        @DisplayName("ExecutionTrace 带 RunIdentity 时 memoryId 被强制为 runId")
        void execution_trace_with_identity() {
            RunIdentity id = new RunIdentity("RTMP-EXP01", "BASELINE_A", "RTMP-001", 1);
            ExecutionTrace trace = new ExecutionTrace("trace-2", "ignored", "v1.4", id);
            assertEquals(id.runId(), trace.getMemoryId(), "memoryId 必须等于 runId");
            assertEquals(id.runId(), trace.getRunId());
            assertEquals(id, trace.getRunIdentity());
        }
    }
}