package com.shopmind.experiment;

/**
 * Runtime Session Context 提供者 — Phase 5-C1。
 * <p>
 * 真实运行时输入来源的抽象：根据 caseId 解析出该会话的 {@link RuntimeSessionContext}。
 * 生产系统中由登录/会话服务实现；RTMP 实验环境由 {@link RtmpRuntimeScenarioProvider}
 * （独立 runtime fixture，非 GT）实现。
 */
public interface RuntimeSessionContextProvider {

    /**
     * 解析指定 caseId 对应的 Runtime Session Context。
     *
     * @param caseId 用例标识（如 {@code RTMP-019}）
     * @return 对应的 Runtime Session Context；无 fixture 时返回 null（表示无运行时来源）
     */
    RuntimeSessionContext resolve(String caseId);
}
