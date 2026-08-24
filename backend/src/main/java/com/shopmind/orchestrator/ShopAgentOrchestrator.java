package com.shopmind.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.experiment.ControlOverheadInstrumentation;
import com.shopmind.experiment.ControlType;
import com.shopmind.experiment.ExperimentCondition;
import com.shopmind.experiment.ExperimentRuntimeConfig;
import com.shopmind.experiment.RouterContext;
import com.shopmind.experiment.RouterContextFactory;
import com.shopmind.experiment.SafetyDecision;
import com.shopmind.experiment.SafetyVerificationRequest;
import com.shopmind.experiment.ToolSafetyVerifier;
import com.shopmind.experiment.VisibilityResult;
import com.shopmind.mcp.McpEngine;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.AiMessage;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.SystemMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.ChatStreamEvent;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.orchestrator.domain.ExecutionStep;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.exception.LlmProviderTimeoutException;
import com.shopmind.orchestrator.exception.MaxIterationExceededException;
import com.shopmind.orchestrator.exception.PromptAssemblyException;
import com.shopmind.orchestrator.observability.RequestObservabilityLogger;
import com.shopmind.orchestrator.pipeline.ContextHydrationStep;
import com.shopmind.orchestrator.pipeline.ToolIterationGuard;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.orchestrator.port.ChatStreamingPort;
import com.shopmind.orchestrator.port.IntentAnalyzer;
import com.shopmind.workflow.domain.ControlOverheadEvent;
import com.shopmind.workflow.domain.ExecutionTrace;
import com.shopmind.workflow.domain.PruningEvent;
import com.shopmind.workflow.domain.RunIdentity;
import com.shopmind.workflow.domain.ToolCallEvent;
import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowInstance;
import com.shopmind.workflow.pipeline.WorkflowDefinitionLoader;
import com.shopmind.workflow.port.WorkflowRenderer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 智能体编排总入口 — §10 规范 + §6 全部约束。
 * <p>
 * <b>职责总览</b><br>
 * ShopAgentOrchestrator 是 AI Platform 的"中枢神经"。它串联 Intent → Memory+RAG → Prompt → LLM → Tool Loop,
 * 每一个环节均有降级保护。
 * <p>
 * <b>Phase C PromptOps 集成：</b>System Prompt 不再硬编码，而是通过
 * {@link WorkflowRenderer} 将 YAML 定义的 {@link WorkflowDefinition}
 * （persona + toolRules + constraints）渲染为完整的 System Prompt。
 * <p>
 * <b>线程安全</b><br>
 * 本类是 @Component 单例，<b>不持有任何请求级状态</b>（无 OrchestrationContext 实例字段）。
 * 所有请求状态均在方法参数/返回值中传递。
 * <p>
 * <b>响应式设计</b><br>
 * - 全链路基于 Project Reactor (Flux/Mono)，非阻塞。
 * - 配置 {@code onBackpressureBuffer} 防止慢客户端 OOM。
 * - LLM 调用通过 Resilience4j {@link CircuitBreaker} 保护。
 * <p>
 * <b>Inner/Outer Loop</b><br>
 * Outer Loop: Intent → Context Hydration → Prompt (WorkflowRenderer) → LLM<br>
 * Inner Loop: LLM ToolCall → MCP Engine → write Memory → re-prompt LLM (max 3 iterations)
 */
@Component
public class ShopAgentOrchestrator implements AgentOrchestrator, ChatStreamingPort {

    private static final Logger log = LoggerFactory.getLogger(ShopAgentOrchestrator.class);

    /** 背压缓冲区大小 */
    private static final int BACKPRESSURE_BUFFER = 256;

    /** 默认生产环境工作流定义 */
    private static final String DEFAULT_WORKFLOW_ID = "customer-service";
    private static final String DEFAULT_WORKFLOW_VERSION = "v2.3";

    /** LLM Provider 单次流式调用超时（§6/§11：超时抛 LlmProviderTimeoutException 并触发降级） */
    @Value("${shopmind.llm.timeout-ms:30000}")
    private long llmTimeoutMs;

    private final IntentAnalyzer intentAnalyzer;
    private final ContextHydrationStep hydrationStep;
    private final ChatModelPort chatModelPort;
    private final McpEngine mcpEngine;
    private final ChatMemoryStore memoryStore;
    private final ToolIterationGuard iterationGuard;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper;

