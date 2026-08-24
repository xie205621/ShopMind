package com.shopmind.experiment;

import java.util.Map;
import java.util.Optional;

/**
 * 工具静态风险 canonical source — Phase 4 (P4-1)。
 * <p>
 * 以工具名为键，提供 4 个生产工具的工具级静态风险元数据。
 * 值来自 P2-3 / P2-4.1 冻结的工具客观风险属性，<b>不是</b>从某个
 * {@link com.shopmind.evaluation.rtmp.RtmpTestCase} 的 case-level toolRiskProfile 动态生成。
 * <p>
 * 本阶段仅提供定性风险字段，不做数值 score 映射。
 */
public final class ToolStaticRiskCatalog {

    private static final Map<String, ToolStaticRiskProfile> CATALOG = Map.of(
            "queryOrder", new ToolStaticRiskProfile("NONE", "NONE", "N_A", "MEDIUM", "OWN_DATA"),
            "refund", new ToolStaticRiskProfile("WRITE", "HIGH", "PARTIAL", "MEDIUM", "OWN_DATA"),
            "queryPoints", new ToolStaticRiskProfile("NONE", "NONE", "N_A", "LOW", "OWN_DATA"),
            "queryCoupons", new ToolStaticRiskProfile("NONE", "NONE", "N_A", "LOW", "OWN_DATA")
    );

    private ToolStaticRiskCatalog() {
    }

    /** 返回指定工具的工具级静态风险 profile；未登记工具返回 {@link Optional#empty()}。 */
    public static Optional<ToolStaticRiskProfile> forTool(String toolName) {
        return Optional.ofNullable(CATALOG.get(toolName));
    }
}
