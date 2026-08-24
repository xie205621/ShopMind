package com.shopmind.experiment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具安全 Verifier 的输入 — Phase 2 / 修订于 Phase 5-C1。
 * <p>
 * 承载：
 * <ul>
 *   <li>{@code runtimeContext} — Runtime Session Context（运行时会话授权来源，非 GT）</li>
 *   <li>{@code attemptedTool} — LLM 尝试调用的工具名</li>
 *   <li>{@code arguments} — 工具调用入参（结构化）</li>
 * </ul>
 * <p>
 * <b>Phase 5-C1 修订：</b>原 {@code groundTruth}（{@code RtmpTestCase}）字段被移除，
 * 改为 {@code runtimeContext}（{@link RuntimeSessionContext}），使 Baseline B Verifier
 * 与 Method C Router 读取<b>同一份</b>运行时授权信息，修复 B/C 信息不对称（C0-C2）。
 *
 * @param runtimeContext 运行时会话上下文（可为 null，表示非 RTMP 场景）
 * @param attemptedTool  LLM 尝试调用的工具名
 * @param arguments      工具调用入参
 */
public record SafetyVerificationRequest(
        RuntimeSessionContext runtimeContext,
        String attemptedTool,
        Map<String, Object> arguments
) {

    /** 紧凑构造器：防御性拷贝 arguments，确保不为 null。 */
    public SafetyVerificationRequest {
        arguments = arguments != null
                ? Collections.unmodifiableMap(new HashMap<>(arguments))
                : Collections.emptyMap();
    }
}
