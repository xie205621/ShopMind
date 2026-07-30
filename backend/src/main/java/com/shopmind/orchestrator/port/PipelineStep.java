package com.shopmind.orchestrator.port;

import com.shopmind.orchestrator.domain.OrchestrationContext;
import reactor.core.publisher.Mono;

/**
 * Pipeline 步骤抽象接口 — 架构评审新增，支持 OCP 开闭原则。
 * <p>
 * 每个 PipelineStep 接收上下文、返回新的上下文（或原上下文）。
 * 新增步骤只需实现此接口并注入 Pipeline 序列即可，无需修改核心调度代码。
 * <p>
 * 线程安全约束：实现类必须为无状态 @Component，上下文仅通过参数传入。
 */
@FunctionalInterface
public interface PipelineStep {

    /**
     * 执行本步骤的业务逻辑。
     *
     * @param ctx 当前编排上下文（可能被修改后返回或封装在 Mono 中）
     * @return Mono 包装的上下文，支持响应式链式组合
     */
    Mono<OrchestrationContext> execute(OrchestrationContext ctx);
}
