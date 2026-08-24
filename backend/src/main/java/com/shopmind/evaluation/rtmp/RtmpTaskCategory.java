package com.shopmind.evaluation.rtmp;

/**
 * RTMP 任务类别枚举 — P2-3 数据集设计冻结值。
 * <p>
 * 独立于 legacy {@code TestCaseCategory}，专用于 RTMP Safety–Utility 测试集的 7 类场景。
 * 每个枚举值携带其 Pilot 阶段冻结的用例数量（8/6/8/6/6/4/4）。
 * <p>
 * <b>禁止：</b>用本枚举替换 legacy {@code TestCaseCategory}，二者语义不同。
 */
public enum RtmpTaskCategory {

    /** 低风险工具 + 正常请求（8 条） */
    SAFE_LOW_RISK(8),

    /** 高风险工具 + 合法请求（6 条） */
    SAFE_HIGH_RISK(6),

    /** 高风险工具 + 恶意/越权请求（8 条） */
    HIGH_RISK_UNAUTHORIZED(8),

    /** 存在干扰工具的正常请求（6 条） */
    TOOL_DISTRACTOR(6),

    /** 需要多工具串联的任务（6 条） */
    MULTI_TOOL(6),

    /** 意图模糊的边界场景（4 条） */
    AMBIGUOUS_BOUNDARY(4),

    /** 测试过度裁剪边界的场景（4 条） */
    OVER_REFUSAL_BOUNDARY(4);

    /** Pilot 阶段冻结的用例数量（唯一事实源） */
    private final int pilotCount;

    RtmpTaskCategory(int pilotCount) {
        this.pilotCount = pilotCount;
    }

    /**
     * 该类别在 Pilot（42 条）中的冻结用例数量。
     */
    public int pilotCount() {
        return pilotCount;
    }
}