package com.shopmind.evaluation.rtmp.statistics;

import com.shopmind.evaluation.rtmp.RunStatus;
import com.shopmind.evaluation.rtmp.persistence.RtmpComparison;
import com.shopmind.evaluation.rtmp.persistence.RtmpRawRecord;
import com.shopmind.experiment.ControlType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * RTMP 统计主入口 — Phase 5-B1。
 * <p>
 * 严格依赖方向（§0）：
 * <pre>
 *   B4 Raw → paired-unit extraction → McNemar / Wilcoxon → Comparison statistical fields
 * </pre>
 * 统计事实源仍然是 {@code RtmpRawRecord}（Raw），不读取 Summary rate、不反推 McNemar、
 * 不从 Comparison 的 {@code valueA/valueB} 猜测 paired observations。
 * <p>
 * 对每个 condition pair × metric：binary metric 使用 exact McNemar，scalar metric 使用
 * Wilcoxon signed-rank，并把结果写回 Comparison 的 {@code statisticalTest / statistic /
 * pValue / decision / alpha / twoSided} 字段（§28 / §29）。Raw 不被修改（§30）。
 */
public final class RtmpStatisticalAnalyzer {

    /** Phase 5-C1：confirmatory primary family（H1/H2/H3），进入 Holm 校正；H4/H5 除外。 */
    private static final Set<String> PRIMARY_HYPOTHESES = Set.of("H1", "H2", "H3");

    private RtmpStatisticalAnalyzer() {
    }

    /**
     * 对已生成的 descriptive Comparison 执行统计写回，返回填充统计字段的新 Comparison。
     * 原始 Raw 记录与 descriptive 的 valueA/valueB/pairedN/pairedUnitIds 均保持不变。
     * <p>
     * Phase 5-C1：写回 p-value 后，对 confirmatory primary family（H1/H2/H3）做
     * Holm-Bonferroni 校正，填充 {@code adjustedPValue}；H4/H5/null 不校正。
     */
    public static RtmpComparison analyze(List<RtmpRawRecord> records, RtmpComparison comparison) {
        List<RtmpComparison.PairComparison> pairs = comparison.pairs().stream()
                .map(p -> fillPair(records, p))
                .toList();
        return applyHolm(comparison.schemaVersion(), comparison.sourceExperimentId(),
                comparison.sourceRawPattern(), comparison.generatedAt(), pairs);
    }

    // ============================================================
    //  Metric mapping（与 B4 RtmpComparisonBuilder 一致，但按统计类型区分）
    // ============================================================

    private record BinaryMetric(
            Function<RtmpRawRecord, Boolean> positive,
            Function<RtmpRawRecord, Boolean> eligible
    ) {
    }

    private record ScalarMetric(Function<RtmpRawRecord, Double> value) {
    }

    private static final Map<String, BinaryMetric> BINARY_METRICS = Map.of(
            "L1_RATE", new BinaryMetric(r -> r.evaluation().l1GenericSafetyViolation(), null),
            "L2_RATE", new BinaryMetric(r -> r.evaluation().l2HighRiskToolMisuse(), null),
            "L3_RATE", new BinaryMetric(r -> r.evaluation().l3ActualSafetyViolation(), null),
            "SAFETY_INTERVENTION_RATE", new BinaryMetric(r -> r.evaluation().safetyIntervention(), null),
            "CORE_TASK_SUCCESS_RATE", new BinaryMetric(r -> r.evaluation().coreTaskSuccess(),
                    r -> r.evaluation().coreTaskEligible()),
            "OVER_REFUSAL_RATE", new BinaryMetric(r -> r.evaluation().overRefusal(),
                    r -> "ANSWER_EXPECTED".equals(r.expectedOutcome()) && "CALL".equals(r.expectedToolAction()))
    );

    private static final Map<String, ScalarMetric> SCALAR_METRICS = Map.of(
            "ROUTER_LATENCY_MEAN_MS",
            new ScalarMetric(r -> (double) overheadLatency(r, ControlType.RTMP_ROUTER)),
            "VERIFIER_LATENCY_MEAN_MS",
            new ScalarMetric(r -> (double) overheadLatency(r, ControlType.SAFETY_VERIFIER))
    );

    private static long overheadLatency(RtmpRawRecord r, ControlType type) {
        return r.controlOverheadEvents().stream()
                .filter(e -> e.controlType() == type)
                .mapToLong(e -> e.latencyMs())
                .sum();
    }

    // ============================================================
    //  Pair fill
    // ============================================================

    private static RtmpComparison.PairComparison fillPair(List<RtmpRawRecord> records,
                                                          RtmpComparison.PairComparison pair) {
        List<RtmpComparison.ComparisonEntry> entries = pair.entries().stream()
                .map(e -> fillEntry(records, pair, e))
                .toList();
        return new RtmpComparison.PairComparison(pair.pairId(), pair.conditionA(), pair.conditionB(), entries);
    }

    private static RtmpComparison.ComparisonEntry fillEntry(List<RtmpRawRecord> records,
                                                            RtmpComparison.PairComparison pair,
                                                            RtmpComparison.ComparisonEntry entry) {
        StatisticalResult result = computeMetric(records, pair.conditionA(), pair.conditionB(), entry.metric());
        if (result == null) {
            return entry;
        }
        return new RtmpComparison.ComparisonEntry(
                entry.metric(), entry.hypothesis(), entry.conditionA(), entry.conditionB(),
                entry.pairedN(), entry.pairedUnitIds(),
                entry.valueA(), entry.valueB(), entry.difference(), entry.relativeDifference(),
                result.statisticalTest(), result.statistic(), result.pValue(), null,
                result.decision().name(), StatisticalDecision.ALPHA, true);
    }

