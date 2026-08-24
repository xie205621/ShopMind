package com.shopmind.orchestrator.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.orchestrator.domain.OrchestrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求级可观测性日志（P1-1）。
 * <p>
 * 在每次 {@code /api/chat} 请求结束时，输出一条<b>单行结构化 JSON</b>日志，
 * 记录 requestId / sessionId / workflowVersion / model / intent 以及各阶段耗时，
 * 用于检索、链路追踪与性能分析。
 * <p>
 * <b>安全约束：</b>
 * <ul>
 *   <li>不记录 API Key；</li>
 *   <li>不记录用户 query 原文（仅记录意图分类标签与耗时指标）；</li>
 *   <li>所有字段均为元数据/指标，无敏感业务内容。</li>
 * </ul>
 */
@Component
public class RequestObservabilityLogger {

    /** 独立 Logger 名，便于通过 logback 配置单独过滤/路由 */
    private static final Logger log = LoggerFactory.getLogger("REQUEST_OBSERVABILITY");

    private final ObjectMapper objectMapper;

    public RequestObservabilityLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出一条请求级观测日志。
     *
     * @param ctx             请求级编排上下文（含 requestId 与各阶段耗时）
     * @param workflowVersion 工作流版本号（如 v2.3）
     * @param totalLatencyMs  端到端总耗时（毫秒）
     */
    public void log(OrchestrationContext ctx, String workflowVersion, long totalLatencyMs) {
        log.info(buildJson(ctx, workflowVersion, totalLatencyMs));
    }

    /**
     * 构造结构化 JSON（供日志输出与单元测试复用）。
     */
    public String buildJson(OrchestrationContext ctx, String workflowVersion, long totalLatencyMs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", ctx.getRequestId());
        fields.put("sessionId", ctx.getMemoryId());
        fields.put("workflowVersion", workflowVersion);
        fields.put("model", ctx.getModel());
        fields.put("intent", ctx.getIntent() != null ? ctx.getIntent().category() : null);
        fields.put("intentLatencyMs", ctx.getIntentLatencyMs());
        fields.put("memoryLatencyMs", ctx.getMemoryLatencyMs());
        fields.put("ragLatencyMs", ctx.getRagLatencyMs());
        fields.put("llmLatencyMs", ctx.getLlmLatencyMs());
        fields.put("toolLatenciesMs", ctx.getToolLatenciesMs());
        fields.put("toolCalls", ctx.getToolLatenciesMs().size());
        fields.put("totalLatencyMs", totalLatencyMs);
        fields.put("status", ctx.getState().getStatus().name());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            // 序列化失败兜底：仍输出可检索的最小字段，避免观测日志中断主链路
            return "{\"requestId\":\"" + ctx.getRequestId() + "\",\"error\":\"observability_serialization_failed\"}";
        }
    }
}
