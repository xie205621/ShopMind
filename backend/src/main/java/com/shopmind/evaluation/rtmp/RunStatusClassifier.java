package com.shopmind.evaluation.rtmp;

import com.shopmind.orchestrator.exception.LlmProviderTimeoutException;
import com.shopmind.workflow.domain.ExecutionTrace;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Run Outcome 分类器 — Phase 1-C。
 * <p>
 * 将一次 RTMP run 的 Raw Trace 与（可选的）异常映射为 {@link RunStatus}，
 * 仅做<b>实验级运行状态</b>分类，不涉及任何行为/评估层结论。
 * <p>
 * <b>分类规则（冻结）：</b>
 * <ul>
 *   <li>缺失强制 run metadata（{@code trace.getRunIdentity() == null}）→ {@link RunStatus#INVALID_RUN}</li>
 *   <li>正常完成（无异常）→ {@link RunStatus#VALID}</li>
 *   <li>临时故障（LLM timeout / HTTP 429 / transient MCP failure）→ {@link RunStatus#RETRYABLE_FAILURE}</li>
 *   <li>其余异常（dataset/schema corruption、duplicate run identity、instrumentation corruption 等）→ {@link RunStatus#INVALID_RUN}</li>
 * </ul>
 * <p>
 * 无状态，可安全作为 Spring singleton 注入。
 */
@Component
public class RunStatusClassifier {

    /**
     * 分类一次 run 的结果。
     *
     * @param trace 本次 run 的 canonical Raw Trace（必须非 null）
     * @param error 驱动 run 时捕获到的异常（正常完成为 null）
     * @return 实验级运行状态
     */
    public RunStatus classify(ExecutionTrace trace, Throwable error) {
        // 缺失强制 run metadata（RTMP run 必须携带 RunIdentity）
        if (trace == null || trace.getRunIdentity() == null) {
            return RunStatus.INVALID_RUN;
        }
        // 正常完成
        if (error == null) {
            return RunStatus.VALID;
        }
        // 有异常：区分可重试 vs 不可恢复
        return isTransient(error) ? RunStatus.RETRYABLE_FAILURE : RunStatus.INVALID_RUN;
    }

    /**
     * 判定异常是否为临时基础设施故障（可重试）。
     */
    private boolean isTransient(Throwable error) {
        if (error instanceof TimeoutException) {
            return true;
        }
        if (error instanceof LlmProviderTimeoutException) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout")
                || lower.contains("429")
                || lower.contains("rate limited")
                || lower.contains("too many requests")
                || lower.contains("transient")
                || lower.contains("temporarily");
    }
}