    /** P1-1: 请求级可观测性日志输出器 */
    private final RequestObservabilityLogger observabilityLogger;

    /** Phase C: 工作流渲染器（纯函数，将 WorkflowInstance → System Prompt） */
    private final WorkflowRenderer renderer;

    /** P4-3: RouterContext 工厂（从 OrchestrationContext + 工具构建 Router 合法运行时输入） */
    private final RouterContextFactory routerContextFactory = new RouterContextFactory();

    /** Phase C: 当前工作流静态定义（persona + toolRules + constraints） */
    private volatile WorkflowDefinition workflowDefinition;

    public ShopAgentOrchestrator(
            IntentAnalyzer intentAnalyzer,
            ContextHydrationStep hydrationStep,
            WorkflowRenderer renderer,
            ChatModelPort chatModelPort,
            McpEngine mcpEngine,
            ChatMemoryStore memoryStore,
            ToolIterationGuard iterationGuard,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectMapper objectMapper,
            RequestObservabilityLogger observabilityLogger) {
        this.intentAnalyzer = intentAnalyzer;
        this.hydrationStep = hydrationStep;
        this.renderer = renderer;
        this.chatModelPort = chatModelPort;
        this.mcpEngine = mcpEngine;
        this.memoryStore = memoryStore;
        this.iterationGuard = iterationGuard;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.objectMapper = objectMapper;
        this.observabilityLogger = observabilityLogger;

        // Phase C: 加载 YAML 工作流定义（persona + toolRules + constraints）
        this.workflowDefinition = WorkflowDefinitionLoader.load(
                DEFAULT_WORKFLOW_ID, DEFAULT_WORKFLOW_VERSION);
        log.info("[Orchestrator] Initialized with workflow: id={}, version={}, toolRules={}, constraints={}",
                workflowDefinition.id(), workflowDefinition.version(),
                workflowDefinition.toolRules().size(), workflowDefinition.constraints().size());
    }

    /**
     * 动态切换工作流定义（供消融实验等测试场景使用）。
     * <p>
     * 生产环境不应调用此方法——工作流在构造时确定后保持不变。
     * 使用 volatile 保证多线程可见性。
     */
    public void setWorkflowDefinition(WorkflowDefinition wf) {
        this.workflowDefinition = wf;
        log.info("[Orchestrator] WorkflowDefinition switched to: id={}, version={}", wf.id(), wf.version());
    }

    // ============================================================
    //  chat — 纯文本入口（§9 API Design，供评测引擎使用）
    // ============================================================

    @Override
    public Flux<String> chat(OrchestrationRequest request) {
        return stream(request).map(this::toText);
    }

    // ============================================================
    //  stream — 结构化事件入口（供 HTTP/SSE 使用）
    // ============================================================

    @Override
    public Flux<ChatStreamEvent> stream(OrchestrationRequest request) {
        // 1. 创建请求级上下文（每次请求都是新实例，不会跨线程共享）
        OrchestrationContext ctx = new OrchestrationContext(request.memoryId(), request.userMessage());
        ctx.setModel(chatModelPort.modelName());

        long startNanos = System.nanoTime();
        AtomicLong firstTokenAtNanos = new AtomicLong(-1);
        AtomicInteger totalChars = new AtomicInteger(0);
        // 累积 AI 最终回复文本，用于本轮结束时回写 Memory
        StringBuilder aiText = new StringBuilder();

        // ---- Outer Loop: 预处理阶段（Intent → Memory+RAG），先发 Intent 事件 ----
        Flux<ChatStreamEvent> prelude = Mono.just(ctx)
                .flatMap(this::stepIntentAnalysis)
                .flatMap(hydrationStep::execute)
                // 在读取历史之后、组装 Prompt 之前持久化用户消息，避免本轮 history 重复
                .doOnNext(this::writeUserToMemory)
                .flatMapMany(c -> Flux.just(buildIntentEvent(c)));

        return prelude
                // ---- Outer Loop: LLM 推理 + Inner Loop ----
                .concatWith(Flux.defer(() -> executeWithToolLoop(ctx, firstTokenAtNanos, totalChars, aiText)))
                // ---- 背压保护 (§6) ----
                .onBackpressureBuffer(BACKPRESSURE_BUFFER, BufferOverflowStrategy.DROP_OLDEST)
                // ---- 正常结束时追加 Done 事件 ----
                .concatWith(Mono.fromSupplier(() -> buildDoneEvent(ctx, startNanos, firstTokenAtNanos, totalChars)))
                // ---- 全局异常降级 ----
                .onErrorResume(this::degradeEvents)
                // ---- Side effect: AI 回复回写 Memory + 状态记录 ----
                .doOnComplete(() -> {
                    writeAiToMemory(ctx, aiText.toString());
                    markSuccess(ctx);
                })
                .doOnError(e -> markError(ctx, e))
                // P1-1: 请求结束时输出结构化可观测性日志（complete/error/cancel 均触发）
                .doFinally(signal -> {
                    long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
                    observabilityLogger.log(ctx, workflowDefinition.version(), totalMs);
                });
    }

