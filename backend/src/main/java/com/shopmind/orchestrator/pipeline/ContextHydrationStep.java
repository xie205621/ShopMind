package com.shopmind.orchestrator.pipeline;

import com.shopmind.knowledge.api.KnowledgeRetriever;
import com.shopmind.knowledge.model.QueryRequest;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.orchestrator.domain.ExecutionStep;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import com.shopmind.orchestrator.port.PipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 上下文水合步骤 — 并行加载 Memory + RAG（§6 TTFT 优化 + §7 外部循环）。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li>使用 {@code Mono.zip(memoryMono, ragMono)} 并行获取 Memory 和 Knowledge，
 *       而非串行调用。将 TTFT 从 Memory + RAG 的串行耗时压缩为 max(Memory, RAG)。</li>
 *   <li>Memory 不可用时返回空列表（Memory Engine 自身已有降级策略），不中断主链路。</li>
 *   <li>RAG 失败时跳过知识检索，ctx.getKnowledge() 保持为 null。</li>
 * </ul>
 * <p>
 * 线程安全：无状态 @Component 单例，所有状态均通过 OrchestrationContext 参数传递。
 */
@Component
public class ContextHydrationStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ContextHydrationStep.class);

    private final ChatMemoryStore memoryStore;
    private final KnowledgeRetriever knowledgeRetriever;

    public ContextHydrationStep(ChatMemoryStore memoryStore,
                                 KnowledgeRetriever knowledgeRetriever) {
        this.memoryStore = memoryStore;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    @Override
    public Mono<OrchestrationContext> execute(OrchestrationContext ctx) {
        ctx.getState().advanceTo(ExecutionStep.MEMORY_LOADING);
        log.debug("[Orchestrator] ContextHydration: loading memory + knowledge in parallel for memoryId={}",
                ctx.getMemoryId());

        // ---- Mono 1: Memory 加载（异步，失败返回空列表） ----
        Mono<List<ChatMessage>> memoryMono = Mono.fromCallable(() ->
                        memoryStore.getMessages(ctx.getMemoryId()))
                .onErrorResume(e -> {
                    log.warn("[Orchestrator] Memory load failed for memoryId={}, continue with empty history. "
                            + "Error: {}", ctx.getMemoryId(), e.getMessage());
                    return Mono.just(List.<ChatMessage>of());
                })
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> {
                    log.warn("[Orchestrator] Memory load timed out for memoryId={}", ctx.getMemoryId());
                    return Mono.just(List.<ChatMessage>of());
                });

        // ---- Mono 2: RAG 加载（异步，失败返回 null） ----
        Mono<RetrievedContext> ragMono = Mono.fromCallable(() ->
                        knowledgeRetriever.retrieve(
                                QueryRequest.builder()
                                        .query(ctx.getUserMessage())
                                        .topK(3)
                                        .scoreThreshold(0.7)
                                        .build()
                        ))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("[Orchestrator] RAG retrieval failed for query='{}', continue without knowledge. "
                            + "Error: {}", ctx.getUserMessage(), e.getMessage());
                    return Mono.just(RetrievedContext.builder().build()); // empty
                })
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.warn("[Orchestrator] RAG retrieval timed out for query='{}'", ctx.getUserMessage());
                    return Mono.just(RetrievedContext.builder().build());
                });

        // ---- Mono.zip: 并行执行两个异步任务 ----
        return Mono.zip(memoryMono, ragMono)
                .map(tuple -> {
                    ctx.setHistory(tuple.getT1());
                    RetrievedContext knowledge = tuple.getT2();
                    ctx.setKnowledge(knowledge.hasResults() ? knowledge : null);
                    ctx.getState().advanceTo(ExecutionStep.PROMPT_ASSEMBLY);
                    log.info("[Orchestrator] Context hydrated: {} history msgs, {} knowledge chunks",
                            ctx.getHistory().size(),
                            ctx.hasKnowledge() ? ctx.getKnowledge().getChunks().size() : 0);
                    return ctx;
                });
    }
}
