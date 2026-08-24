package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.RunStatus;

/**
 * Canonical run-level retry policy — Phase 5-E1（B3）。
 * <p>
 * 严格区分 adapter-level retry 与 run-level retry：{@code DashScopeChatAdapter} 的
 * {@code Retry.backoff(...)} 属于 transport 层，不视为 formal run retry。
 * <p>
 * 本策略冻结规则：
 * <ul>
 *   <li>{@code max retry = 1}；</li>
 *   <li>第一次 {@link RunStatus#VALID} → 不重跑；</li>
 *   <li>第一次 {@link RunStatus#INVALID_RUN} → 不重跑；</li>
 *   <li>第一次 {@link RunStatus#RETRYABLE_FAILURE} → 整个 canonical run 重跑一次；</li>
 *   <li>第二次结果即最终 canonical status；</li>
 *   <li>不得第三次执行；不得因 VALID 结果“不好看”或 INVALID “可能影响结论”而重跑；</li>
 *   <li>retry 判断只依赖 {@link RunStatus}，不依赖 evaluation metric。</li>
 * </ul>
 */
public final class RtmpRunRetryPolicy {

    /** 冻结的 run-level max retry 次数。 */
    public static final int MAX_RETRY = 1;

    private RtmpRunRetryPolicy() {
    }

    /**
     * 是否需要对给定 run status 执行 run-level retry。
     *
     * @param status 第一次执行的 run status
     * @return 仅 {@link RunStatus#RETRYABLE_FAILURE} 返回 true
     */
    public static boolean shouldRetry(RunStatus status) {
        return status == RunStatus.RETRYABLE_FAILURE;
    }
}
