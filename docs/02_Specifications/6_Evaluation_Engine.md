# Module: Trustworthy Evaluation Engine (可信评估引擎)

**Version**: v2.3 (Framework-Agnostic + LLM-as-Judge)

**Status**: 📝 Draft -> [x] Review -> [x] Approved -> [x] Implemented -> [ ] Verified

> **⚠️ Single Source of Truth (SSOT) Declaration:**
>
> This document is the single source of truth for implementing the Evaluation Engine. Any AI-generated code MUST strictly follow this specification.
>
> **Architecture Review Prompt:**
>
> Review this specification from the perspective of LLM for Software Engineering (LLM4SE), Reproducible Research, and Concurrent Data Pipelines. Do not generate code until the review is passed.
>
> **v2.3 Updates (2026-07-24):**
> - ✅ **Phase F**: BenchmarkRunner 依赖从 `AgentOrchestrator` 改为 `EvaluableAgent` 统一接口，实现框架无关评测
> - ✅ **Phase F**: 新增适配器层：ShopMindAgentAdapter / LangChainAgentAdapter / OpenAIAdapter
> - ✅ **LLM-as-Judge**: 新增 `LlmJudgeMetricEvaluator`，用 DeepSeek 作为裁判进行 5 维 0-100 语义评分
> - ✅ **真实 LLM**: 新增 `DeepSeekChatAdapter` 支持 OpenAI 兼容协议流式调用
> - ✅ **EvaluationConfig**: `@Configuration` 自动装配 EvaluableAgent Bean 给 `@SpringBootTest`

## 1. Overview (模块概述)

Trustworthy Evaluation Engine 是 ShopMind 平台的"离线实验与基准测试中心 (Offline Benchmark System)"。

它通过消费 Workflow Engine 产生的 `ExecutionTrace`，驱动海量测试用例在不同场景（Scenario）下运行，执行严格的指标计算与失败归因分析（Failure Analysis），最终生成可直接用于学术论文与工程复盘的**结构化实验报告 (Experiment Report)**。

## 2. Research Alignment (科研对齐)

- **Reproducible Research (可复现研究)**：引入 `BenchmarkConfig`，严格记录 LLM 版本、Temperature、Embedding 模型等超参数，确保实验结果 100% 可复现。
- **LLM-as-a-Judge (大模型裁判)**：在幻觉检测中引入混合裁判机制，契合当前学术界主流的自动化评估趋势。
- **Failure Taxonomy (失败分类学)**：不仅关注 Accuracy，更关注 Agent 为何失败（意图错误、知识错误、沙箱拦截等），直接对应 TOSEM/ISSTA 等顶会的软件漏洞与可靠性分析方向。

## 3. Evaluation Dimensions (核心评估维度)

本引擎的评测体系对齐顶级软件工程会议的标准：

| **Dimension (维度)**     | **Metric (度量指标)**      | **评估目标**                                 |
| ------------------------ | -------------------------- | -------------------------------------------- |
| **Capability (能力)**    | Intent Accuracy            | Agent 是否正确识别用户的核心意图             |
| **Knowledge (知识)**     | Recall@K                   | RAG 是否召回了 Ground Truth 所需的知识片段   |
| **Reliability (可靠性)** | Hallucination Rate         | 最终回答是否捏造了 RAG 之外的事实            |
| **Execution (执行)**     | Tool Accuracy              | 工具调用及提取的参数是否与预期完全一致       |
| **Success (成功率)**     | Task Success Rate          | 端到端的业务任务（如查单、退款）是否最终完成 |
| **Performance (性能)**   | TTFT / p95 Latency         | 首字响应延迟与长尾端到端延迟                 |
| **Cost (成本)**          | Prompt / Completion Tokens | 单次任务的 Token 开销                        |
| **Robustness (鲁棒性)**  | Workflow Completion Rate   | 执行流是否因内部异常（Timeout/OOM）断裂      |

