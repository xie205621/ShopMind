package com.shopmind.workflow.pipeline;

import com.shopmind.workflow.domain.Policy;
import com.shopmind.workflow.domain.PolicyLevel;
import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowInstance;
import com.shopmind.workflow.port.WorkflowRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WorkflowRenderer 默认实现 — 将 WorkflowInstance 渲染为 LLM System Prompt。
 * <p>
 * <b>渲染顺序（遵循 Workflow_Engine.md §7.2 规范）：</b>
 * <ol>
 *   <li>Persona（角色定义 + 核心规则）</li>
 *   <li>【安全约束】— HARD + SOFT 策略</li>
 *   <li>【可用工具】— 来自 YAML toolRules 的声明式工具描述</li>
 *   <li>【参考知识】— RAG 召回的知识片段（仅当存在时）</li>
 * </ol>
 * <p>
 * <b>设计约束：</b>
 * <ul>
 *   <li>纯函数，无副作用，不访问 DB/API</li>
 *   <li>输入确定则输出确定（可测试性）</li>
 *   <li>不包含【用户问题】（由 Orchesrator 作为单独的 UserMessage 传入 LLM）</li>
 * </ul>
 * <p>
 * <b>线程安全：</b>无状态 {@code @Component} 单例。
 *
 * @see WorkflowRenderer
 * @see WorkflowInstance
 */
@Component
public class WorkflowRendererImpl implements WorkflowRenderer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRendererImpl.class);

    @Override
    public String render(WorkflowInstance instance) {
        WorkflowDefinition def = instance.definition();

        StringBuilder sb = new StringBuilder();

        // ---- 1. Persona ----
        sb.append(def.persona());

        // ---- 2. Constraints (安全合规策略) ----
        List<Policy> constraints = def.constraints();
        if (!constraints.isEmpty()) {
            sb.append("\n\n【安全约束】请严格遵守以下企业合规策略：\n");
            for (int i = 0; i < constraints.size(); i++) {
                Policy p = constraints.get(i);
                sb.append(i + 1).append(". [").append(p.level()).append("] ").append(p.content()).append("\n");
            }
        }

        // ---- 3. Tool Rules (可用工具声明) ----
        List<ToolRule> toolRules = def.toolRules();
        if (!toolRules.isEmpty()) {
            sb.append("\n【可用工具】你可以调用以下工具来执行业务操作：\n");
            for (ToolRule rule : toolRules) {
                sb.append("- ").append(rule.toolName())
                        .append(": ").append(rule.description());
                if (rule.required()) {
                    sb.append(" （必须调用）");
                }
                sb.append("\n");
            }
        }

        // ---- 4. Knowledge (参考知识，仅当存在时) ----
        if (instance.hasKnowledge()) {
            sb.append("\n【参考知识（从知识库召回）】请基于以下知识片段回答用户问题：\n");
            sb.append(instance.knowledge().toConcatenatedText()).append("\n");
        } else {
            // ---- Guardrails: 知识库为空时强制拒答 ----
            sb.append("\n【重要安全规则 - 知识库未命中】\n");
            sb.append("知识库中未检索到与用户问题相关的信息。你必须**拒绝编造答案**，直接回复：\n");
            sb.append("\"抱歉，我目前没有相关信息可以回答您的问题，建议您联系人工客服获取帮助。\"\n");
            sb.append("严禁使用\"根据内部数据显示\"、\"据我了解\"、\"一般来说\"等编造性表述。\n");
        }

        log.debug("[WorkflowRenderer] Rendered prompt: {} chars, toolRules={}, constraints={}, hasKnowledge={}",
                sb.length(), toolRules.size(), constraints.size(), instance.hasKnowledge());

        return sb.toString();
    }
}
