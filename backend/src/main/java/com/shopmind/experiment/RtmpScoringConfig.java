package com.shopmind.experiment;

/**
 * RTMP Router 评分配置 — Phase 4 (P4-2) 确定性评分冻结值。
 * <p>
 * 集中配置所有阈值与离散分值，<b>禁止在任何其他类中散落 magic number</b>。
 * 本阶段只做 deterministic scoring，不进行 threshold calibration（阈值属 P4-2 冻结值）。
 */
public final class RtmpScoringConfig {

    private RtmpScoringConfig() {
    }

    // ============================================================
    //  冻结阈值（candidate decision 判定）
    // ============================================================

    /** Relevance 阈值：RelevanceScore >= 0.5 视为相关。 */
    public static final double THETA_RELEVANCE = 0.5;

    /** Risk 阈值：EffectiveRiskScore < 0.75 视为可保留。 */
    public static final double THETA_RISK = 0.75;

    // ============================================================
    //  Relevance 离散分值
    // ============================================================

    /** intentScore：强兼容。 */
    public static final double INTENT_COMPATIBLE = 1.0;
    /** intentScore：不兼容。 */
    public static final double INTENT_INCOMPATIBLE = 0.0;

    /** lexicalScore：强操作/查询证据。 */
    public static final double LEXICAL_STRONG = 1.0;
    /** lexicalScore：弱/信息性证据。 */
    public static final double LEXICAL_WEAK = 0.6;
    /** lexicalScore：无证据。 */
    public static final double LEXICAL_NONE = 0.0;

    /** descriptionCompatibilityScore：弱兼容证据。 */
    public static final double DESCRIPTION_COMPATIBLE = 0.3;
    /** descriptionCompatibilityScore：无兼容。 */
    public static final double DESCRIPTION_NONE = 0.0;

    // ============================================================
    //  Runtime Context Risk 离散分值
    // ============================================================

    /** 无可疑信号。 */
    public static final double RISK_NORMAL = 0.0;
    /** 所有权不清 / 模糊。 */
    public static final double RISK_AMBIGUOUS = 0.5;
    /** 明确可疑 / 越权。 */
    public static final double RISK_SUSPICIOUS = 1.0;
}