### 3.1 LLM-as-Judge 评估维度 (v2.3 新增)

当使用 `LlmJudgeMetricEvaluator` 时，用第二路 DeepSeek LLM 作为裁判，输出 5 维 0-100 评分：

| 维度 | 说明 | 通过阈值 |
|------|------|----------|
| Intent Match | 意图理解是否正确 | >= 60 |
| Tool Selection | 工具选择是否正确 | >= 60 |
| Task Success | 任务是否完成 | >= 60 |
| Hallucination | 幻觉程度 (0=无) | <= 30 |
| Knowledge Recall | 知识点覆盖率 | >= 60 |

## 4. Evaluation Pipeline (评测流水线)

系统采用高度解耦的流水线设计（v2.3 标注 Phase F + LLM-as-Judge 关键点）：

```plaintext
[Dataset & Config] ──▶ [BenchmarkRunner] ──▶ (Flux.flatMap + RateLimiter 并发驱动 EvaluableAgent)
                               │                    │
                               │                    ├── EvaluableAgent.chat(AgentInput) → Flux<String>
                               │                    ▼
                               │             [ExecutionTrace]
                               │                    │
                               ▼                    ▼
        ┌──────────────────────┴──────────────────────┐
        │                                             │
 [MetricEvaluator] (Mono<TestCaseResult>)    [FailureAnalyzer] (Mono<FailureReason>)
   ├── RuleBasedMetricEvaluator (关键词匹配)       (归因: Wrong Intent, Hallucination)
   └── LlmJudgeMetricEvaluator (LLM-as-Judge)             │
        ├── 5 维 0-100 语义评分                          │
        └── 用 DeepSeek 作为裁判 LLM                     │
        │                                             │
        └──────────────────────┬──────────────────────┘
                               ▼ (Flux.reduce 线程安全聚合)
                    [Experiment Report] (导出 JSON/Markdown 包含可视化数据)
```

**v2.3 Framework-Agnostic**: `BenchmarkRunnerImpl` 依赖 `EvaluableAgent` 接口（非具体 Orchestrator），通过适配器模式兼容 ShopMind / LangChain / OpenAI SDK 三种框架。

**v2.1 关键约束**：所有 `→` 标注的接口调用均为响应式非阻塞（`Mono<T>` / `Flux<T>`），绝不允许在 event loop 线程上执行同步 I/O。

## 5. Architecture & Data Model (架构与数据模型)

### 5.1 实验配置与数据集 (Experiment Config & Dataset)

用于保证科研可复现性的核心配置。

```java
record BenchmarkConfig(
    String experimentId,         // v2.1: 实验全局唯一 ID
    String workflowVersion,      // e.g., "v2.3"
    String datasetVersion,       // e.g., "v2.0"
    String llmProvider,          // e.g., "deepseek-chat", "qwen-max"
    double temperature,
    double topP,
    String embeddingModel,       // e.g., "text-embedding-v3"
    String vectorStore,          // e.g., "InMemory", "Qdrant"
    int maxConcurrency,          // v2.1: 并发上限
    int rpmLimit                 // v2.1: RPM 速率上限
) {}

record EvaluationDataset(
    String datasetId,
    DatasetScenario scenario,    // v2.1: 枚举
    List<TestCase> testCases
) {}

record TestCase(
    String testCaseId,
    String query,
    String expectedIntent,           // 预期意图 (e.g., "return_policy")
    String expectedTool,             // 预期工具 (e.g., "queryOrder")
    List<String> expectedKnowledge,  // 预期命中知识关键词
    String expectedAnswer,           // v2.3: 预期回答（供 LLM-as-Judge 使用）
    FailureReason expectedFailureReason  // v2.3: 预期失败原因（null=期望成功）
) {}

enum DatasetScenario {
    SAFETY,      // 安全攻击测试（Prompt Injection, Jailbreak）
    NORMAL,      // 常规业务对话（含成功+失败两类）
    STRESS,      // 长文本 / 高并发压力测试
    MULTI_TURN,  // 多轮对话上下文保持测试
    TOOL,        // v2.3: 工具调用专项测试
    RAG,         // v2.3: 知识检索专项测试
    EDGE         // v2.3: 极端/对抗用例
}

// v2.3 Phase F: 框架无关 Agent 输入
record AgentInput(
    String sessionId,
    String userMessage
) {}

// v2.3 Phase F: 统一可评测 Agent 接口
interface EvaluableAgent {
    String agentId();
    String agentVersion();
    Flux<String> chat(AgentInput input);
}
```

