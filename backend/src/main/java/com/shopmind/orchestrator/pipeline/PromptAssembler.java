package com.shopmind.orchestrator.pipeline;

import com.shopmind.mcp.model.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装器 — §10 规范。
 * <p>
 * 将 System Prompt、历史对话、RAG 知识片段和可用工具 Schema
 * 拼接为 LLM 可以消费的标准化输入文本。
 * <p>
 * <b>v2.1 PromptOps：</b>System Prompt 不再硬编码，而是通过构造函数注入。
 * 默认无参构造器使用与 v2.0 完全一致的兜底 Prompt，保证向后兼容。
 * Phase B 将通过 WorkflowDefinitionLoader 动态注入不同版本的 Prompt。
 * <p>
 * 线程安全：无状态单例（Prompt 文本在构造后不可变），所有运行时数据通过方法参数传入。
 */
@Component
public class PromptAssembler {

    /** 兜底 System Prompt（当未通过构造函数注入时使用，内容 = v2.0 版本）。 */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            【角色】你是 ShopMind 智能客服助手，为电商平台用户提供专业、友好的服务。
            
            【核心规则】
            1. 回答简洁、准确，优先基于知识库内容回答。
            2. 知识库中没有的信息，诚实告知用户"目前没有相关信息"。
            3. 当需要执行业务操作（付款、查订单）时，调用对应工具。
            4. 禁止捏造或编造任何不存在的信息。
            5. 回答请使用中文。
            """;

    private static final String KNOWLEDGE_HEADER = """
            
            【参考知识（从知识库召回）】请基于以下知识片段回答用户问题：
            """;

    private static final String TOOLS_HEADER = """
            
            【可用工具】你可以调用以下工具来执行业务操作：
            """;

    /** 当前实例使用的 System Prompt 文本（构造后不可变）。 */
    private final String systemPrompt;

    /**
     * 无参构造器（供 Spring 使用）。
     * 使用默认 v2.0 Prompt，保证向后兼容。
     */
    public PromptAssembler() {
        this(DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * 带参构造器（供 PromptOps 版本化注入）。
     * Phase B 中由 WorkflowDefinitionLoader 加载 YAML persona 后注入。
     *
     * @param systemPrompt 版本化的 System Prompt 文本，不为 null
     */
    public PromptAssembler(String systemPrompt) {
        this.systemPrompt = systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * 组装完整 Prompt（消息列表格式，适合大多数 LLM SDK）。
     *
     * @param userMessage 用户当前提问
     * @param history     历史对话消息
     * @param knowledge   知识库召回结果（可为 null）
     * @param tools       可用工具列表（可为空）
     * @return 可直接传入 ChatModelPort 的消息列表
     */
    public String assembleFullPrompt(String userMessage,
                                      Object history, // List<ChatMessage>
                                      Object knowledge, // RetrievedContext
                                      List<ToolSpecification> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt);

        // 注入知识
        if (knowledge != null) {
            sb.append(KNOWLEDGE_HEADER);
            // knowledge.toString() 会输出拼接后的文本
            sb.append(knowledge.toString()).append("\n");
        }

        // 注入工具定义
        if (tools != null && !tools.isEmpty()) {
            sb.append(TOOLS_HEADER);
            for (ToolSpecification tool : tools) {
                sb.append("- ").append(tool.getToolName())
                        .append(": ").append(tool.getDescription()).append("\n");
            }
        }

        sb.append("\n【用户问题】").append(userMessage);
        return sb.toString();
    }
}
