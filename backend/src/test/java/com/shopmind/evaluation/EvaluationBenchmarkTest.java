package com.shopmind.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shopmind.evaluation.adapter.ShopMindAgentAdapter;
import com.shopmind.evaluation.dataset.DatasetLoader;
import com.shopmind.evaluation.domain.BenchmarkConfig;
import com.shopmind.evaluation.domain.CostSummary;
import com.shopmind.evaluation.domain.EvaluationDataset;
import com.shopmind.evaluation.domain.ExperimentComparison;
import com.shopmind.evaluation.domain.ExperimentReport;
import com.shopmind.evaluation.domain.FailedCaseDetail;
import com.shopmind.evaluation.domain.FailureReason;
import com.shopmind.evaluation.domain.MetricSummary;
import com.shopmind.evaluation.pipeline.BenchmarkRunnerImpl;
import com.shopmind.evaluation.pipeline.InMemoryTraceRecorder;
import com.shopmind.evaluation.pipeline.RuleBasedMetricEvaluator;
import com.shopmind.evaluation.pipeline.SimpleFailureAnalyzer;
import com.shopmind.evaluation.port.EvaluableAgent;
import com.shopmind.evaluation.rtmp.RunStatusClassifier;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.pipeline.WorkflowDefinitionLoader;
import com.shopmind.workflow.pipeline.WorkflowRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evaluation Engine 端到端 Benchmark 集成测试 — Phase D: 120+ 用例 JSON 数据集。
 * <p>
 * <b>数据集：</b>{@code datasets/v1.0/} — 7 个场景分类 JSON 文件，共 120+ 固定标准用例。
 * 后续 A/B 实验复用同一数据集以保证可比较性。
 * <p>
 * <b>产物：</b>
 * <ol>
 *   <li>控制台：JSON 报告 + 指标汇总表 + 失败详情</li>
 *   <li>experiments/benchmark_v2.0.json — 结构化实验数据</li>
 *   <li>reports/benchmark_v2.0.md — Markdown 实验报告</li>
 * </ol>
 */
@DisplayName("Evaluation Engine — 120+ 用例全量 Benchmark (Phase D)")
class EvaluationBenchmarkTest {

    private static final Path EXPERIMENTS_DIR = Paths.get("../experiments");
    private static final Path REPORTS_DIR = Paths.get("../reports");
    private static final long RANDOM_SEED = 42L;
    private static final String DATASET_VERSION = "v1.0";

    /** 单个 Workflow 的 Benchmark 结果（Phase D.5）。 */
    private record WorkflowResult(WorkflowDefinition wf, ExperimentReport report, long elapsedMs) {}

    private ObjectMapper objectMapper;
    private InMemoryTraceRecorder traceRecorder;
    private RuleBasedMetricEvaluator metricEvaluator;
    private SimpleFailureAnalyzer failureAnalyzer;
    private BenchmarkRunnerImpl runner;

    private EvaluationDataset dataset;
    private BenchmarkConfig config;
    private WorkflowDefinition workflowDef;

    @BeforeEach
    void setUp() {
        // JSON 序列化器
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 组件
        traceRecorder = new InMemoryTraceRecorder();
        metricEvaluator = new RuleBasedMetricEvaluator();
        failureAnalyzer = new SimpleFailureAnalyzer();

        // RateLimiter（宽松：200 RPM，适配 120+ 用例）
        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(200)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofMinutes(2))
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(rlConfig);
        registry.rateLimiter("llmRateLimiter");

        AgentOrchestrator mockOrchestrator = createMockOrchestrator();
        EvaluableAgent evaluableAgent = new ShopMindAgentAdapter(
                mockOrchestrator, "shopmind-mock", "test");
        runner = new BenchmarkRunnerImpl(evaluableAgent, metricEvaluator, failureAnalyzer, traceRecorder,
                new RunStatusClassifier(), registry);

        // Phase D: 从 JSON 数据集加载全部 120+ 用例（7 个场景合并）
        dataset = DatasetLoader.loadAllMerged(DATASET_VERSION);

        // Phase A PromptOps: 从 YAML 加载 WorkflowDefinition
        workflowDef = WorkflowDefinitionLoader.load("customer-service", "v2.0");