### 5.2 评估中间结果 (TestCaseResult) — v2.1 新增

```java
record TestCaseResult(
    String testCaseId,
    boolean intentMatch,
    boolean toolMatch,
    boolean knowledgeRecalled,
    double recallAtK,
    long ttftMs,
    long totalLatencyMs,
    int promptTokens,
    int completionTokens,
    FailureReason failureReason,     // null = 成功
    String answerSnippet,            // 截断的实际回复（用于人工审核）
    Map<String, Object> rawMetrics   // 扩展点
) {}
```

### 5.3 失败归因与实验报告 (Failure Analyzer & Experiment Report)

```java
enum FailureReason {
    WRONG_INTENT,           // 意图识别错误
    WRONG_TOOL,             // 工具选择错误
    WRONG_PARAMETER,        // 工具参数提取错误
    KNOWLEDGE_MISS,         // 知识未召回
    HALLUCINATION,          // 出现幻觉
    SAFETY_BLOCKED,         // 触发安全沙箱/策略拦截
    TIMEOUT                 // API 超时
}

class ExperimentReport {
    private String experimentId;
    private BenchmarkConfig metadata;           // 实验元数据
    private MetricSummary metrics;              // 各维度指标汇总
    private Map<FailureReason, Double> failureDistribution; // 失败分布（百分比）
    private CostSummary cost;                   // 总 Token 消耗与预估金额
    private List<FailedCaseDetail> failedDetails; // 失败 case 抽样
    private int totalCases;                     // v2.1: 总用例数
    private int passedCases;                    // v2.1: 通过用例数
}

record MetricSummary(
    double intentAccuracy,
    double avgRecallAtK,
    double hallucinationRate,
    double toolAccuracy,
    double taskSuccessRate,
    double avgTtftMs,
    double p95LatencyMs,
    double workflowCompletionRate
) {}

record CostSummary(
    long totalPromptTokens,
    long totalCompletionTokens,
    double estimatedCostUsd,
    String pricingModel           // e.g., "qwen-max: $0.02/1K tokens"
) {}

record FailedCaseDetail(
    String testCaseId,
    String query,
    FailureReason reason,
    String actualResponse,
    String diagnostics
) {}
```

## 6. API Design (内部接口) — v2.1 全异步化

### 6.1 BenchmarkRunner

```java
public interface BenchmarkRunner {
    /**
     * 执行全量 Benchmark 评测。
     *
     * @param dataset         评测数据集
     * @param config          实验超参数配置（保证可复现性）
     * @param isolationPrefix memoryId 前缀（如 "eval_v1.0_"），防止污染线上用户数据
     * @return 聚合后的实验报告。即使部分用例失败，Report 中也会包含失败详情。
     */
    Mono<ExperimentReport> run(EvaluationDataset dataset, BenchmarkConfig config, String isolationPrefix);
}
```

### 6.2 MetricEvaluator

```java
public interface MetricEvaluator {
    /**
     * 对单个用例进行指标评估。
     * 从 ExecutionTrace 中提取 Intent、Tool、Knowledge、Latency 等维度数据，
     * 与 TestCase 的预期值对比。
     *
     * @param expected  预期结果（Ground Truth）
     * @param actual    实际执行 Trace（来自 Workflow Engine）
     * @return Mono<TestCaseResult> — 异步返回评估结果
     */
    Mono<TestCaseResult> evaluate(TestCase expected, ExecutionTrace actual);
}
```

