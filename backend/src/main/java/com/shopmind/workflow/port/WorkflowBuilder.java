package com.shopmind.workflow.port;

import com.shopmind.workflow.domain.Policy;
import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowDefinition;

import java.util.List;

/**
 * 工作流构建器接口 — Workflow_Engine.md §7.1 规范。
 * <p>
 * 使用 Builder 模式逐步构建不可变的 {@link WorkflowDefinition}。
 * 每次调用 {@link #build()} 都产生一个全新的 record 实例，
 * 确保了不同版本之间的隔离与安全。
 * <p>
 * <b>典型用法：</b>
 * <pre>{@code
 * WorkflowDefinition v1 = builder
 *     .id("customer-service")
 *     .version("v1.0.0")
 *     .persona("你是一个友好的智能客服...")
 *     .addPolicy(Policy.soft("禁止泄露进货价", "绝对不要..."))
 *     .addToolRule(ToolRule.required("confirmPayment", "确认付款"))
 *     .build();
 * }</pre>
 * <p>
 * <b>线程安全：</b>实现类应当是无状态 {@code @Component} 单例。
 * Builder 本身在每次 {@code build()} 后返回全新实例，不保留状态。
 */
public interface WorkflowBuilder {

    /**
     * 设置工作流唯一标识。
     */
    WorkflowBuilder id(String id);

    /**
     * 设置语义化版本号（如 "v1.2.0"），用于 Evaluation 的 A/B 对照。
     */
    WorkflowBuilder version(String version);

    /**
     * 设置角色 System Prompt 模板文本。
     */
    WorkflowBuilder persona(String persona);

    /**
     * 批量设置可用的工具规则列表。
     */
    WorkflowBuilder toolRules(List<ToolRule> rules);

    /**
     * 添加单个工具规则。
     */
    WorkflowBuilder addToolRule(ToolRule rule);

    /**
     * 添加单个安全策略。
     */
    WorkflowBuilder addPolicy(Policy policy);

    /**
     * 批量设置安全合规策略列表。
     */
    WorkflowBuilder constraints(List<Policy> constraints);

    /**
     * 构建不可变的 {@link WorkflowDefinition} 实例。
     * <p>
     * 每次调用均产生新实例，Builder 内部状态在 build 后不保留。
     *
     * @return 一个新的不可变 WorkflowDefinition
     * @throws IllegalStateException 如果必填字段（id, version, persona）未设置
     */
    WorkflowDefinition build();
}