    /**
     * Phase 5-C1：对 confirmatory primary family（H1/H2/H3）做 Holm-Bonferroni 校正。
     * <p>
     * 收集所有 hypothesis ∈ {H1,H2,H3} 的非 null p-value，按 Holm step-down 计算
     * adjusted p-value 并写回 {@code adjustedPValue}；H4/H5/null hypothesis 不校正，
     * 其 adjustedPValue 保持为原始 pValue（未校正）。
     */
    private static RtmpComparison applyHolm(String schema, String experimentId, String rawPattern,
                                            String generatedAt,
                                            List<RtmpComparison.PairComparison> pairs) {
        List<Double> primaryPValues = new ArrayList<>();
        for (RtmpComparison.PairComparison pair : pairs) {
            for (RtmpComparison.ComparisonEntry e : pair.entries()) {
                if (e.hypothesis() != null && PRIMARY_HYPOTHESES.contains(e.hypothesis())
                        && e.pValue() != null) {
                    primaryPValues.add(e.pValue());
                }
            }
        }
        Double[] adjusted = HolmBonferroni.adjust(primaryPValues.toArray(new Double[0]));

        int cursor = 0;
        List<RtmpComparison.PairComparison> newPairs = new ArrayList<>();
        for (RtmpComparison.PairComparison pair : pairs) {
            List<RtmpComparison.ComparisonEntry> newEntries = new ArrayList<>();
            for (RtmpComparison.ComparisonEntry e : pair.entries()) {
                Double adjustedPValue;
                if (e.hypothesis() != null && PRIMARY_HYPOTHESES.contains(e.hypothesis())) {
                    adjustedPValue = e.pValue() != null ? adjusted[cursor++] : null;
                } else {
                    adjustedPValue = e.pValue();
                }
                newEntries.add(new RtmpComparison.ComparisonEntry(
                        e.metric(), e.hypothesis(), e.conditionA(), e.conditionB(),
                        e.pairedN(), e.pairedUnitIds(),
                        e.valueA(), e.valueB(), e.difference(), e.relativeDifference(),
                        e.statisticalTest(), e.statistic(), e.pValue(), adjustedPValue,
                        e.decision(), e.alpha(), e.twoSided()));
            }
            newPairs.add(new RtmpComparison.PairComparison(
                    pair.pairId(), pair.conditionA(), pair.conditionB(), newEntries));
        }
        return new RtmpComparison(schema, experimentId, rawPattern, generatedAt, newPairs);
    }

    private static StatisticalResult computeMetric(List<RtmpRawRecord> records,
                                                   String condA, String condB, String metric) {
        BinaryMetric binary = BINARY_METRICS.get(metric);
        if (binary != null) {
            return computeBinary(records, condA, condB, binary);
        }
        ScalarMetric scalar = SCALAR_METRICS.get(metric);
        if (scalar != null) {
            return computeScalar(records, condA, condB, scalar);
        }
        return null;
    }

    private static StatisticalResult computeBinary(List<RtmpRawRecord> records,
                                                   String condA, String condB, BinaryMetric m) {
        int[] excluded = {0};
        List<PairedUnit> paired = extractValidPaired(records, condA, condB, excluded);
        List<Boolean> a = new ArrayList<>();
        List<Boolean> b = new ArrayList<>();
        for (PairedUnit u : paired) {
            if (m.eligible != null && (!m.eligible.apply(u.a) || !m.eligible.apply(u.b))) {
                continue;
            }
            a.add(m.positive.apply(u.a));
            b.add(m.positive.apply(u.b));
        }
        return McNemarExact.compute(a, b, excluded[0]);
    }

    private static StatisticalResult computeScalar(List<RtmpRawRecord> records,
                                                   String condA, String condB, ScalarMetric m) {
        int[] excluded = {0};
        List<PairedUnit> paired = extractValidPaired(records, condA, condB, excluded);
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (PairedUnit u : paired) {
            x.add(m.value.apply(u.a));
            y.add(m.value.apply(u.b));
        }
        return WilcoxonSignedRank.compute(x, y, excluded[0]);
    }

    // ============================================================
    //  Paired-unit extraction（caseId#repetition，仅 VALID + evaluation != null）
    // ============================================================

    private record PairedUnit(String unitId, RtmpRawRecord a, RtmpRawRecord b) {
    }

    private static Map<String, RtmpRawRecord> allByUnit(List<RtmpRawRecord> records, String condition) {
        Map<String, RtmpRawRecord> map = new LinkedHashMap<>();
        if (records == null) {
            return map;
        }
        for (RtmpRawRecord r : records) {
            if (r != null && condition.equals(r.condition()) && r.caseId() != null) {
                map.put(r.caseId() + "#" + r.repetition(), r);
            }
        }
        return map;
    }

    private static List<PairedUnit> extractValidPaired(List<RtmpRawRecord> records,
                                                       String condA, String condB, int[] excludedOut) {
        Map<String, RtmpRawRecord> allA = allByUnit(records, condA);
        Map<String, RtmpRawRecord> allB = allByUnit(records, condB);
        TreeSet<String> units = new TreeSet<>(allA.keySet());
        units.addAll(allB.keySet());

        List<PairedUnit> paired = new ArrayList<>();
        int excluded = 0;
        for (String unit : units) {
            RtmpRawRecord ra = allA.get(unit);
            RtmpRawRecord rb = allB.get(unit);
            if (ra == null || rb == null
                    || ra.status() != RunStatus.VALID || rb.status() != RunStatus.VALID
                    || ra.evaluation() == null || rb.evaluation() == null) {
                excluded++;
                continue;
            }
            paired.add(new PairedUnit(unit, ra, rb));
        }
        excludedOut[0] = excluded;
        return paired;
    }
}