### 6.3 HallucinationEvaluator — v2.1 异步化

```java
public interface HallucinationEvaluator {
    /**
     * 判断回答是否包含幻觉（捏造了 RAG 之外的事实）。
     * 支持两种实现：
     *   - RuleBasedHallucinationJudge：基于规则（关键词白名单）判断
     *   - LlmAsAJudgeHallucinationJudge：调用 LLM API 进行自动化判断
     *
     * @param answer  Agent 最终输出文本
     * @param context RAG 召回的知识片段
     * @return Mono<Boolean> — 异步返回是否包含幻觉
     */
    Mono<Boolean> isHallucinated(String answer, RetrievedContext context);
}
```

### 6.4 FailureAnalyzer — v2.1 异步化

```java
public interface FailureAnalyzer {
    /**
     * 对失败用例进行根因分析。
     * 接收 TestCaseResult（包含 MetricEvaluator 的计算结果）和 ExecutionTrace，
     * 判断具体是哪个环节出了问题。
     *
     * @param expected 预期结果
     * @param metrics  指标评估结果（由 MetricEvaluator 先计算）
     * @param trace    完整执行 Trace
     * @return Mono<FailureReason> — 异步返回失败原因
     */
    Mono<FailureReason> analyze(TestCase expected, TestCaseResult metrics, ExecutionTrace trace);
}
```

## 7. Non-functional Requirement (并发与限流约束) — v2.1 强化

- **双重限流**：`BenchmarkRunner` 内部必须组合使用：
  - `Flux.flatMap(..., maxConcurrency)` 控制并发在途请求数
  - Resilience4j `@RateLimiter`（或 `RateLimiterOperator`）控制 RPM/TPM 速率，防止触发 LLM 厂商 HTTP 429
- **隔离性 (Isolation)**：评测过程中的 `ChatMemoryStore` 必须通过 `isolationPrefix` 参数分配独立的 `memoryId`，禁止污染线上真实用户的长期记忆数据。
- **异步要求**：所有接口返回 `Mono<T>`，禁止在响应式链中调用 `block()` 或同步 I/O。
- **聚合安全**：使用 `Flux.reduce()` 而非手动操作共享 Map，保证线程安全。

## 8. Implementation Guidance (v2.1 新增)

### 8.1 BenchmarkRunnerImpl 核心流程（v2.3 Phase F）

```java
@Component
public class BenchmarkRunnerImpl implements BenchmarkRunner {

    private final EvaluableAgent agent;  // v2.3: 框架无关接口
    private final MetricEvaluator evaluator;
    private final FailureAnalyzer failureAnalyzer;
    private final RateLimiterRegistry rateLimiterRegistry;

    @Override
    public Mono<ExperimentReport> run(EvaluationDataset dataset,
                                       BenchmarkConfig config,
                                       String isolationPrefix) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("llmRateLimiter");
        int maxConcurrency = config.maxConcurrency();

        return Flux.fromIterable(dataset.testCases())
            .flatMap(testCase -> Mono.just(testCase)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                // Step 1: 驱动 EvaluableAgent + 构建 ExecutionTrace
                .flatMap(tc -> executeAndCollectTrace(tc, isolationPrefix, config))
                // Step 2: 指标评估 (RuleBased 或 LlmJudge)
                .flatMap(ctx -> evaluator.evaluate(ctx.testCase, ctx.trace)
                    .map(result -> ctx.withMetrics(result)))
                // Step 3: 失败归因
                .flatMap(ctx -> failureAnalyzer.analyze(ctx.testCase, ctx.metrics, ctx.trace)
                    .map(reason -> ctx.withFailureReason(reason))
                    .defaultIfEmpty(ctx))
                // Step 4: 产出 TestCaseResult
                .map(BenchmarkRunnerImpl::toFinalResult),
                maxConcurrency
            )
            .reduce(new ExperimentReport(), (r, result) -> r.accumulate(result))
            .map(report -> report.finalize(config));
    }
    
    // v2.3: AgentInput 替代 OrchestrationRequest
    private Mono<EvalContext> executeAndCollectTrace(TestCase tc,
                                                      String prefix,
                                                      BenchmarkConfig config) {
        String memoryId = prefix + tc.testCaseId();
        AgentInput input = new AgentInput(memoryId, tc.query());
        // ... Trace 构建逻辑
        return agent.chat(input)  // v2.3: EvaluableAgent.chat()
            .collect(Collectors.joining())
            .map(answer -> new EvalContext(tc, trace, answer));
    }
}
```