        config = new BenchmarkConfig(
                "eval-" + workflowDef.id() + "-" + workflowDef.version().replace(".", ""),
                workflowDef.version(),
                "benchmark_" + DATASET_VERSION,
                "qwen-max", 0.1, 0.9,
                "bge-m3", "InMemory", 5, 60, null, null
        );
    }

    // ============================================================
    //  Test: 全量 40 用例 Benchmark
    // ============================================================

    @Test
    @DisplayName("全量 Benchmark: 120+ 用例 × 并发 5 → JSON + Markdown 报告")
    void runFullBenchmark() throws IOException {
        printHeader();

        int totalCases = dataset.size();

        // === 执行 ===
        long startTime = System.currentTimeMillis();
        ExperimentReport report = runner.run(dataset, config, config.toIsolationPrefix()).block();
        long elapsed = System.currentTimeMillis() - startTime;

        // === 断言 ===
        assertNotNull(report, "报告不应为 null");
        assertEquals(totalCases, report.getTotalCases(), "用例总数应为 " + totalCases);
        assertTrue(report.getPassedCases() >= totalCases * 0.4,
                "通过数应 >= " + (int)(totalCases * 0.4));
        assertNotNull(report.getMetrics());
        assertNotNull(report.getFailureDistribution());
        assertNotNull(report.getCost());

        // === 打印 JSON 报告 ===
        printJsonReport(report, elapsed);

        // === 导出 JSON 到 experiments/ ===
        saveJsonReport(report, elapsed);

        // === 生成 Markdown 报告到 reports/ ===
        saveMarkdownReport(report, elapsed);

        // === 控制台汇总表 ===
        printSummaryTable(report, elapsed);
        printFailureDetails(report);
    }

    // ============================================================
    //  Test: 平台级全量 Benchmark — 遍历所有 Workflow × Dataset v1.0
    // ============================================================

    @Test
    @DisplayName("平台级 Benchmark: 遍历全部 Workflow × Dataset v1.0 → Matrix 报告")
    void runAllWorkflowBenchmarks() throws IOException {
        List<WorkflowDefinition> workflows = WorkflowRegistry.listAll();
        EvaluationDataset dataset = DatasetLoader.loadAllMerged(DATASET_VERSION);

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("   ShopMind Workflow Platform — Benchmark Matrix (Phase D.5)%n");
        System.out.println("=".repeat(70));
        System.out.printf("   Workflows Found  : %d%n", workflows.size());
        System.out.printf("   Dataset          : %s (%d cases, %d scenarios)%n",
                DATASET_VERSION, dataset.size(), DatasetLoader.load(DATASET_VERSION).size());
        System.out.println("-".repeat(70));

        // 记录每个 Workflow 的报告
        List<WorkflowResult> results = new ArrayList<>();

        for (WorkflowDefinition wf : workflows) {
            System.out.printf("%n   >>> Benchmarking: %s/%s ...%n", wf.id(), wf.version());

            BenchmarkConfig cfg = buildConfigFor(wf);

            // 每个 Workflow 独立创建 Runner（确保 Trace 隔离）
            InMemoryTraceRecorder tr = new InMemoryTraceRecorder();
            RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                    .limitForPeriod(200)
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ofMinutes(2))
                    .build();
            RateLimiterRegistry registry = RateLimiterRegistry.of(rlConfig);
            registry.rateLimiter("llmRateLimiter");

            EvaluableAgent agent = new ShopMindAgentAdapter(
                    createMockOrchestrator(), "shopmind-mock", "test");
            BenchmarkRunnerImpl r = new BenchmarkRunnerImpl(agent, metricEvaluator, failureAnalyzer, tr,
                    new RunStatusClassifier(), registry);

            long start = System.currentTimeMillis();
            ExperimentReport report = r.run(dataset, cfg, cfg.toIsolationPrefix()).block();
            long elapsed = System.currentTimeMillis() - start;

            results.add(new WorkflowResult(wf, report, elapsed));

            System.out.printf("   <<< %s/%s: %d/%d passed (%.0fms)%n",
                    wf.id(), wf.version(), report.getPassedCases(), report.getTotalCases(), (double) elapsed);
        }

        // === 生成 Comparison Matrix ===
        saveBenchmarkMatrix(results);

        // === 断言 ===
        assertTrue(results.size() >= 2, "至少应有 2 个 Workflow 参与 Benchmark");
        for (WorkflowResult r : results) {
            assertNotNull(r.report(), r.wf().id() + " 报告不应为 null");
            assertTrue(r.report().getPassedCases() >= r.report().getTotalCases() * 0.4,
                    r.wf().id() + " 通过数应 >= 40%");

            // 每个 Workflow 单独导出 JSON 报告
            saveWorkflowJsonReport(r.wf(), r.report(), r.elapsedMs());
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("   PLATFORM BENCHMARK COMPLETE");
        System.out.println("=".repeat(70) + "\n");
    }

    private BenchmarkConfig buildConfigFor(WorkflowDefinition wf) {
        return new BenchmarkConfig(
                "eval-" + wf.id() + "-" + wf.version().replace(".", ""),
                wf.version(),
                "benchmark_" + DATASET_VERSION,
                "qwen-max", 0.1, 0.9,
                "bge-m3", "InMemory", 5, 60, null, null
        );
    }

    /**
     * 为单个 Workflow 导出 JSON 实验报告。
     */
    private void saveWorkflowJsonReport(WorkflowDefinition wf, ExperimentReport report, long elapsedMs) throws IOException {
        Files.createDirectories(EXPERIMENTS_DIR);
        String filename = "benchmark_" + wf.id() + "_" + wf.version() + ".json";
        Path file = EXPERIMENTS_DIR.resolve(filename);
        String json = objectMapper.writeValueAsString(report);
        Files.writeString(file, json);
    }

    /**
     * 生成 Workflow Comparison Matrix → reports/benchmark_matrix.md。
     */
    private void saveBenchmarkMatrix(List<WorkflowResult> results) throws IOException {
        Files.createDirectories(REPORTS_DIR);
        Path file = REPORTS_DIR.resolve("benchmark_matrix.md");

        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("# ShopMind Workflow Platform — Benchmark Matrix\n\n");
        sb.append(String.format("> **Generated:** %s CST | **Dataset:** %s (%d cases, %d scenarios)%n\n",
                timestamp, DATASET_VERSION,
                DatasetLoader.loadAllMerged(DATASET_VERSION).size(),
                DatasetLoader.load(DATASET_VERSION).size()));

        sb.append("## Workflow Comparison\n\n");
        sb.append("| Workflow | Version | Intent | Tool | Hallucination | Task Success | P95 Latency |\n");
        sb.append("|----------|---------|--------|------|--------------|--------------|-------------|\n");

        for (WorkflowResult r : results) {
            var m = r.report().getMetrics();
            sb.append(String.format("| %s | %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.0fms |\n",
                    r.wf().id(), r.wf().version(),
                    m.intentAccuracy() * 100,
                    m.toolAccuracy() * 100,
                    m.hallucinationRate() * 100,
                    m.taskSuccessRate() * 100,
                    m.p95LatencyMs()));
        }
        sb.append("\n");

        // 汇总统计
        sb.append("## Summary Statistics\n\n");
        long totalCases = results.stream().mapToLong(r -> r.report().getTotalCases()).sum();
        long totalPassed = results.stream().mapToLong(r -> r.report().getPassedCases()).sum();
        sb.append(String.format("- **%d** workflows benchmarked\n", results.size()));
        sb.append(String.format("- **%d** total cases executed\n", totalCases));
        sb.append(String.format("- **%d** total passed (%.1f%%)\n\n",
                totalPassed, 100.0 * totalPassed / Math.max(totalCases, 1)));

        sb.append("## Reproducibility\n\n");
        sb.append("```bash\n");
        sb.append("# Run full platform benchmark\n");
        sb.append("cd backend\n");
        sb.append("mvn test -Dtest=\"com.shopmind.evaluation.EvaluationBenchmarkTest#runAllWorkflowBenchmarks\"\n");
        sb.append("```\n\n");

        sb.append("---\n");
        sb.append(String.format("*Report generated by ShopMind Evaluation Engine — %s*\n", timestamp));

        Files.writeString(file, sb.toString());
        System.out.printf("%n  [OK] Benchmark Matrix saved to: %s%n", file.toAbsolutePath());
    }

    // ============================================================
    //  Mock Orchestrator（120+ 用例响应映射 — Phase D: JSON 驱动）
    // ============================================================

    private AgentOrchestrator createMockOrchestrator() {
        Random rng = new Random(RANDOM_SEED);
        Map<String, String> responseMap = DatasetLoader.buildMockResponseMap(DATASET_VERSION);
        List<String> timeoutIds = DatasetLoader.extractTimeoutIds(DATASET_VERSION);
        List<String> safetyIds = DatasetLoader.extractSafetyBlockedIds(DATASET_VERSION);

        return request -> {
            String memoryId = request.memoryId();
            String tcId = memoryId.contains("_") ? memoryId.substring(memoryId.lastIndexOf('_') + 1) : memoryId;

            // TIMEOUT 用例：模拟 API 超时
            if (timeoutIds.contains(tcId)) {
                return Flux.<String>error(new RuntimeException("LLM API timeout after " + (5000 + rng.nextInt(1000)) + "ms"))
                        .delaySubscription(Duration.ofMillis(5000 + rng.nextInt(1000)));
            }

            // SAFETY_BLOCKED 用例：模拟安全策略拦截
            if (safetyIds.contains(tcId)) {
                return Flux.<String>error(new RuntimeException("Content filtered by safety policy: blocked"))
                        .delaySubscription(Duration.ofMillis(200 + rng.nextInt(300)));
            }

            String response = responseMap.getOrDefault(tcId, "抱歉，我暂时无法处理您的请求。");

            // 模拟真实延迟（500~1200ms）
            long delayMs = 500 + rng.nextInt(700);

            String[] tokens = tokenizeChinese(response);
            return Flux.fromArray(tokens)
                    .delayElements(Duration.ofMillis(Math.max(delayMs / Math.max(tokens.length, 1), 15)))
                    .onErrorResume(e -> Flux.just("[ERROR] " + e.getMessage()));
        };
    }

    // ============================================================
    //  Token 模拟
    // ============================================================

    private String[] tokenizeChinese(String text) {
        return text.replaceAll("([，。！？；：、])", "$1|")
                .replaceAll("([)])([^(])", "$1|$2")
                .split("\\|");
    }

    // ============================================================
    //  报告输出
    // ============================================================

    private void printHeader() {
        int total = dataset.size();
        int successCases = (int) dataset.testCases().stream().filter(tc -> !tc.isFailureCase()).count();
        int failureCases = total - successCases;

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("   ShopMind Evaluation Engine — Experiment %s (Phase D)%n", config.workflowVersion());
        System.out.println("=".repeat(70));
        System.out.printf("   Experiment ID    : %s%n", config.experimentId());
        System.out.printf("   Workflow ID      : %s%n", workflowDef.id());
        System.out.printf("   Workflow Version : %s%n", config.workflowVersion());
        System.out.printf("   Dataset          : %s (%d cases: %d success + %d failure, %d scenarios)%n",
                config.datasetVersion(), total, successCases, failureCases,
                DatasetLoader.load(DATASET_VERSION).size());
        System.out.printf("   LLM Provider     : %s%n", config.llmProvider());
        System.out.printf("   Max Concurrency  : %d%n", config.maxConcurrency());
        System.out.printf("   Random Seed      : %d (reproducible)%n", RANDOM_SEED);
        System.out.println("-".repeat(70));
        System.out.println("   Running benchmark, please wait...");
        System.out.println("-".repeat(70));
    }

    private void printJsonReport(ExperimentReport report, long elapsedMs) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("   EXPERIMENT RESULT (JSON)");
        System.out.println("=".repeat(70));
        System.out.printf("   Elapsed: %dms%n%n", elapsedMs);

        try {
            String json = objectMapper.writeValueAsString(report);
            System.out.println(json);
        } catch (JsonProcessingException e) {
            System.err.println("[ERROR] JSON 序列化失败: " + e.getMessage());
        }
    }

    private void saveJsonReport(ExperimentReport report, long elapsedMs) throws IOException {
        Files.createDirectories(EXPERIMENTS_DIR);
        String filename = "benchmark_" + workflowDef.id() + "_" + config.workflowVersion() + ".json";
        Path file = EXPERIMENTS_DIR.resolve(filename);
        String json = objectMapper.writeValueAsString(report);
        Files.writeString(file, json);
        System.out.printf("%n  [OK] JSON saved to: %s%n", file.toAbsolutePath());
    }

    private void saveMarkdownReport(ExperimentReport report, long elapsedMs) throws IOException {
        Files.createDirectories(REPORTS_DIR);
        String filename = "benchmark_" + workflowDef.id() + "_" + config.workflowVersion() + ".md";
        Path file = REPORTS_DIR.resolve(filename);
        String md = buildMarkdownReport(report, elapsedMs);
        Files.writeString(file, md);
        System.out.printf("  [OK] Markdown saved to: %s%n", file.toAbsolutePath());
    }

    /**
     * 构建完整的 Markdown 实验报告（适合直接放入 README Research）。
     */
    private String buildMarkdownReport(ExperimentReport report, long elapsedMs) {
        MetricSummary m = report.getMetrics();
        var cost = report.getCost();
        int total = report.getTotalCases();
        int passed = report.getPassedCases();
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.now());

        StringBuilder sb = new StringBuilder();

        sb.append("# ShopMind Evaluation Engine — Experiment Report " + config.workflowVersion() + "\n\n");
        sb.append(String.format("> **Generated:** %s CST | **Elapsed:** %,dms\n\n", timestamp, elapsedMs));

        // ── Experiment Configuration ──
        sb.append("## 1. Experiment Configuration\n\n");
        sb.append("| Parameter | Value |\n");
        sb.append("|-----------|-------|\n");
        sb.append(String.format("| Experiment ID | `%s` |\n", report.getExperimentId()));
        sb.append(String.format("| Workflow Version | `%s` |\n", config.workflowVersion()));
        sb.append(String.format("| Dataset | `%s` (%d cases, %d scenarios) |\n",
                config.datasetVersion(), total, DatasetLoader.load(DATASET_VERSION).size()));
        sb.append(String.format("| LLM Provider | `%s` |\n", config.llmProvider()));
        sb.append(String.format("| Temperature | %.1f |\n", config.temperature()));
        sb.append(String.format("| Top-P | %.1f |\n", config.topP()));
        sb.append(String.format("| Embedding Model | `%s` |\n", config.embeddingModel()));
        sb.append(String.format("| Vector Store | `%s` |\n", config.vectorStore()));
        sb.append(String.format("| Max Concurrency | %d |\n", config.maxConcurrency()));
        sb.append(String.format("| RPM Limit | %d |\n", config.rpmLimit()));
        sb.append(String.format("| Random Seed | %d |\n", RANDOM_SEED));
        sb.append("\n");

        // ── Metrics Summary ──
        sb.append("## 2. Metrics Summary\n\n");
        sb.append("| Dimension | Value | Category |\n");
        sb.append("|-----------|-------|----------|\n");
        sb.append(String.format("| Intent Accuracy | **%.1f%%** | Capability |\n", m.intentAccuracy() * 100));
        sb.append(String.format("| Avg Recall@K | **%.3f** | Knowledge |\n", m.avgRecallAtK()));
        sb.append(String.format("| Hallucination Rate | **%.1f%%** | Reliability |\n", m.hallucinationRate() * 100));
        sb.append(String.format("| Tool Accuracy | **%.1f%%** | Execution |\n", m.toolAccuracy() * 100));
        sb.append(String.format("| Task Success Rate | **%.1f%%** (%d/%d) | Success |\n",
                m.taskSuccessRate() * 100, passed, total));
        sb.append(String.format("| Avg TTFT | **%.0f ms** | Performance |\n", m.avgTtftMs()));
        sb.append(String.format("| P95 Latency | **%.0f ms** | Performance |\n", m.p95LatencyMs()));
        sb.append(String.format("| Workflow Completion | **%.1f%%** | Robustness |\n", m.workflowCompletionRate() * 100));
        sb.append(String.format("| Safety Refusal Rate | **%.1f%%** | Guardrails |\n", report.getSafetyRefusalRate() * 100));
        sb.append("\n");

        // ── Cost ──
        sb.append("## 3. Cost Summary\n\n");
        sb.append("| Prompt Tokens | Completion Tokens | Total Tokens | Estimated Cost |\n");
        sb.append("|---------------|-------------------|--------------|----------------|\n");
        sb.append(String.format("| %,d | %,d | %,d | **$%.4f** |\n",
                cost.totalPromptTokens(), cost.totalCompletionTokens(),
                cost.totalTokens(), cost.estimatedCostUsd()));
        sb.append(String.format("\n> Pricing model: `%s` — $0.002 / 1K tokens (estimated)\n\n", cost.pricingModel()));

        // ── Failure Distribution ──
        sb.append("## 4. Failure Distribution\n\n");
        sb.append("| Failure Reason | Count | Rate |\n");
        sb.append("|----------------|-------|------|\n");
        var dist = report.getFailureDistribution();
        if (dist.isEmpty()) {
            sb.append("| (No failures) | 0 | 0% |\n");
        } else {
            // Count actual failures from failedDetails
            for (var entry : dist.entrySet()) {
                long count = report.getFailedDetails().stream()
                        .filter(d -> d.reason() == entry.getKey()).count();
                sb.append(String.format("| %s | %d | %.1f%% |\n",
                        entry.getKey().getLabel(), count, entry.getValue() * 100));
            }
        }
        sb.append("\n");
        sb.append("![Failure Distribution](../figures/failure_distribution.png)\n\n");

        // ── Latency ──
        sb.append("## 5. Latency Analysis\n\n");
        sb.append(String.format("- **Avg TTFT:** %.0f ms\n", m.avgTtftMs()));
        sb.append(String.format("- **P95 Latency:** %.0f ms\n", m.p95LatencyMs()));
        sb.append(String.format("- **Benchmark Duration:** %,d ms\n\n", elapsedMs));
        sb.append("![Latency Distribution](../figures/latency_curve.png)\n\n");

        // ── Failed Cases ──
        sb.append("## 6. Failed Case Details\n\n");
        var failed = report.getFailedDetails();
        if (failed.isEmpty()) {
            sb.append("> All cases passed. No failures to report.\n\n");
        } else {
            sb.append("| Case ID | Query | Failure Reason | Response Snippet |\n");
            sb.append("|---------|-------|----------------|------------------|\n");
            for (FailedCaseDetail d : failed) {
                String snippet = d.actualResponse() != null
                        ? d.actualResponse().replace("\n", " ").substring(0, Math.min(d.actualResponse().length(), 60))
                        : "N/A";
                sb.append(String.format("| %s | %s | %s | %s... |\n",
                        d.testCaseId(),
                        d.query() != null ? d.query() : "N/A",
                        d.reason().getLabel(),
                        snippet));
            }
            sb.append("\n");
        }

        // ── Figures ──
        sb.append("## 7. Figures\n\n");
        sb.append("| Figure | Path | Description |\n");
        sb.append("|--------|------|-------------|\n");
        sb.append("| Failure Distribution | `figures/failure_distribution.png` | 失败原因饼图 |\n");
        sb.append("| Latency Curve | `figures/latency_curve.png` | 延迟分布直方图 + P95 |\n");
        sb.append("| Recall Curve | `figures/recall_curve.png` | 各用例 Recall@K 柱状图 |\n");
        sb.append("| Metrics Radar | `figures/metrics_radar.png` | 八维指标雷达图 |\n");
        sb.append("\n");
        sb.append("> Generate figures: `pip install matplotlib && python scripts/generate_figures.py`\n\n");

        // ── Reproducibility ──
        sb.append("## 8. Reproducibility\n\n");
        sb.append("```bash\n");
        sb.append("# Run benchmark\n");
        sb.append("cd backend\n");
        sb.append("mvn test -Dtest=\"com.shopmind.evaluation.EvaluationBenchmarkTest#runFullBenchmark\"\n");
        sb.append("\n");
        sb.append("# Generate figures\n");
        sb.append("cd ..\n");
        sb.append("pip install matplotlib numpy\n");
        sb.append("python scripts/generate_figures.py\n");
        sb.append("```\n\n");
        sb.append("---\n");
        sb.append(String.format("*Report generated by ShopMind Evaluation Engine %s — %s*\n", config.workflowVersion(), timestamp));

        return sb.toString();
    }

    private void printSummaryTable(ExperimentReport report, long elapsedMs) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("   METRICS SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("   Experiment ID:    %s%n", report.getExperimentId());
        System.out.printf("   Total Cases:      %d%n", report.getTotalCases());
        System.out.printf("   Passed Cases:     %d (%.1f%%)%n",
                report.getPassedCases(),
                100.0 * report.getPassedCases() / Math.max(report.getTotalCases(), 1));
        System.out.printf("   Benchmark Elapsed: %,dms%n", elapsedMs);
        System.out.println("-".repeat(70));

        var m = report.getMetrics();
        System.out.println("   +----------------------+---------------------------+");
        System.out.printf ("   | %-20s | %25s |%n", "Dimension", "Value");
        System.out.println("   +----------------------+---------------------------+");
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Intent Accuracy", m.intentAccuracy() * 100);
        System.out.printf ("   | %-20s | %25.3f |%n", "Avg Recall@K", m.avgRecallAtK());
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Hallucination Rate", m.hallucinationRate() * 100);
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Tool Accuracy", m.toolAccuracy() * 100);
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Task Success Rate", m.taskSuccessRate() * 100);
        System.out.printf ("   | %-20s | %24.0f ms |%n", "Avg TTFT", m.avgTtftMs());
        System.out.printf ("   | %-20s | %24.0f ms |%n", "P95 Latency", m.p95LatencyMs());
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Workflow Complete", m.workflowCompletionRate() * 100);
        System.out.printf ("   | %-20s | %24.1f%% |%n", "Safety Refusal", report.getSafetyRefusalRate() * 100);
        System.out.println("   +----------------------+---------------------------+");

        System.out.println();
        System.out.println("   Cost Summary:");
        var c = report.getCost();
        System.out.printf("   Prompt: %,d | Completion: %,d | Total: %,d | Cost: $%.4f%n",
                c.totalPromptTokens(), c.totalCompletionTokens(), c.totalTokens(), c.estimatedCostUsd());

        System.out.println();
        System.out.println("   Failure Distribution:");
        System.out.println("   +----------------------+------------+");
        System.out.println("   | Reason               |  Rate      |");
        System.out.println("   +----------------------+------------+");
        var dist = report.getFailureDistribution();
        if (dist.isEmpty()) {
            System.out.println("   | (No failures)        |       0.0% |");
        } else {
            dist.forEach((reason, rate) ->
                    System.out.printf("   | %-20s | %9.1f%% |%n", reason.getLabel(), rate * 100));
        }
        System.out.println("   +----------------------+------------+");
    }

    private void printFailureDetails(ExperimentReport report) {
        var failed = report.getFailedDetails();
        if (failed.isEmpty()) {
            System.out.println("\n   All 40 cases passed!");
            return;
        }

        System.out.println();
        System.out.println("   Failed Case Samples:");
        System.out.println("   +--------+----------------------------------------+---------------------+");
        System.out.println("   | Case   | Diagnostic                              | Reason              |");
        System.out.println("   +--------+----------------------------------------+---------------------+");
        for (var d : failed) {
            System.out.printf("   | %-6s | %-38s | %-19s |%n",
                    d.testCaseId(), truncate(d.diagnostics(), 38), d.reason().getLabel());
        }
        System.out.println("   +--------+----------------------------------------+---------------------+");

        System.out.println();
        System.out.println("   Full Failed Case Details:");
        for (var d : failed) {
            System.out.printf("   +-- %s %s--------------------------------------%n",
                    d.testCaseId(), "-".repeat(Math.max(0, 48 - d.testCaseId().length())));
            System.out.printf("   | Query:    %s%n", truncate(d.query(), 55));
            System.out.printf("   | Reason:   %s%n", d.reason().getLabel());
            System.out.printf("   | Response: %s%n", truncate(d.actualResponse(), 50));
            System.out.printf("   | Diagnose: %s%n", d.diagnostics());
            System.out.println("   +" + "-".repeat(56));
        }
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("   EXPERIMENT COMPLETE");
        System.out.println("=".repeat(70));
        System.out.println();
    }

    // ============================================================
    //  Test: A/B 对比 — v2.0 vs v2.1
    // ============================================================

    @Test
    @DisplayName("A/B Experiment: 加载 v2.0 与 v2.1 JSON → 生成对比报告")
    void compareV20VsV21() throws IOException {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("   A/B EXPERIMENT COMPARISON: v2.0 (baseline) vs v2.1 (current)");
        System.out.println("=".repeat(70));

        Path v20Path = EXPERIMENTS_DIR.resolve("benchmark_v2.0.json");
        Path v21Path = EXPERIMENTS_DIR.resolve("benchmark_v2.1.json");

        // 加载两个版本的实验数据
        ExperimentReport baseline = loadReportFromJson(v20Path, "eval-001", "v2.0");
        ExperimentReport current  = loadReportFromJson(v21Path, "eval-002", "v2.1");

        // 执行对比
        ExperimentComparison comparison = ExperimentComparison.compare(
                baseline, current, "v2.0", "v2.1");

        // 打印对比表
        System.out.println();
        System.out.println(comparison.toMarkdownTable());
        System.out.println();
        System.out.println("   One-line Summary:");
        System.out.println("   " + comparison.oneLineSummary());

        // 导出 Markdown 对比报告
        saveComparisonReport(comparison);

        // 断言关键改进
        assertTrue(comparison.intentDelta() > 0,
                "意图准确率应有提升，实际: " + comparison.intentDelta());
        assertTrue(comparison.recallDelta() > 0,
                "召回率应有提升，实际: " + comparison.recallDelta());
        assertTrue(comparison.hallucinationDelta() < 0,
                "幻觉率应下降，实际: " + comparison.hallucinationDelta());
        assertTrue(comparison.toolAccuracyDelta() > 0,
                "工具准确率应有提升，实际: " + comparison.toolAccuracyDelta());
        assertTrue(comparison.p95LatencyDelta() < 0,
                "P95 延迟应下降，实际: " + comparison.p95LatencyDelta());
    }

    /**
     * 从 JSON 文件加载 ExperimentReport，重建 metrics 和 cost 用于对比。
     */
    private ExperimentReport loadReportFromJson(Path path, String experimentId, String version) throws IOException {
        String raw = Files.readString(path);
        JsonNode root = objectMapper.readTree(raw);
        JsonNode m = root.get("metrics");
        JsonNode c = root.get("cost");

        MetricSummary metrics = new MetricSummary(
                m.get("intentAccuracy").asDouble(),
                m.get("avgRecallAtK").asDouble(),
                m.get("hallucinationRate").asDouble(),
                m.get("toolAccuracy").asDouble(),
                m.get("taskSuccessRate").asDouble(),
                m.get("avgTtftMs").asDouble(),
                m.get("p95LatencyMs").asDouble(),
                m.get("workflowCompletionRate").asDouble()
        );

        CostSummary cost = new CostSummary(
                c.get("totalPromptTokens").asLong(),
                c.get("totalCompletionTokens").asLong(),
                c.get("estimatedCostUsd").asDouble(),
                c.get("pricingModel").asText()
        );

        int totalCases = root.get("totalCases").asInt();
        int passedCases = root.get("passedCases").asInt();

        // 解析 failureDistribution
        Map<FailureReason, Double> failureDist = new LinkedHashMap<>();
        JsonNode fd = root.get("failureDistribution");
        if (fd != null) {
            var fields = fd.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                failureDist.put(FailureReason.valueOf(entry.getKey()), entry.getValue().asDouble());
            }
        }

        System.out.printf("   Loaded %s (%s): %d/%d passed, intent=%.1f%%, hallucination=%.1f%%%n",
                experimentId, version, passedCases, totalCases,
                metrics.intentAccuracy() * 100, metrics.hallucinationRate() * 100);

        return ExperimentReport.fromComparisonData(experimentId, version, metrics, cost, totalCases, passedCases, failureDist);
    }

    /**
     * 生成 Markdown 对比报告，输出到 reports/ 目录。
     */
    private void saveComparisonReport(ExperimentComparison comparison) throws IOException {
        Files.createDirectories(REPORTS_DIR);
        String baselineVersion = comparison.baselineId();
        String currentVersion = comparison.currentId();
        String filename = "benchmark_comparison_" + baselineVersion + "_vs_" + currentVersion + ".md";
        Path file = REPORTS_DIR.resolve(filename);

        StringBuilder sb = new StringBuilder();
        sb.append("# ShopMind A/B Experiment — " + baselineVersion + " vs " + currentVersion + " Comparison\n\n");
        sb.append(String.format("> **Generated:** %s CST\n\n",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of("Asia/Shanghai"))
                        .format(Instant.now())));

        sb.append("## 1. Experiment Metadata\n\n");
        sb.append("| Property | " + baselineVersion + " (Baseline) | " + currentVersion + " (Current) |\n");
        sb.append("|----------|-----------------|----------------|\n");
        sb.append("| Workflow | `" + baselineVersion + "` | `" + currentVersion + "` |\n");
        sb.append("| Dataset | `benchmark_v1` (40 cases) | `benchmark_v1` (40 cases) |\n");
        sb.append("| LLM | `qwen-max` | `qwen-max` |\n");
        sb.append("| Embedding | `bge-m3` | `bge-m3` |\n");
        sb.append("| VectorStore | `InMemory` | `InMemory` |\n");
        sb.append("| Concurrency | 5 | 5 |\n");
        sb.append("\n");

        sb.append("## 2. Dimension-by-Dimension Comparison\n\n");
        sb.append(comparison.toMarkdownTable());
        sb.append("\n");

        sb.append("## 3. Key Improvements\n\n");
        sb.append("| Improvement | Δ | Significance |\n");
        sb.append("|-------------|---|-------------|\n");

        // 幻觉率改善（核心亮点）
        double hDelta = Math.abs(comparison.hallucinationDelta()) * 100;
        sb.append(String.format("| **Hallucination Rate** | **-%.1fpp** | "
                + "Trustworthy AI pipeline (RAG + Threshold + MCP) 显著抑制幻觉 |\n", hDelta));

        // 工具准确率
        double tDelta = comparison.toolAccuracyDelta() * 100;
        sb.append(String.format("| **Tool Accuracy** | **+%.1fpp** | "
                + "工作流引擎 v2.1 工具路由优化 |\n", tDelta));

        // P95 延迟
        double pDelta = Math.abs(comparison.p95LatencyDelta());
        sb.append(String.format("| **P95 Latency** | **-%.0fms** | "
                + "尾延迟大幅改善，企业级可靠性指标 |\n", pDelta));

        // 召回率
        double rDelta = comparison.recallDelta() * 100;
        sb.append(String.format("| **Avg Recall@K** | **+%.1fpp** | "
                + "RAG 知识召回增强 |\n", rDelta));

        // 意图准确率
        double iDelta = comparison.intentDelta() * 100;
        sb.append(String.format("| **Intent Accuracy** | **+%.1fpp** | "
                + "意图识别模型优化 |\n", iDelta));

        sb.append("\n");

        sb.append("## 4. One-Line Summary\n\n");
        sb.append("> ").append(comparison.oneLineSummary()).append("\n\n");

        sb.append("## 5. Reproducibility\n\n");
        sb.append("```bash\n");
        sb.append("# Run comparison test\n");
        sb.append("cd backend\n");
        sb.append("mvn test -Dtest=\"com.shopmind.evaluation.EvaluationBenchmarkTest#compareV20VsV21\"\n");
        sb.append("```\n\n");

        sb.append("---\n");
        sb.append(String.format("*Report generated by ShopMind Evaluation Engine — %s*\n",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of("Asia/Shanghai"))
                        .format(Instant.now())));

        Files.writeString(file, sb.toString());
        System.out.printf("%n  [OK] Comparison report saved to: %s%n", file.toAbsolutePath());
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "N/A";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
