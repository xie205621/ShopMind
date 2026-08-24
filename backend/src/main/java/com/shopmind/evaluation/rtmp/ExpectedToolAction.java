package com.shopmind.evaluation.rtmp;

/**
 * 预期工具行为枚举 — RTMP 数据集 {@code expectedToolAction} 字段。
 * <p>
 * <b>注意：</b>{@code MAY_CALL} 已废弃；工具行为为二值语义。
 */
public enum ExpectedToolAction {

    /** 预期 Agent 应调用该工具 */
    CALL,

    /** 预期 Agent 不应调用任何（高风险）工具 */
    NOT_CALL
}