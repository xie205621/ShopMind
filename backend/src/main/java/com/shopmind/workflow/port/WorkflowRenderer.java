package com.shopmind.workflow.port;

import com.shopmind.workflow.domain.ToolRule;
import com.shopmind.workflow.domain.WorkflowInstance;

import java.util.List;

/**
 * 工作流渲染器接口 — Workflow_Engine.md §7.2 规范。
 * <p>
 * 将结构化的 {@link WorkflowInstance} 渲染为 LLM 可直接消费的 Prompt 字符串。
 * <b>这是一个纯函数：</b>输入确定则输出确定，不包含任何副作用。
 * <p>
 * <b>严格约束：</b>
 * <ul>
 *   <li>禁止包含数据库查询（Memory 数据应在传入前准备好）</li>
 *   <li>禁止包含 HTTP/API 调用（RAG 检索应在传入前完成）</li>
 *   <li>禁止修改 WorkflowInstance 的任何字段</li>
 * </ul>
 * <p>
 * <b>职责边界：</b>Renderer 只负责"格式化输出"，不负责"获取数据"。
 * 数据获取由 {@code ContextHydrationStep} 和 {@code ShopAgentOrchestrator} 完成。
 * <p>
 * <b>线程安全：</b>实现类应当是无状态 {@code @Component} 单例。
 *
 * @see WorkflowInstance
 */
public interface WorkflowRenderer {

    /**
     * 将 WorkflowInstance 渲染为 LLM 可消费的 Prompt 字符串。
     * <p>
     * 输出格式示例：
     * <pre>{@code
     * 【角色】你是一个友好的智能客服...
     *
     * 【安全约束】
     * 1. 禁止泄露进货价
     * 2. 禁止绕过沙箱支付
     *
     * 【可用工具】
     * - confirmPayment: 确认付款
     * - queryOrder: 查询订单
     *
     * 【参考知识】
     * [来源 1] 退货政策：7天无理由退货...
     *
     * 【用户问题】这个手机能退吗？
     * }</pre>
     *
     * @param instance 工作流运行时实例（含定义 + 运行时数据）
     * @return 渲染后的完整 Prompt 字符串
     */
    String render(WorkflowInstance instance);

    /**
     * 将 WorkflowInstance 渲染为 LLM 可消费的 Prompt 字符串，但【可用工具】段
     * 只渲染 {@code visibleToolRules}（拆分渲染，供 P4-3 双入口同步使用）。
     * <p>
     * <b>双入口一致性：</b>System Prompt 的【可用工具】与 Function Calling 的 tools 参数
     * 必须由同一份 visibleTools 驱动；本方法允许调用方把裁剪后的工具规则传入渲染器，
     * 而不在渲染器内部再次计算可见性。
     *
     * @param instance          工作流运行时实例（含定义 + 运行时数据）
     * @param visibleToolRules  可见工具规则（已按 visibleTools 过滤）
     * @return 渲染后的完整 Prompt 字符串（工具段仅含 visibleToolRules）
     */
    String render(WorkflowInstance instance, List<ToolRule> visibleToolRules);
}
