package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.rtmp.persistence.RtmpExperimentPersistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Attempt execution provenance 持久化层 — Phase 5-R6.1。
 * <p>
 * 采用 JSONL append（每个 attempt 生命周期事件追加一行）+ 每次 {@code force(true)}（fsync），
 * 与 {@link RtmpCheckpointStore} 使用相同的单行 JSON + durable flush 语义，但文件独立：
 * {@code {experimentId}_attempt-ledger.jsonl}。
 * <p>
 * 该 ledger 是 <b>recovery provenance artifact</b>，不是统计输入；canonical final Raw 仍由
 * {@link RtmpExecutionCheckpoint} 承载，Raw 是唯一正式事实源。
 */
public final class RtmpAttemptLedgerStore {

    public static final String LEDGER_SCHEMA_VERSION = "rtmp-attempt-ledger-v1";

    private RtmpAttemptLedgerStore() {
    }

    /** attempt ledger 文件路径：{@code {experimentId}_attempt-ledger.jsonl}。 */
    public static Path ledgerFile(Path outputDir, String experimentId) {
        return outputDir.resolve(experimentId + "_attempt-ledger.jsonl");
    }

    /**
     * 追加一条 attempt 事件（JSONL 一行），随后 fsync。
     * <p>
     * IO 失败时抛出 {@link UncheckedIOException}（不静默吞掉），由上层终止 formal execution。
     */
    public static void append(Path file, RtmpAttemptLedgerEvent event) {
        String line = RtmpExperimentPersistence.toJsonLine(event) + "\n";
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Attempt ledger append failed for runId=" + event.runId(), e);
        }
    }

    /**
     * 读取全部 attempt 事件（每行一条 JSON）。文件不存在返回空列表。
     * <p>
     * 非法 JSON / 错误 schema version 立即抛异常（不静默跳过）。
     */
    public static List<RtmpAttemptLedgerEvent> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<RtmpAttemptLedgerEvent> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> out.add(parse(line)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read attempt ledger file: " + file, e);
        }
        return out;
    }

    /**
     * 计算每个 runId 已<b>消耗</b>的 attempt 数（即已出现 {@code STARTED} 的最大 attempt 序号，
     * 0 / 1 / 2）。attempt 严格顺序执行，故消耗数等于最大 STARTED attempt 序号。
     */
    public static Map<String, Integer> consumedAttempts(List<RtmpAttemptLedgerEvent> events) {
        Map<String, Integer> out = new HashMap<>();
        for (RtmpAttemptLedgerEvent e : events) {
            if (RtmpAttemptLedgerEvent.STARTED.equals(e.eventType())) {
                out.merge(e.runId(), e.attempt(), Math::max);
            }
        }
        return out;
    }

    /**
     * 计算每个 runId 最近一次 {@code COMPLETED} 的 status 名称（无 COMPLETED 则为 null）。
     */
    public static Map<String, String> lastCompletedStatus(List<RtmpAttemptLedgerEvent> events) {
        Map<String, String> out = new HashMap<>();
        for (RtmpAttemptLedgerEvent e : events) {
            if (RtmpAttemptLedgerEvent.COMPLETED.equals(e.eventType())) {
                out.put(e.runId(), e.status());
            }
        }
        return out;
    }

    private static RtmpAttemptLedgerEvent parse(String line) {
        RtmpAttemptLedgerEvent event =
                RtmpExperimentPersistence.readJson(line, RtmpAttemptLedgerEvent.class);
        if (event == null || !LEDGER_SCHEMA_VERSION.equals(event.schemaVersion())) {
            throw new IllegalStateException("Invalid attempt ledger schema in line: " + line);
        }
        validateEvent(event, line);
        return event;
    }

    /** 单事件结构校验（fail-closed）：eventType / attempt / status 语义。 */
    private static void validateEvent(RtmpAttemptLedgerEvent event, String line) {
        boolean started = RtmpAttemptLedgerEvent.STARTED.equals(event.eventType());
        boolean completed = RtmpAttemptLedgerEvent.COMPLETED.equals(event.eventType());
        if (!started && !completed) {
            throw new IllegalStateException("Invalid eventType in ledger line: " + line);
        }
        if (event.attempt() < 1 || event.attempt() > 2) {
            throw new IllegalStateException("Invalid attempt in ledger line: " + line);
        }
        if (started && event.status() != null) {
            throw new IllegalStateException("STARTED must not carry status: " + line);
        }
        if (completed && !isTerminalStatus(event.status())) {
            throw new IllegalStateException("COMPLETED must carry terminal status: " + line);
        }
    }

    private static boolean isTerminalStatus(String status) {
        return "VALID".equals(status) || "RETRYABLE_FAILURE".equals(status)
                || "INVALID_RUN".equals(status);
    }

    /**
     * 跨事件 + identity 校验（fail-closed）— Phase 5-R6.1 问题 3。
     * <p>
     * 在 {@code load()}（结构校验）之后、{@code buildAttemptRecoveryMap} 之前调用，校验：
     * <ul>
     *   <li>experimentId 匹配；runId ∈ plan；caseId/condition/repetition 与 plan 一致；</li>
     *   <li>同一 runId 内：无重复 {@code STARTED(n)} / {@code COMPLETED(n)}；</li>
     *   <li>{@code STARTED(2)} 必须已有 {@code STARTED(1)}；</li>
     *   <li>{@code COMPLETED(n)} 必须已有 {@code STARTED(n)}。</li>
     * </ul>
     *
     * @return 错误列表（空表示合法）；调用方据此拒绝启动，绝不静默忽略坏 ledger。
     */
    public static List<String> validate(List<RtmpAttemptLedgerEvent> events, String experimentId,
                                        RtmpFormalExperimentPlan.Plan plan) {
        List<String> errors = new ArrayList<>();
        Map<String, RtmpFormalExperimentPlan.Unit> byRunId = plan.units().stream()
                .collect(Collectors.toMap(
                        RtmpFormalExperimentPlan.Unit::runId, u -> u, (a, b) -> a));
        Map<String, Set<Integer>> startedByRunId = new HashMap<>();
        Map<String, Set<Integer>> completedByRunId = new HashMap<>();

        for (RtmpAttemptLedgerEvent e : events) {
            if (!experimentId.equals(e.experimentId())) {
                errors.add("ledger experimentId mismatch: " + e.experimentId());
            }
            RtmpFormalExperimentPlan.Unit unit = e.runId() == null ? null : byRunId.get(e.runId());
            if (unit == null) {
                errors.add("ledger runId not in plan: " + e.runId());
                continue;
            }
            if (!unit.caseId().equals(e.caseId())
                    || !unit.condition().equals(e.condition())
                    || unit.repetition() != e.repetition()) {
                errors.add("ledger identity mismatch for runId=" + e.runId()
                        + " (caseId=" + e.caseId() + ", condition=" + e.condition()
                        + ", repetition=" + e.repetition() + ")");
            }
            Set<Integer> started = startedByRunId.computeIfAbsent(e.runId(), k -> new HashSet<>());
            Set<Integer> completed = completedByRunId.computeIfAbsent(e.runId(), k -> new HashSet<>());
            if (RtmpAttemptLedgerEvent.STARTED.equals(e.eventType())) {
                if (!started.add(e.attempt())) {
                    errors.add("duplicate STARTED(" + e.attempt() + ") for " + e.runId());
                }
                if (e.attempt() == 2 && !started.contains(1)) {
                    errors.add("STARTED(2) without STARTED(1) for " + e.runId());
                }
            } else if (RtmpAttemptLedgerEvent.COMPLETED.equals(e.eventType())) {
                if (!completed.add(e.attempt())) {
                    errors.add("duplicate COMPLETED(" + e.attempt() + ") for " + e.runId());
                }
                if (!started.contains(e.attempt())) {
                    errors.add("COMPLETED(" + e.attempt() + ") without STARTED(" + e.attempt()
                            + ") for " + e.runId());
                }
            }
        }
        return errors;
    }
}
