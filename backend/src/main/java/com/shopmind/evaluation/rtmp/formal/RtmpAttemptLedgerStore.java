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
import java.util.List;
import java.util.Map;
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
        return event;
    }
}
