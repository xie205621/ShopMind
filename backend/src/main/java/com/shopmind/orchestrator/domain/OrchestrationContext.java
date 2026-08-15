package com.shopmind.orchestrator.domain;

import com.shopmind.knowledge.model.RetrievedContext;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.IntentAnalyzer;

import java.util.Collections;
import java.util.List;

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

    // === 逐步填充 ===
    private List<ChatMessage> history;
    private RetrievedContext knowledge;
    private String assembledPrompt;
    private IntentAnalyzer.IntentResult intent;
    private final ExecutionState state;

    public OrchestrationContext(String memoryId, String userMessage) {
        this.memoryId = memoryId;
        this.userMessage = userMessage;
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
    public List<ChatMessage> getHistory() { return history; }
    public RetrievedContext getKnowledge() { return knowledge; }
    public String getAssembledPrompt() { return assembledPrompt; }
    public IntentAnalyzer.IntentResult getIntent() { return intent; }
    public ExecutionState getState() { return state; }

    // ============================================================
    //  Setters (package-private mutation for PipelineSteps)
    // ============================================================

    public void setHistory(List<ChatMessage> history) { this.history = history; }
    public void setKnowledge(RetrievedContext knowledge) { this.knowledge = knowledge; }
    public void setAssembledPrompt(String assembledPrompt) { this.assembledPrompt = assembledPrompt; }
    public void setIntent(IntentAnalyzer.IntentResult intent) { this.intent = intent; }

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
