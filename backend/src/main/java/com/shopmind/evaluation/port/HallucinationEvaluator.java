package com.shopmind.evaluation.port;

import com.shopmind.knowledge.model.RetrievedContext;
import reactor.core.publisher.Mono;

/**
 * 幻觉检测器接口 — 6_Evaluation_Engine.md §6.3 规范（v2.1 异步化）。
 * <p>
 * 判断 Agent 最终回答中是否捏造了 RAG 知识库之外的事实。
 * 支持两种策略的多态实现：
 * <ul>
 *   <li><b>RuleBasedHallucinationJudge</b> — 基于规则（关键词白名单/正则匹配），
 *       纯 CPU 计算，返回 {@code Mono.just(result)}</li>
 *   <li><b>LlmAsAJudgeHallucinationJudge</b> — 调用独立的大模型裁判 API 进行语义判断，
 *       真正的异步 I/O，必须通过非阻塞 HTTP 客户端（WebClient）实现</li>
 * </ul>
 * <p>
 * <b>异步约束（v2.1 核心修复）：</b>接口返回 {@link Mono}{@code <Boolean>} 而非同步的 boolean。
 * 这是因为 {@code LlmAsAJudge} 实现需要调用 LLM API（网络 I/O），
 * 同步签名会阻塞 Reactor Netty event loop 线程，导致线程池饥饿。
 * <p>
 * <b>线程安全：</b>实现类应为无状态 {@code @Component} 单例。
 * {@code LlmAsAJudge} 实现内部使用 {@code WebClient}（非阻塞）。
 */
@FunctionalInterface
public interface HallucinationEvaluator {

    /**
     * 判断 Agent 回答是否包含幻觉。
     *
     * @param answer  Agent 最终输出的完整文本
     * @param context RAG 引擎召回的知识片段（作为 Ground Truth 参照）
     * @return Mono&lt;Boolean&gt; true = 存在幻觉（捏造了 context 之外的事实）
     */
    Mono<Boolean> isHallucinated(String answer, RetrievedContext context);
}
