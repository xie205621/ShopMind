package com.shopmind.workflow.domain;

import java.util.Collections;
import java.util.List;

/**
 * 工作流静态定义 — Workflow_Engine.md §6.1 Definition-Time 层。
 * <p>
 * 这是一个<b>不可变值对象</b>，代表某个 Agent 角色在某个版本下的完整行为契约。
 * 它包含角色设定、可用工具规则和企业安全策略，但不包含任何运行时数据
 * （如对话历史、知识库检索结果——这些属于 {@link WorkflowInstance}）。
 * <p>
 * 使用 Java record 确保：
 * <ul>
 *   <li>不可变性（线程安全）</li>
 *   <li>自动生成 equals/hashCode（适合版本对比、A/B 测试）</li>
 *   <li>紧凑声明（符合 DDD 值对象模式）</li>
 * </ul>
 * <p>
 * <b>构建方式：</b>通过 {@link com.shopmind.workflow.port.WorkflowBuilder} 逐步构建，
 * 禁止直接 new 或使用不完整的 record 构造器。
 *
 * @param id          工作流唯一标识
 * @param version     语义化版本号（如 "v1.2.0"），用于 Evaluation 的 A/B 对照
 * @param persona     角色 System Prompt 模板文本
 * @param toolRules   本工作流可使用的工具规则列表
 * @param constraints 企业安全合规策略列表
 */
public record WorkflowDefinition(
        String id,
        String version,
        String persona,
        List<ToolRule> toolRules,
        List<Policy> constraints
) {

    /**
     * 紧凑构造器：确保集合字段不为 null，防御外部调用方传入 null。
     */
    public WorkflowDefinition {
        toolRules = toolRules != null ? Collections.unmodifiableList(toolRules) : Collections.emptyList();
        constraints = constraints != null ? Collections.unmodifiableList(constraints) : Collections.emptyList();
    }

    /**
     * 判断两个定义是否为同一工作流的不同版本（id 相同但 version 不同）。
     */
    public boolean isDifferentVersionOf(WorkflowDefinition other) {
        return other != null && this.id.equals(other.id) && !this.version.equals(other.version);
    }

    /**
     * 获取所有 HARD 级别的策略（需要执行层强制校验）。
     */
    public List<Policy> hardPolicies() {
        return constraints.stream()
                .filter(p -> p.level() == PolicyLevel.HARD)
                .toList();
    }

    /**
     * 获取所有 SOFT 级别的策略（仅注入 Prompt）。
     */
    public List<Policy> softPolicies() {
        return constraints.stream()
                .filter(p -> p.level() == PolicyLevel.SOFT)
                .toList();
    }
}