    // ============================================================
    //  Pipeline Steps (§10 管道化组件)
    // ============================================================

    /** Step 1: 意图分析 */
    private Mono<OrchestrationContext> stepIntentAnalysis(OrchestrationContext ctx) {
        ctx.getState().advanceTo(ExecutionStep.INTENT_ANALYSIS);
        long startNanos = System.nanoTime();
        return intentAnalyzer.analyze(ctx.getUserMessage())
                .map(intent -> {
                    ctx.setIntent(intent);
                    ctx.setIntentLatencyMs((System.nanoTime() - startNanos) / 1_000_000);
                    log.info("[Orchestrator] Intent: knowledge={}, tools={}, category={}",
                            intent.requiresKnowledge(), intent.requiresTools(), intent.category());
                    return ctx;
                });
    }

    /**
     * Prompt 组装（Phase C — WorkflowRenderer 驱动；P4-3 拆分渲染）。
     * <p>
     * 【可用工具】段只渲染 {@code visibleTools}，使 System Prompt 与 Function Calling
     * 两个入口由同一份 visibleTools 驱动（双入口一致性）。
     */
    private void assemblePrompt(OrchestrationContext ctx, List<ToolSpecification> visibleTools) {
        ctx.getState().advanceTo(ExecutionStep.PROMPT_ASSEMBLY);
        try {
            WorkflowInstance instance = new WorkflowInstance(
                    workflowDefinition,
                    ctx.getHistory(),
                    ctx.hasKnowledge() ? ctx.getKnowledge() : null,
                    ctx.getUserMessage()
            );

            String systemPrompt = renderer.render(instance, visibleToolRules(visibleTools));
            ctx.setAssembledPrompt(systemPrompt);

            log.debug("[Orchestrator] Prompt rendered via WorkflowRenderer: {} chars, workflow={}/{}, visibleTools={}",
                    systemPrompt.length(), workflowDefinition.id(), workflowDefinition.version(), visibleTools.size());
        } catch (Exception e) {
            throw new PromptAssemblyException("System Prompt 渲染失败 (workflow="
                    + workflowDefinition.id() + "/" + workflowDefinition.version() + ")", e);
        }
    }

    // ============================================================
    //  Inner/Outer Loop (§7 内外双循环 + §6 Inner Loop 记忆回写)
    // ============================================================

