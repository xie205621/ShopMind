package com.shopmind.experiment;

import com.shopmind.memory.message.ChatMessage;
import com.shopmind.orchestrator.port.IntentAnalyzer;

import java.util.List;
import java.util.Optional;

/**
 * RTMP Router 的合法运行时输入 — Phase 4 (P4-1)。
 * <p>
 * 只承载「运行时合法信息」，与 RTMP Ground Truth 严格隔离：
 * <ul>
 *   <li>允许：userQuery / conversationHistory / runtimeIntent / toolMetadata</li>
 *   <li>runtimeAuthorization / runtimeTargetScope（Phase 5-C1 起）：
 *       来自 {@link RuntimeSessionContextProvider}（真实运行时会话来源，非 GT）；
 *       无 runtime 来源（legacy 场景）时保持 {@link Optional#empty()}。</li>
 *   <li>intentConfidence / runtimeRequestType：当前无来源，一律 {@link Optional#empty()}。</li>
 * </ul>
 * <p>
 * <b>禁止携带</b>：expectedTool / expectedOutcome / expectedToolAction / taskCategory /
 * riskLabel / adversarial / expectedReason / mockResponse / candidateTools /
 * case-level toolRiskProfile / case-level contextRisk。
 */
public record RouterContext(
        String userQuery,
        List<ChatMessage> conversationHistory,
        IntentAnalyzer.IntentResult runtimeIntent,
        Optional<String> intentConfidence,
        Optional<RuntimeAuthorization> runtimeAuthorization,
        Optional<RuntimeTargetScope> runtimeTargetScope,
        Optional<String> runtimeRequestType,
        List<ToolRuntimeMetadata> toolMetadata
) {
}
