package com.shopmind.orchestrator.domain;

import com.shopmind.experiment.ToolScoreResult;
import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.mcp.model.ToolSpecification;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.IntentAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 编排上下文 — §8 规范。
 * <p>
 * 在响应式管道各阶段之间传递的共享状态容器。
 * <b>线程安全：</b>此类实例生命周期为单次请求，仅在方法参数/返回值中传递，
 * 禁止作为 @Component 单例的实例字段。
 * <p>
 * 不可变字段（userMessage, memoryId）来自 OrchestrationRequest。
 * 可变字段（history, knowledge, assembledPrompt）由各 PipelineStep 逐步填充。
 */
public class OrchestrationContext {

    // === 不可变（来源请求） ===
    private final String memoryId;
    private final String userMessage;
    /** 请求级追踪 ID（每次 /api/chat 生成一个 UUID） */
    private final String requestId;

    // === 逐步填充 ===
    private List<ChatMessage> history;
    private RetrievedContext knowledge;
    private String assembledPrompt;
    private IntentAnalyzer.IntentResult intent;
    private final ExecutionState state;

    // === P4-3：RTMP Router 可见性 canonical carrier（单一事实源，双入口共用） ===
    private List<ToolSpecification> visibleTools = Collections.emptyList();
    private List<ToolScoreResult> pruningDecision = Collections.emptyList();
    /** 累积的工具执行结果（作为独立 SystemMessage 反哺 LLM，避免重渲染 System Prompt 时丢失） */
    private final StringBuilder toolObservations = new StringBuilder();

    // === 可观测性字段（P1-1 请求级观测，仅记录指标，不参与业务逻辑） ===
    private String model;
    private long intentLatencyMs;
    private long memoryLatencyMs;
    private long ragLatencyMs;
    private long llmLatencyMs;
    private final List<Long> toolLatenciesMs = new ArrayList<>();

    public OrchestrationContext(String memoryId, String userMessage) {
        this.memoryId = memoryId;
        this.userMessage = userMessage;
        this.requestId = UUID.randomUUID().toString();
        this.history = Collections.emptyList();
        this.knowledge = null;
        this.assembledPrompt = "";
        this.state = new ExecutionState();
    }

    // ============================================================
    //  Getters
    // ============================================================

    public String getMemoryId() { return memoryId; }
    public String getUserMessage() { return userMessage; }
    public String getRequestId() { return requestId; }
    public List<ChatMessage> getHistory() { return history; }
    public RetrievedContext getKnowledge() { return knowledge; }
    public String getAssembledPrompt() { return assembledPrompt; }
    public IntentAnalyzer.IntentResult getIntent() { return intent; }
    public ExecutionState getState() { return state; }

    /** P4-3：canonical visibleTools（单一事实源，System Prompt 与 Function Calling 共用）。 */
    public List<ToolSpecification> getVisibleTools() { return visibleTools; }
    /** P4-3：canonical pruningDecision（复用 ToolScoreResult，供 instrumentation 观测）。 */
    public List<ToolScoreResult> getPruningDecision() { return pruningDecision; }
    /** P4-3：累积的工具执行结果文本（作为独立 SystemMessage 反哺 LLM）。 */
    public String getToolObservations() { return toolObservations.toString(); }
    public boolean hasToolObservations() { return toolObservations.length() > 0; }

    public String getModel() { return model; }
    public long getIntentLatencyMs() { return intentLatencyMs; }
    public long getMemoryLatencyMs() { return memoryLatencyMs; }
    public long getRagLatencyMs() { return ragLatencyMs; }
    public long getLlmLatencyMs() { return llmLatencyMs; }
    public List<Long> getToolLatenciesMs() { return Collections.unmodifiableList(toolLatenciesMs); }

    // ============================================================
    //  Setters (package-private mutation for PipelineSteps)
    // ============================================================

    public void setHistory(List<ChatMessage> history) { this.history = history; }
    public void setKnowledge(RetrievedContext knowledge) { this.knowledge = knowledge; }
    public void setAssembledPrompt(String assembledPrompt) { this.assembledPrompt = assembledPrompt; }
    public void setIntent(IntentAnalyzer.IntentResult intent) { this.intent = intent; }

    /** P4-3：设置 canonical visibleTools。 */
    public void setVisibleTools(List<ToolSpecification> visibleTools) {
        this.visibleTools = visibleTools != null ? visibleTools : Collections.emptyList();
    }
    /** P4-3：设置 canonical pruningDecision。 */
    public void setPruningDecision(List<ToolScoreResult> pruningDecision) {
        this.pruningDecision = pruningDecision != null ? pruningDecision : Collections.emptyList();
    }
    /** P4-3：追加一次工具执行结果到独立观测缓冲区。 */
    public void appendToolObservation(String observation) {
        if (observation != null) {
            this.toolObservations.append(observation);
        }
    }

    public void setModel(String model) { this.model = model; }
    public void setIntentLatencyMs(long intentLatencyMs) { this.intentLatencyMs = intentLatencyMs; }
    public void setMemoryLatencyMs(long memoryLatencyMs) { this.memoryLatencyMs = memoryLatencyMs; }
    public void setRagLatencyMs(long ragLatencyMs) { this.ragLatencyMs = ragLatencyMs; }
    /** 累加一次 LLM 推理耗时（Inner Loop 可能多次调用 LLM） */
    public void addLlmLatencyMs(long latencyMs) { this.llmLatencyMs += latencyMs; }
    /** 记录一次工具调用耗时 */
    public void addToolLatency(long latencyMs) { this.toolLatenciesMs.add(latencyMs); }

    // ============================================================
    //  Convenience
    // ============================================================

    /** 快速判断是否有 RAG 知识上下文 */
    public boolean hasKnowledge() {
        return knowledge != null && knowledge.hasResults();
    }

    /** 快速判断是否有历史对话 */
    public boolean hasHistory() {
        return history != null && !history.isEmpty();
    }
}
