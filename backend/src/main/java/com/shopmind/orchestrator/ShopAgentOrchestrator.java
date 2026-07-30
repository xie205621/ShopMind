package com.shopmind.orchestrator;

import com.shopmind.mcp.McpEngine;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.SystemMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.ExecutionStatus;
import com.shopmind.orchestrator.domain.ExecutionStep;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.exception.LlmProviderTimeoutException;
import com.shopmind.orchestrator.exception.MaxIterationExceededException;
import com.shopmind.orchestrator.exception.PromptAssemblyException;
import com.shopmind.orchestrator.pipeline.ContextHydrationStep;
import com.shopmind.orchestrator.pipeline.ToolIterationGuard;
import com.shopmind.orchestrator.port.AgentOrchestrator;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.orchestrator.port.IntentAnalyzer;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowInstance;
import com.shopmind.workflow.pipeline.WorkflowDefinitionLoader;
import com.shopmind.workflow.port.WorkflowRenderer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
public class ShopAgentOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ShopAgentOrchestrator.class);

    /** 背压缓冲区大小 */
    private static final int BACKPRESSURE_BUFFER = 256;

    /** 默认生产环境工作流定义 */
    private static final String DEFAULT_WORKFLOW_ID = "customer-service";
    private static final String DEFAULT_WORKFLOW_VERSION = "v2.0";

    private final IntentAnalyzer intentAnalyzer;
    private final ContextHydrationStep hydrationStep;
    private final ChatModelPort chatModelPort;
    private final McpEngine mcpEngine;
    private final ChatMemoryStore memoryStore;
    private final ToolIterationGuard iterationGuard;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** Phase C: 工作流渲染器（纯函数，将 WorkflowInstance → System Prompt） */
    private final WorkflowRenderer renderer;

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
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.intentAnalyzer = intentAnalyzer;
        this.hydrationStep = hydrationStep;
        this.renderer = renderer;
        this.chatModelPort = chatModelPort;
        this.mcpEngine = mcpEngine;
        this.memoryStore = memoryStore;
        this.iterationGuard = iterationGuard;
        this.circuitBreakerRegistry = circuitBreakerRegistry;

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
    //  chat — 核心入口（§9 API Design）
    // ============================================================

    @Override
    public Flux<String> chat(OrchestrationRequest request) {
        // 1. 创建请求级上下文（每次请求都是新实例，不会跨线程共享）
        OrchestrationContext ctx = new OrchestrationContext(request.memoryId(), request.userMessage());

        return Mono.just(ctx)
                // ---- Outer Loop: 预处理阶段 ----
                .flatMap(this::stepIntentAnalysis)
                .flatMap(hydrationStep::execute)    // §6: Mono.zip 并行加载
                .map(this::stepPromptAssembly)
                // ---- Outer Loop: LLM 推理 + Inner Loop ----
                .flatMapMany(this::executeWithToolLoop)
                // ---- 背压保护 (§6) ----
                .onBackpressureBuffer(BACKPRESSURE_BUFFER, BufferOverflowStrategy.DROP_OLDEST)
                // ---- 全局异常降级 ----
                .onErrorResume(this::degradeFlux)
                // ---- Side effect: 成功后写 Memory ----
                .doOnComplete(() -> markSuccess(ctx))
                .doOnError(e -> markError(ctx, e));
    }

    // ============================================================
    //  Pipeline Steps (§10 管道化组件)
    // ============================================================

    /** Step 1: 意图分析 */
    private Mono<OrchestrationContext> stepIntentAnalysis(OrchestrationContext ctx) {
        ctx.getState().advanceTo(ExecutionStep.INTENT_ANALYSIS);
        return intentAnalyzer.analyze(ctx.getUserMessage())
                .map(intent -> {
                    log.info("[Orchestrator] Intent: knowledge={}, tools={}, category={}",
                            intent.requiresKnowledge(), intent.requiresTools(), intent.category());
                    return ctx;
                });
    }

    /**
     * Step 3: Prompt 组装（Phase C — WorkflowRenderer 驱动）。
     * <p>
     * 构建 {@link WorkflowInstance}（定义 + 运行时数据），
     * 委托 {@link WorkflowRenderer#render(WorkflowInstance)} 生成完整 System Prompt。
     * 渲染结果存储到 ctx.assembledPrompt，供 {@link #buildMessages(OrchestrationContext)} 使用。
     */
    private OrchestrationContext stepPromptAssembly(OrchestrationContext ctx) {
        ctx.getState().advanceTo(ExecutionStep.PROMPT_ASSEMBLY);
        try {
            WorkflowInstance instance = new WorkflowInstance(
                    workflowDefinition,
                    ctx.getHistory(),
                    ctx.hasKnowledge() ? ctx.getKnowledge() : null,
                    ctx.getUserMessage()
            );

            String systemPrompt = renderer.render(instance);
            ctx.setAssembledPrompt(systemPrompt);

            log.debug("[Orchestrator] Prompt rendered via WorkflowRenderer: {} chars, workflow={}/{}",
                    systemPrompt.length(), workflowDefinition.id(), workflowDefinition.version());
        } catch (Exception e) {
            throw new PromptAssemblyException("System Prompt 渲染失败 (workflow="
                    + workflowDefinition.id() + "/" + workflowDefinition.version() + ")", e);
        }
        return ctx;
    }

    // ============================================================
    //  Inner/Outer Loop (§7 内外双循环 + §6 Inner Loop 记忆回写)
    // ============================================================

    /**
     * Outer Loop + Inner Loop 合二为一。
     * <p>
     * Outer: 请求 LLM 推理。<br>
     * Inner: 当 LLM 返回工具调用时，执行业务、写 Memory、重试 LLM（最多 3 次）。
     */
    private Flux<String> executeWithToolLoop(OrchestrationContext ctx) {
        return Flux.defer(() -> {
            ctx.getState().advanceTo(ExecutionStep.LLM_INFERENCE);

            // 构建消息列表
            List<ChatMessage> messages = buildMessages(ctx);
            List<ToolSpecification> tools = discoverTools();

            // LLM 调用（CircuitBreaker 保护）
            return chatModelPort.stream(messages, tools)
                    .transformDeferred(CircuitBreakerOperator.of(
                            circuitBreakerRegistry.circuitBreaker("llmProvider")))
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                            .filter(t -> t instanceof LlmProviderTimeoutException))
                    .flatMap(token -> handleLlmToken(token, ctx, true));
        });
    }

    /**
     * 处理 LLM 返回的单个 Token：区分纯文本 / 工具调用。
     *
     * @param token     LLM 输出的文本片段
     * @param ctx       当前上下文
     * @param firstPass 是否为首轮 LLM 调用（用于 inner loop 递归控制）
     */
    private Flux<String> handleLlmToken(String token, OrchestrationContext ctx, boolean firstPass) {
        // 检测工具调用标记（使用 contains 兼容 Markdown/换行前缀）
        if (token.contains("__TOOL_CALL__")) {
            return executeToolAndRePrompt(token, ctx);
        }
        // 纯文本 → 直接输出
        return Flux.just(token);
    }

    /**
     * Inner Loop 核心：解析工具调用 → MCP 执行 → 写 Memory → 重新请求 LLM。
     * 递归执行，最多 3 次（由 ToolIterationGuard 控制）。
     */
    private Flux<String> executeToolAndRePrompt(String toolCallToken, OrchestrationContext ctx) {
        try {
            iterationGuard.checkAndIncrement(ctx.getState());
            ctx.getState().advanceTo(ExecutionStep.TOOL_EXECUTION);
        } catch (MaxIterationExceededException e) {
            return Flux.just("\n\n" + degradeMaxIteration());
        }

        // 解析工具调用格式: __TOOL_CALL__toolName{jsonArgs}
        String toolName = extractToolName(toolCallToken);
        String jsonArgs = extractJsonArgs(toolCallToken);

        log.info("[Orchestrator] Tool call detected: tool={}, iteration={}",
                toolName, ctx.getState().getToolCallCount());

        // 1. MCP Engine 执行工具
        String observation;
        try {
            observation = mcpEngine.executeTool(toolName, jsonArgs);
        } catch (Exception e) {
            observation = "工具执行失败: " + e.getMessage();
            log.error("[Orchestrator] Tool execution failed: {}", toolName, e);
        }

        // 2. §6: Inner Loop 记忆回写 — 把 ToolCall + Observation 写入 Memory
        writeToolToMemory(ctx, toolName, jsonArgs, observation);

        // 3. Observation 反哺给 LLM 重新推理
        String rePromptToken = "\n\n[工具执行结果: " + toolName + "]\n" + observation + "\n";
        // 递归：将 observation 作为新的 user message 追加到上下文
        ctx.setAssembledPrompt(ctx.getAssembledPrompt() + rePromptToken);

        // 重新调用 LLM（不通过 outer loop，直接在 inner loop 中递归）
        return Flux.just(rePromptToken)
                .concatWith(Flux.defer(() -> {
                    List<ChatMessage> messages = buildMessages(ctx);
                    List<ToolSpecification> tools = discoverTools();
                    return chatModelPort.stream(messages, tools)
                            .transformDeferred(CircuitBreakerOperator.of(
                                    circuitBreakerRegistry.circuitBreaker("llmProvider")))
                            .flatMap(nextToken -> handleLlmToken(nextToken, ctx, false));
                }));
    }

    // ============================================================
    //  异常降级 (§11 + §6 熔断降级)
    // ============================================================

    /**
     * 统一的 Flux 异常降级处理。
     * PromptsAssembly、MaxIteration、LLM 超时均通过此方法转为用户友好提示。
     */
    private Flux<String> degradeFlux(Throwable error) {
        if (error instanceof MaxIterationExceededException) {
            log.warn("[Orchestrator] Max iteration exceeded, degrading.");
            return Flux.just(degradeMaxIteration());
        }
        if (error instanceof PromptAssemblyException) {
            log.error("[Orchestrator] Prompt assembly failed.", error);
            return Flux.just("上下文加载失败，请重试。");
        }
        if (error instanceof LlmProviderTimeoutException) {
            log.error("[Orchestrator] LLM provider timeout.", error);
            return Flux.just("AI 服务暂时不可用，请稍后重试。");
        }
        // 未知异常：打出 error 日志但向前端返回友好降级
        log.error("[Orchestrator] Unexpected error during orchestration.", error);
        return Flux.just("系统遇到了一个意外错误，请稍后重试。");
    }

    /** MaxIteration 降级文案 */
    private String degradeMaxIteration() {
        return "系统当前遇到一些复杂情况，已为您转接人工客服。";
    }

    /** CircuitBreaker 熔断降级（当 llmProvider 断路器打开时触发） */
    private Flux<String> circuitBreakerFallback(Throwable t) {
        log.warn("[Orchestrator] LLM CircuitBreaker OPEN, serving fallback response.");
        return Flux.just("AI 服务暂时繁忙，请稍后再试。");
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    /** 构建 LLM 消息列表（Phase C: 第 1 条为 SystemMessage，来自 WorkflowRenderer 渲染结果） */
    private List<ChatMessage> buildMessages(OrchestrationContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();
        // 1. System Prompt（WorkflowRenderer 渲染的 persona + constraints + toolRules + knowledge）
        messages.add(new SystemMessage(ctx.getAssembledPrompt()));
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

    /** §6: 将 ToolCall + Observation 写入 Memory */
    private void writeToolToMemory(OrchestrationContext ctx, String toolName,
                                    String jsonArgs, String observation) {
        try {
            // getMessages 可能返回不可变空列表，必须 new ArrayList 包装
            List<ChatMessage> currentHistory = new ArrayList<>(memoryStore.getMessages(ctx.getMemoryId()));
            currentHistory.add(new UserMessage(ctx.getUserMessage()));
            memoryStore.updateMessages(ctx.getMemoryId(), currentHistory);
            log.debug("[Orchestrator] Tool call result written to memory for memoryId={}", ctx.getMemoryId());
        } catch (Exception e) {
            log.warn("[Orchestrator] Failed to write tool call to memory: {}", e.getMessage());
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
