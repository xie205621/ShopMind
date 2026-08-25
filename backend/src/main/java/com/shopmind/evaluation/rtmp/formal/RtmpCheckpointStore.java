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
import java.util.List;
import java.util.stream.Stream;

/**
 * Execution checkpoint 持久化层 — Phase 5-R6。
 * <p>
 * 采用 JSONL append（每完成一个 canonical unit 追加一行），并：
 * <ul>
 *   <li>每行写完后立即 {@code force(true)}（fsync）做 durable flush；</li>
 *   <li>发生 {@link IOException} 时抛出 {@link UncheckedIOException}，<b>绝不假装成功</b>；</li>
 *   <li>由上层负责拒绝 same runId 重复 checkpoint（见 {@link RtmpFormalExperimentRunner}）。</li>
 * </ul>
 * checkpoint 是 recovery artifact，不得直接作为统计输入；final Raw 仍由 checkpoint 重建后经
 * {@code RtmpExperimentValidator} 校验落盘。
 */
public final class RtmpCheckpointStore {

    public static final String CHECKPOINT_SCHEMA_VERSION = "rtmp-checkpoint-v1";

    private RtmpCheckpointStore() {
    }

    /** checkpoint 文件路径：{@code {experimentId}_checkpoint.jsonl}。 */
    public static Path checkpointFile(Path outputDir, String experimentId) {
        return outputDir.resolve(experimentId + "_checkpoint.jsonl");
    }

    /**
     * 追加一条 completed checkpoint（JSONL 一行），随后 fsync。
     * <p>
     * IO 失败时抛出异常（不静默吞掉），由上层终止 formal execution。
     */
    public static void append(Path file, RtmpExecutionCheckpoint checkpoint) {
        String line = RtmpExperimentPersistence.toJsonLine(checkpoint) + "\n";
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
                    "Checkpoint append failed for runId=" + checkpoint.runId(), e);
        }
    }

    /**
     * 读取全部 checkpoint（每行一条 JSON）。文件不存在返回空列表。
     * <p>
     * 非法 JSON / 错误 schema version 立即抛异常（不静默跳过）。
     */
    public static List<RtmpExecutionCheckpoint> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<RtmpExecutionCheckpoint> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> out.add(parse(line)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read checkpoint file: " + file, e);
        }
        return out;
    }

    private static RtmpExecutionCheckpoint parse(String line) {
        RtmpExecutionCheckpoint cp =
                RtmpExperimentPersistence.readJson(line, RtmpExecutionCheckpoint.class);
        if (cp == null || !CHECKPOINT_SCHEMA_VERSION.equals(cp.schemaVersion())) {
            throw new IllegalStateException("Invalid checkpoint schema in line: " + line);
        }
        return cp;
    }
}