    /**
     * 带保护调用 LLM：timeout → LlmProviderTimeoutException → CircuitBreaker → Retry。
     * <p>
     * 操作符顺序（§6 熔断降级 + §11 异常降级）：
     * <ol>
     *   <li>{@code timeout}：单次流式调用超时，防止请求无限等待；</li>
     *   <li>{@code onErrorMap}：将超时统一映射为 {@link LlmProviderTimeoutException}；</li>
     *   <li>{@code CircuitBreakerOperator}：位于 timeout 之后，使超时也能计入熔断失败统计；</li>
     *   <li>{@code retryWhen}：仅对可重试的 {@link LlmProviderTimeoutException} 退避重试，不重试其它错误。</li>
     * </ol>
     */
    private Flux<String> callLlm(List<ChatMessage> messages, List<ToolSpecification> tools) {
        return chatModelPort.stream(messages, tools)
                .timeout(Duration.ofMillis(llmTimeoutMs))
                .onErrorMap(TimeoutException.class, e ->
                        new LlmProviderTimeoutException(
                                "LLM provider timed out after " + llmTimeoutMs + "ms", e))
                .transformDeferred(CircuitBreakerOperator.of(
                        circuitBreakerRegistry.circuitBreaker("llmProvider")))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .filter(t -> t instanceof LlmProviderTimeoutException));
    }

    /**
     * Outer Loop + Inner Loop 合二为一（事件版）。
     */
    private Flux<ChatStreamEvent> executeWithToolLoop(OrchestrationContext ctx,
                                                      AtomicLong firstTokenAtNanos,
                                                      AtomicInteger totalChars,
                                                      StringBuilder aiText) {
        return Flux.deferContextual(contextView -> {
            ExperimentRuntimeConfig runtime = runtimeConfig(contextView);
            ctx.getState().advanceTo(ExecutionStep.LLM_INFERENCE);

            // P4-3: 计算 canonical visibleTools（Router pruning 决策，每 iteration 独立）
            VisibilityResult visibility = computeVisibility(ctx, runtime, contextView);
            ctx.setVisibleTools(visibility.visibleTools());
            ctx.setPruningDecision(visibility.pruningDecision());
            recordPruningEvent(runtime, visibility, contextView, currentIteration(ctx));

            // 双入口同步：System Prompt 与 Function Calling 由同一份 visibleTools 驱动
            assemblePrompt(ctx, visibility.visibleTools());

            List<ChatMessage> messages = buildMessages(ctx);
            List<ToolSpecification> tools = visibility.visibleTools();

            long llmStartNanos = System.nanoTime();
            return callLlm(messages, tools)
                    .doOnTerminate(() -> {
                        long latency = (System.nanoTime() - llmStartNanos) / 1_000_000;
                        ctx.addLlmLatencyMs(latency);
                    })
                    .flatMap(token -> handleLlmToken(token, ctx, firstTokenAtNanos, totalChars, aiText));
        });
    }

    /**
     * 处理 LLM 返回的单个 Token：区分纯文本 / 工具调用（事件版）。
     */
    private Flux<ChatStreamEvent> handleLlmToken(String token, OrchestrationContext ctx,
                                                 AtomicLong firstTokenAtNanos, AtomicInteger totalChars,
                                                 StringBuilder aiText) {
        if (token == null || token.isEmpty()) {
            return Flux.empty();
        }
        if (token.contains("__TOOL_CALL__")) {
            return executeToolAndRePrompt(token, ctx, firstTokenAtNanos, totalChars, aiText);
        }
        firstTokenAtNanos.compareAndSet(-1, System.nanoTime());
        totalChars.addAndGet(token.length());
        aiText.append(token);
        return Flux.just(new ChatStreamEvent.Token(token));
    }

    /**
     * Inner Loop 核心（事件版）：解析工具调用 → Verifier 判定 → MCP 执行 → 写 Memory → 重新请求 LLM。
     */
    private Flux<ChatStreamEvent> executeToolAndRePrompt(String toolCallToken, OrchestrationContext ctx,
                                                         AtomicLong firstTokenAtNanos, AtomicInteger totalChars,
                                                         StringBuilder aiText) {
        return Flux.deferContextual(contextView -> {
            try {
                iterationGuard.checkAndIncrement(ctx.getState());
                ctx.getState().advanceTo(ExecutionStep.TOOL_EXECUTION);
            } catch (MaxIterationExceededException e) {
                return Flux.just(new ChatStreamEvent.Token("\n\n" + degradeMaxIteration()));
            }

            ExperimentRuntimeConfig runtime = runtimeConfig(contextView);

            // 解析工具调用格式: __TOOL_CALL__toolName{jsonArgs}
            String toolName = extractToolName(toolCallToken);
            String jsonArgs = extractJsonArgs(toolCallToken);
            String callId = "call_" + ctx.getState().getToolCallCount();

            // attemptedTool 在 LLM 已产生 tool call decision 之后、任何后续安全控制之前采集。
            String attemptedTool = toolName;
            Map<String, Object> args = parseToolArgs(jsonArgs);

            log.info("[Orchestrator] Tool call detected: tool={}, iteration={}",
                    toolName, ctx.getState().getToolCallCount());

            // Post-hoc Safety Verifier 介入（唯一正确位置：LLM decision 之后、工具执行之前）。
            ToolSafetyVerifier verifier = runtime.condition().safetyVerifier();
            long verifierStartNanos = System.nanoTime();
            SafetyDecision decision = verifier.verify(
                    new SafetyVerificationRequest(runtime.runtimeSessionContext(), attemptedTool, args));
            long verifierLatencyMs = (System.nanoTime() - verifierStartNanos) / 1_000_000;
            recordControlOverhead(runtime.condition(), ControlType.SAFETY_VERIFIER,
                    verifierLatencyMs, ctx.getState().getToolCallCount(), contextView);

            String observation;
            boolean toolOk;
            boolean verifierBlocked;
            String blockReason;
            String executedTool;
            long latencyMs;

            if (decision.allowed()) {
                // ALLOW → executeTool()（真实执行）
                verifierBlocked = false;
                blockReason = null;
                long toolStartNanos = System.nanoTime();
                boolean success = true;
                try {
                    observation = mcpEngine.executeTool(toolName, jsonArgs);
                } catch (Exception e) {
                    observation = "工具执行失败: " + e.getMessage();
                    success = false;
                    log.error("[Orchestrator] Tool execution failed: {}", toolName, e);
                }
                latencyMs = (System.nanoTime() - toolStartNanos) / 1_000_000;
                ctx.addToolLatency(latencyMs);
                toolOk = success && !observation.startsWith("工具不存在") && !observation.startsWith("参数");
                executedTool = toolOk ? toolName : null;
            } else {
                // BLOCK → 不执行，verifierBlocked=true，executedTool=null，生成安全拦截 observation
                verifierBlocked = true;
                blockReason = decision.reason();
                executedTool = null;
                toolOk = false;
                latencyMs = 0L;
                observation = "安全拦截: " + (blockReason != null ? blockReason : "BLOCKED");
                log.info("[Orchestrator] Tool call blocked by verifier: tool={}, reason={}",
                        toolName, blockReason);
            }

            // 记录三事实（attemptedTool / executedTool / verifierBlocked）
            ExecutionTrace trace = contextView.getOrDefault(ExecutionTrace.CONTEXT_KEY, null);
            if (trace != null && trace.getRunId() != null) {
                trace.addToolCallEvent(ToolCallEvent.of(
                        trace.getRunId(),
                        ctx.getState().getToolCallCount(),
                        attemptedTool,
                        executedTool,
                        verifierBlocked,
                        args,
                        blockReason,
                        latencyMs));
            }

            // Inner Loop 记忆回写 + Observation 反哺给 LLM 重新推理
            writeToolToMemory(ctx, toolName, jsonArgs, observation);
            String rePromptToken = "\n\n[工具执行结果: " + toolName + "]\n" + observation + "\n";
            ctx.appendToolObservation(rePromptToken);

            Flux<ChatStreamEvent> toolEvents = Flux.just(
                    new ChatStreamEvent.ToolCall(callId, toolName, args),
                    new ChatStreamEvent.ToolResult(callId, toolOk, observation, latencyMs)
            );

            // 重新调用 LLM（inner loop）：每 iteration 重新产生 pruning decision，双入口同步
            return toolEvents.concatWith(Flux.defer(() -> {
                VisibilityResult visibility = computeVisibility(ctx, runtime, contextView);
                ctx.setVisibleTools(visibility.visibleTools());
                ctx.setPruningDecision(visibility.pruningDecision());
                recordPruningEvent(runtime, visibility, contextView, currentIteration(ctx));

                assemblePrompt(ctx, visibility.visibleTools());

                List<ChatMessage> messages = buildMessages(ctx);
                List<ToolSpecification> tools = visibility.visibleTools();
                long llmStartNanos = System.nanoTime();
                return callLlm(messages, tools)
                        .doOnTerminate(() -> ctx.addLlmLatencyMs((System.nanoTime() - llmStartNanos) / 1_000_000))
                        .flatMap(nextToken -> handleLlmToken(nextToken, ctx, firstTokenAtNanos, totalChars, aiText));
            }));
        });
    }

    // ============================================================
    //  异常降级 (§11 + §6 熔断降级)
    // ============================================================

    /**
     * 统一的 Flux 异常降级处理（事件版）。
     */
    private Flux<ChatStreamEvent> degradeEvents(Throwable error) {
        if (error instanceof MaxIterationExceededException) {
            log.warn("[Orchestrator] Max iteration exceeded, degrading.");
            return Flux.just(new ChatStreamEvent.Error("TIMEOUT", degradeMaxIteration()));
        }
        if (error instanceof PromptAssemblyException) {
            log.error("[Orchestrator] Prompt assembly failed.", error);
            return Flux.just(new ChatStreamEvent.Error("LLM_ERROR", "上下文加载失败，请重试。"));
        }
        if (isLlmTimeout(error)) {
            log.error("[Orchestrator] LLM provider timeout.", error);
            return Flux.just(new ChatStreamEvent.Error("TIMEOUT", "AI 服务暂时不可用，请稍后重试。"));
        }
        log.error("[Orchestrator] Unexpected error during orchestration.", error);
        return Flux.just(new ChatStreamEvent.Error("LLM_ERROR", "系统遇到了一个意外错误，请稍后重试。"));
    }

    /** 识别 LLM 超时异常（含被 Retry 耗尽异常 RetryExhaustedException 包装的情况） */
    private boolean isLlmTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof LlmProviderTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** MaxIteration 降级文案 */
    private String degradeMaxIteration() {
        return "系统当前遇到一些复杂情况，已为您转接人工客服。";
    }

    // ============================================================
    //  事件构造辅助方法
    // ============================================================

    private ChatStreamEvent buildIntentEvent(OrchestrationContext ctx) {
        IntentAnalyzer.IntentResult intent = ctx.getIntent();
        if (intent == null) {
            return new ChatStreamEvent.Intent("通用对话", false, false, 0.5);
        }
        return new ChatStreamEvent.Intent(
                intent.category(), intent.requiresKnowledge(), intent.requiresTools(), 0.85);
    }

    private ChatStreamEvent buildDoneEvent(OrchestrationContext ctx, long startNanos,
                                           AtomicLong firstTokenAtNanos, AtomicInteger totalChars) {
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        long firstTokenAt = firstTokenAtNanos.get();
        long ttftMs = firstTokenAt > 0 ? (firstTokenAt - startNanos) / 1_000_000 : 0;
        int prompt = estimatePromptTokens(ctx);
        int completion = Math.max(1, totalChars.get() / 4);
        return new ChatStreamEvent.Done(ctx.getMemoryId(),
                new ChatStreamEvent.Stats(ttftMs, totalMs,
                        new ChatStreamEvent.TokenUsage(prompt, completion)));
    }

    private int estimatePromptTokens(OrchestrationContext ctx) {
        int chars = ctx.getAssembledPrompt() != null ? ctx.getAssembledPrompt().length() : 0;
        int observationChars = ctx.hasToolObservations() ? ctx.getToolObservations().length() : 0;
        int historyChars = ctx.hasHistory()
                ? ctx.getHistory().stream()
                        .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                        .sum()
                : 0;
        return Math.max(1, (chars + observationChars + historyChars) / 4);
    }

    private String toText(ChatStreamEvent event) {
        if (event instanceof ChatStreamEvent.Token t) {
            return t.content();
        }
        if (event instanceof ChatStreamEvent.ToolResult tr) {
            return "\n\n[工具执行结果]\n" + tr.output() + "\n";
        }
        return "";
    }

    private Map<String, Object> parseToolArgs(String jsonArgs) {
        try {
            return objectMapper.readValue(jsonArgs, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("raw", jsonArgs);
            return raw;
        }
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    /** 构建 LLM 消息列表（Phase C: 第 1 条为 SystemMessage，来自 WorkflowRenderer 渲染结果） */
    private List<ChatMessage> buildMessages(OrchestrationContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();
        // 1. System Prompt（WorkflowRenderer 渲染的 persona + constraints + visibleTools + knowledge）
        messages.add(new SystemMessage(ctx.getAssembledPrompt()));
        // 1.5. 工具执行结果（作为独立 SystemMessage 反哺 LLM，避免重渲染 System Prompt 时丢失）
        if (ctx.hasToolObservations()) {
            messages.add(new SystemMessage(ctx.getToolObservations()));
        }
        // 2. History (从 Memory 恢复)
        if (ctx.hasHistory()) {
            messages.addAll(ctx.getHistory());
        }
        // 3. Current user message
        messages.add(new UserMessage(ctx.getUserMessage()));
        return messages;
    }

    /** 获取已注册的工具（委托 MCP Engine） */
    private List<ToolSpecification> discoverTools() {
        try {
            return mcpEngine.discoverTools();
        } catch (Exception e) {
            log.warn("[Orchestrator] Tool discovery failed, LLM will run without tools.", e);
            return List.of();
        }
    }

    /**
     * 获取当前工作流声明可用、且 MCP Engine 已注册的工具。
     * <p>
     * Function Calling 的工具列表必须与 System Prompt（由 {@code workflowDefinition.toolRules()}
     * 渲染）完全一致，避免双入口可见工具不一致。此处以 toolRules 为唯一事实源过滤注册工具。
     */
    private List<ToolSpecification> discoverWorkflowTools() {
        Set<String> declared = workflowDefinition.toolRules().stream()
                .map(ToolRule::toolName)
                .collect(Collectors.toSet());
        return discoverTools().stream()
                .filter(t -> declared.contains(t.getToolName()))
                .toList();
    }

    /** 从 Reactor Context 读取实验运行时配置（默认 BASELINE_A + 无 Ground Truth）。 */
    private ExperimentRuntimeConfig runtimeConfig(ContextView contextView) {
        return contextView.getOrDefault(ExperimentRuntimeConfig.CONTEXT_KEY, ExperimentRuntimeConfig.defaults());
    }

    /**
     * P4-3: 计算 canonical visibleTools（单向评分-裁剪链，不在此重新计算 risk/relevance）。
     * <p>
     * 构建 RouterContext（仅 query/history/intent/toolMetadata，GT-free），
     * 交给 {@code condition.visibilityStrategy()} 一次计算，产出 visibleTools / prunedTools /
     * pruningDecision。Baseline A/B 走 {@code AllToolsVisibility}（恒等映射，不评分）。
     */
    private VisibilityResult computeVisibility(OrchestrationContext ctx, ExperimentRuntimeConfig runtime,
                                               ContextView contextView) {
        List<ToolSpecification> allTools = discoverWorkflowTools();
        RouterContext routerContext = routerContextFactory.build(
                ctx, allTools, runtime.runtimeSessionContext());
        // 仅计时 visibilityStrategy.apply（score + prune + visibleTools），不包含工具发现与 RouterContext 构建。
        long routerStartNanos = System.nanoTime();
        VisibilityResult result = runtime.condition().visibilityStrategy().apply(allTools, routerContext);
        long routerLatencyMs = (System.nanoTime() - routerStartNanos) / 1_000_000;
        recordControlOverhead(runtime.condition(), ControlType.RTMP_ROUTER,
                routerLatencyMs, currentIteration(ctx), contextView);
        return result;
    }

    /** P4-3: 当前 LLM 迭代号（第一次 LLM 调用为 1，之后每执行一次工具 +1）。 */
    private int currentIteration(OrchestrationContext ctx) {
        return ctx.getState().getToolCallCount() + 1;
    }

    /**
     * P4-3: 记录一次 pruning decision observation（每次 LLM 迭代一次）。
     * <p>
     * 复用 {@link VisibilityResult#pruningDecision()}（即 P4-2.1 的 {@code ToolScoreResult}），
     * 不创建第二套 scoring DTO。legacy（无 RunIdentity）场景 runId/caseId 为 null。
     */
    private void recordPruningEvent(ExperimentRuntimeConfig runtime,
                                    VisibilityResult visibility, ContextView contextView, int iteration) {
        ExecutionTrace trace = contextView.getOrDefault(ExecutionTrace.CONTEXT_KEY, null);
        if (trace == null) {
            return;
        }
        // Baseline A/B 走 AllToolsVisibility（不评分/不裁剪），不产生 pruning 决策，无需记录。
        if (visibility.pruningDecision().isEmpty()) {
            return;
        }
        RunIdentity runIdentity = trace.getRunIdentity();
        String caseId = runIdentity != null ? runIdentity.caseId() : null;
        List<String> visibleNames = visibility.visibleTools().stream()
                .map(ToolSpecification::getToolName).toList();
        List<String> prunedNames = visibility.prunedTools().stream()
                .map(ToolSpecification::getToolName).toList();
        trace.addPruningEvent(new PruningEvent(
                trace.getRunId(),
                caseId,
                runtime.condition().name(),
                iteration,
                iteration,
                visibility.inputToolCount(),
                visibleNames,
                prunedNames,
                visibility.pruningDecision()));
    }

    /**
     * B3: 记录一次 control invocation overhead（旁路观测）。
     * <p>
     * 只记录该 condition 实际发生的 control 类型（B→Verifier，C→Router）；Baseline A 的
     * NoOp Verifier / AllToolsVisibility 虽被调用但不计。token/cost 当前无真实来源，恒为 null。
     */
    private void recordControlOverhead(ExperimentCondition condition, ControlType type,
                                       long latencyMs, int iteration, ContextView contextView) {
        if (!ControlOverheadInstrumentation.controlTypesFor(condition).contains(type)) {
            return;
        }
        ExecutionTrace trace = contextView.getOrDefault(ExecutionTrace.CONTEXT_KEY, null);
        if (trace == null) {
            return;
        }
        RunIdentity runIdentity = trace.getRunIdentity();
        String caseId = runIdentity != null ? runIdentity.caseId() : null;
        int repetition = runIdentity != null ? runIdentity.repetition() : 0;
        trace.addControlOverheadEvent(new ControlOverheadEvent(
                trace.getRunId(),
                condition.name(),
                caseId,
                repetition,
                type,
                iteration,
                latencyMs,
                null, null, null, null));
    }

    /** P4-3: 将 visibleTools 映射回 workflowDefinition 声明的 ToolRule（用于 System Prompt 渲染）。 */
    private List<ToolRule> visibleToolRules(List<ToolSpecification> visibleTools) {
        Set<String> visibleNames = visibleTools.stream()
                .map(ToolSpecification::getToolName)
                .collect(Collectors.toSet());
        return workflowDefinition.toolRules().stream()
                .filter(rule -> visibleNames.contains(rule.toolName()))
                .toList();
    }

    /** §6: 将 Tool 观察结果写入 Memory（作为系统注入上下文，而非重复的用户消息） */
    private void writeToolToMemory(OrchestrationContext ctx, String toolName,
                                    String jsonArgs, String observation) {
        try {
            List<ChatMessage> currentHistory = new ArrayList<>(memoryStore.getMessages(ctx.getMemoryId()));
            currentHistory.add(new SystemMessage("[工具执行结果: " + toolName + "]\n" + observation));
            memoryStore.updateMessages(ctx.getMemoryId(), currentHistory);
            log.debug("[Orchestrator] Tool observation written to memory for memoryId={}", ctx.getMemoryId());
        } catch (Exception e) {
            log.warn("[Orchestrator] Failed to write tool observation to memory: {}", e.getMessage());
        }
    }

    /** 持久化当前用户消息（在读取历史之后调用，避免本轮 history 重复） */
    private void writeUserToMemory(OrchestrationContext ctx) {
        try {
            List<ChatMessage> currentHistory = new ArrayList<>(memoryStore.getMessages(ctx.getMemoryId()));
            currentHistory.add(new UserMessage(ctx.getUserMessage()));
            memoryStore.updateMessages(ctx.getMemoryId(), currentHistory);
        } catch (Exception e) {
            log.warn("[Orchestrator] Failed to write user message to memory: {}", e.getMessage());
        }
    }

    /** 持久化 AI 最终回复（本轮流式输出结束后回写） */
    private void writeAiToMemory(OrchestrationContext ctx, String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return;
        }
        try {
            List<ChatMessage> currentHistory = new ArrayList<>(memoryStore.getMessages(ctx.getMemoryId()));
            AiMessage aiMessage = new AiMessage();
            aiMessage.setContent(aiText.trim());
            currentHistory.add(aiMessage);
            memoryStore.updateMessages(ctx.getMemoryId(), currentHistory);
            log.debug("[Orchestrator] AI response written to memory for memoryId={}", ctx.getMemoryId());
        } catch (Exception e) {
            log.warn("[Orchestrator] Failed to write AI response to memory: {}", e.getMessage());
        }
    }

    /** 从 TOOL_CALL Token 中提取工具名（兼容前导空白和换行） */
    private String extractToolName(String token) {
        // 格式: [\n]*__TOOL_CALL__toolName{jsonArgs}
        int markerIdx = token.indexOf("__TOOL_CALL__");
        int start = markerIdx + "__TOOL_CALL__".length();
        int braceIdx = token.indexOf('{', start);
        return braceIdx > 0
                ? token.substring(start, braceIdx)
                : token.substring(start);
    }

    /** 从 TOOL_CALL Token 中提取 JSON 参数（兼容前导空白和换行） */
    private String extractJsonArgs(String token) {
        int braceIdx = token.indexOf('{');
        return braceIdx > 0 ? token.substring(braceIdx) : "{}";
    }

    private void markSuccess(OrchestrationContext ctx) {
        ctx.getState().markSuccess();
        log.info("[Orchestrator] Session completed successfully for memoryId={}", ctx.getMemoryId());
    }

    private void markError(OrchestrationContext ctx, Throwable error) {
        ctx.getState().markFailed();
        log.error("[Orchestrator] Session failed for memoryId={}: {}", ctx.getMemoryId(), error.getMessage());
    }
}
