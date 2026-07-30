package com.shopmind.workflow.domain;

import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.memory.message.ChatMessage;

import java.util.Collections;
import java.util.List;

/**
 * 工作流运行时实例 — Workflow_Engine.md §6.1 Runtime 层。
 * <p>
 * 每次用户请求时由 Agent Orchestrator 动态构建。它将静态的
 * {@link WorkflowDefinition}（角色+策略）与运行时数据
 *（Memory 历史、RAG 知识、当前用户消息）组装为一个可供
 * {@link com.shopmind.workflow.port.WorkflowRenderer} 渲染的完整对象。
 * <p>
 * <b>与 WorkflowDefinition 的区别：</b>
 * <ul>
 *   <li>{@code WorkflowDefinition} — 部署时定义，存储在 DB/YAML/Git，长期不变</li>
 *   <li>{@code WorkflowInstance} — 每次请求 new 出来，生命周期 = 单次对话，用完即弃</li>
 * </ul>
 * <p>
 * 使用 Java record 确保不可变语义。
 *
 * @param definition        关联的工作流静态定义（含版本号，用于 Trace 关联）
 * @param history           从 Memory Engine 恢复的对话历史
 * @param knowledge         从 Knowledge Engine 召回的知识片段（可为 null）
 * @param currentUserMessage 用户当前轮输入的自然语言文本
 */
public record WorkflowInstance(
        WorkflowDefinition definition,
        List<ChatMessage> history,
        RetrievedContext knowledge,
        String currentUserMessage
) {

    /**
     * 紧凑构造器：防御性拷贝集合，确保不可变。
     */
    public WorkflowInstance {
        history = history != null ? Collections.unmodifiableList(history) : Collections.emptyList();
    }

    /**
     * 是否包含知识库召回结果。
     */
    public boolean hasKnowledge() {
        return knowledge != null && knowledge.hasResults();
    }

    /**
     * 是否有历史对话记录。
     */
    public boolean hasHistory() {
        return !history.isEmpty();
    }

    /**
     * 获取关联的工作流版本号（便捷方法）。
     */
    public String workflowVersion() {
        return definition.version();
    }
}
