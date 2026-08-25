package com.shopmind.evaluation.rtmp.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RTMP 实验事实持久化层 — Phase 5-B4。
 * <p>
 * 负责将 Raw / Summary / Comparison 落盘到 {@code experiments/} 目录：
 * <ul>
 *   <li>{@code {experimentId}_raw.json} — 一个 experiment 的完整 Raw 事实（含 schema version）；</li>
 *   <li>{@code {experimentId}_summary.json} — 从 Raw 聚合的描述性 summary；</li>
 *   <li>{@code comparison.json} — 三个 condition pair 的描述性比较。</li>
 * </ul>
 * <p>
 * <b>原子写入：</b>写 temp → close → atomic move（不支持 atomic rename 时降级为普通 replace）。
 * <b>幂等/重复：</b>同一 run_id 重复写入为 duplicate error（不得静默 append 两份相同 run）。
 * <b>null 语义：</b>未配置 NON_NULL，确保 null token/cost 以 {@code null} 原样落盘，不被改写为 0/空串。
 */
public final class RtmpExperimentPersistence {

    public static final String RAW_SCHEMA_VERSION = "rtmp-b4-raw-v1";

    private static final ObjectMapper MAPPER = configureMapper();

    /** 单行（compact）mapper：与 {@link #MAPPER} 同模块/日期配置，但关闭 INDENT_OUTPUT。 */
    private static final ObjectMapper COMPACT_MAPPER = configureCompactMapper();

    private RtmpExperimentPersistence() {
    }

    private static ObjectMapper configureMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private static ObjectMapper configureCompactMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Raw 文件信封（含 raw schema version + experimentId + records）。
     */
    public record RawFile(String schemaVersion, String experimentId, String generatedAt,
                          List<RtmpRawRecord> records) {
    }

    // ============================================================
    //  Raw
    // ============================================================

    /**
     * 将一个 experiment 的 Raw 记录落盘为 {@code {experimentId}_raw.json}。
     *
     * @param records   同一 experiment 的 Raw 记录
     * @param outputDir 输出目录（{@code experiments/}）
     * @return 写入的文件路径
     */
    public static Path writeRaw(List<RtmpRawRecord> records, Path outputDir) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("Cannot write empty raw records");
        }
        String experimentId = resolveExperimentId(records);
        rejectDuplicateRunIds(records);

        RawFile file = new RawFile(RAW_SCHEMA_VERSION, experimentId, Instant.now().toString(),
                List.copyOf(records));
        Path target = outputDir.resolve(experimentId + "_raw.json");
        atomicWrite(target, toJson(file));
        return target;
    }

    /**
     * 读取 {@code {experimentId}_raw.json} 信封（供 Raw→Summary/Comparison 复现与 round-trip 测试）。
     */
    public static RawFile readRawFile(Path rawFile) {
        try {
            String json = Files.readString(rawFile, StandardCharsets.UTF_8);
            return MAPPER.readValue(json, RawFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read raw file: " + rawFile, e);
        }
    }

    // ============================================================
    //  Summary / Comparison
    // ============================================================

    /**
     * 落盘 Summary 为 {@code {experimentId}_summary.json}。
     */
    public static Path writeSummary(RtmpSummary summary, Path outputDir) {
        Path target = outputDir.resolve(summary.sourceExperimentId() + "_summary.json");
        atomicWrite(target, toJson(summary));
        return target;
    }

    /**
     * 落盘 Comparison 为 {@code comparison.json}（固定文件名，§1）。
     */
    public static Path writeComparison(RtmpComparison comparison, Path outputDir) {
        Path target = outputDir.resolve("comparison.json");
        atomicWrite(target, toJson(comparison));
        return target;
    }

    // ============================================================
    //  Output collision preflight（B5）
    // ============================================================

    /**
     * Experiment-level output collision preflight（Phase 5-E1 / B5）。
     * <p>
     * 正式 runner 启动前检查目标输出（{@code {experimentId}_raw.json} /
     * {@code {experimentId}_summary.json} / {@code comparison.json}）是否已存在；
     * 只要任意 canonical output 已存在即抛异常拒绝启动，<b>不</b> REPLACE_EXISTING、
     * <b>不</b>自动覆盖、<b>不</b>静默删除、<b>不</b>自动换目录。
     */
    public static void assertOutputsDoNotExist(String experimentId, Path outputDir) {
        List<Path> outputs = List.of(
                outputDir.resolve(experimentId + "_raw.json"),
                outputDir.resolve(experimentId + "_summary.json"),
                outputDir.resolve("comparison.json"));
        List<String> existing = new ArrayList<>();
        for (Path p : outputs) {
            if (Files.exists(p)) {
                existing.add(p.getFileName().toString());
            }
        }
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Output collision detected for experiment '" + experimentId + "': "
                            + existing + ". Refusing to overwrite existing experiment outputs.");
        }
    }

    // ============================================================
    //  Serialization helpers（暴露给测试做 round-trip / null 校验）
    // ============================================================

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize to JSON", e);
        }
    }

    /**
     * 单行（compact）序列化，供 checkpoint JSONL 等按行读取的 artifact 使用。
     * 与 {@link #toJson} 使用同一组模块/日期配置，但关闭 INDENT_OUTPUT。
     */
    public static String toJsonLine(Object value) {
        try {
            return COMPACT_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize to JSON", e);
        }
    }

    /**
     * 从 JSON 反序列化为指定类型（复用本层的 Jackson mapper 配置，
     * 供 checkpoint JSONL 等 recovery artifact 读取用）。
     */
    public static <T> T readJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON to " + type.getSimpleName(), e);
        }
    }

    // ============================================================
    //  Atomic write
    // ============================================================

    private static void atomicWrite(Path target, String json) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write file: " + target, e);
        }
    }

    // ============================================================
    //  Duplicate / experiment resolution
    // ============================================================

    private static void rejectDuplicateRunIds(List<RtmpRawRecord> records) {
        Set<String> seen = new HashSet<>();
        for (RtmpRawRecord r : records) {
            if (r.runId() != null && !seen.add(r.runId())) {
                throw new IllegalArgumentException("Duplicate runId in raw records: " + r.runId());
            }
        }
    }

    private static String resolveExperimentId(List<RtmpRawRecord> records) {
        Set<String> ids = new HashSet<>();
        for (RtmpRawRecord r : records) {
            if (r.experimentId() != null) {
                ids.add(r.experimentId());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("No experimentId present in raw records");
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("Mixed experimentIds in raw records: " + ids);
        }
        return ids.iterator().next();
    }
}