### 8.2 通过 Reactor Context 提取 ExecutionTrace

Orchestrator 需增加一个重载方法，将 `TraceHandle` 的引用注入 Reactor Context：

```java
// ShopAgentOrchestrator 扩展
public Flux<String> chatWithTrace(OrchestrationRequest request, TraceCollector collector) {
    TraceHandle trace = traceRecorder.createTrace(request.memoryId(), workflowVersion);
    return chat(request)
        .doFinally(signal -> {
            trace.markComplete(deriveStatus(signal));
            collector.accept(trace);  // 将 Trace 传回 BenchmarkRunner
            traceRecorder.save(trace).subscribe();
        });
}
```

## 9. Phase F: Framework-Agnostic Architecture (v2.3 已完成)

```text
                  ┌──────────────────────┐
                  │  BenchmarkRunnerImpl  │
                  └──────────┬───────────┘
                             │ depends on
                  ┌──────────▼───────────┐
                  │   EvaluableAgent     │  ← 统一接口 (port)
                  │  + agentId()         │
                  │  + agentVersion()    │
                  │  + chat(AgentInput)  │
                  └──────────┬───────────┘
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                  ▼
  ShopMindAgentAdapter  LangChainAdapter  OpenAIAdapter
   ✅ 已实现              ✅ 骨架接口        ✅ 骨架接口
  (AgentOrchestrator)   (UnsupportedOpEx)  (UnsupportedOpEx)
```

### 已实现组件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `AgentInput.java` | `evaluation.domain` | 框架无关 Agent 输入 (record) |
| `EvaluableAgent.java` | `evaluation.port` | 统一可评测 Agent 接口 |
| `ShopMindAgentAdapter.java` | `evaluation.adapter` | ShopMind Orchestrator → EvaluableAgent |
| `LangChainAgentAdapter.java` | `evaluation.adapter` | LangChain 骨架适配器 |
| `OpenAIAdapter.java` | `evaluation.adapter` | OpenAI SDK 骨架适配器 |
| `EvaluationConfig.java` | `evaluation.config` | `@Configuration` 自动装配 Bean |

## 10. LLM Provider Support (v2.3)

| Profile | Chat Adapter | MetricEvaluator | Status |
|---------|-------------|-----------------|--------|
| 默认 (default) | `MockChatModelAdapter` | `RuleBasedMetricEvaluator` | ✅ 单元测试 |
| `deepseek` | `DeepSeekChatAdapter` | `LlmJudgeMetricEvaluator` | ✅ 真实 LLM 评测 |
| `qwen` | `DashScopeChatAdapter` | DashScope (WIP) | 🚧 待实现 Judge |

## 11. Future Evolution (演进路线)

- **Visualization (可视化输出)**：后续在前端或利用 Python 脚本，基于 `ExperimentReport` JSON 数据自动渲染：
  - **Radar Chart (雷达图)**：展示不同 LLM 模型在六大维度上的综合能力对比。
  - **Failure Pie (失败分布饼图)**：直观展示 Agent 的核心短板。
  - **Latency Curve (延迟长尾曲线)**：展示 TTFT 与总体延迟的 P50/P90/P99 曲线，证明系统的高可用性。
