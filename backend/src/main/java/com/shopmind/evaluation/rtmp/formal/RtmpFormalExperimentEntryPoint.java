package com.shopmind.evaluation.rtmp.formal;

import com.shopmind.evaluation.port.BenchmarkRunner;
import com.shopmind.evaluation.rtmp.RtmpDatasetLoader;
import com.shopmind.evaluation.rtmp.RtmpEvaluationDataset;
import com.shopmind.memory.store.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Formal experiment opt-in entry point — Phase 5-E1（§十四）。
 * <p>
 * 默认（未显式 opt-in）不执行正式实验。仅当 {@code shopmind.rtmp.formal.enabled=true}
 * 时本 runner 才被装配并执行。preflight 失败时<b>不调用 Real LLM</b>，也不偷偷调用 Pilot。
 */
@Component
@ConditionalOnProperty(name = "shopmind.rtmp.formal.enabled", havingValue = "true")
public class RtmpFormalExperimentEntryPoint implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RtmpFormalExperimentEntryPoint.class);

    private final ObjectProvider<BenchmarkRunner> benchmarkRunner;
    private final ObjectProvider<ChatMemoryStore> memoryStore;

    @Value("${shopmind.rtmp.formal.experiment-id:" + RtmpFormalExperimentConfig.DEFAULT_EXPERIMENT_ID + "}")
    private String experimentId;

    @Value("${shopmind.rtmp.formal.output-dir:experiments}")
    private String outputDir;

    public RtmpFormalExperimentEntryPoint(ObjectProvider<BenchmarkRunner> benchmarkRunner,
                                          ObjectProvider<ChatMemoryStore> memoryStore) {
        this.benchmarkRunner = benchmarkRunner;
        this.memoryStore = memoryStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        BenchmarkRunner runner = benchmarkRunner.getIfAvailable();
        if (runner == null) {
            log.error("[RtmpFormalExperiment] BenchmarkRunner bean not available; aborting formal experiment.");
            return;
        }

        RtmpEvaluationDataset dataset = RtmpDatasetLoader.load();
        Path outDir = Path.of(outputDir);
        log.info("[RtmpFormalExperiment] Opt-in formal experiment: experimentId={}, datasetCases={}, "
                        + "model={}, repetitions=3, plannedUnits={}, outputDir={}",
                experimentId, dataset.size(), RtmpFormalExperimentConfig.MODEL,
                RtmpFormalExperimentPlan.EXPECTED_UNITS, outDir.toAbsolutePath());

        ChatMemoryStore store = memoryStore.getIfAvailable();
        RtmpFormalExperimentRunner formalRunner = new RtmpFormalExperimentRunner(runner, store);

        RtmpFormalExperimentRunner.PreflightResult preflight =
                RtmpFormalExperimentRunner.preflight(experimentId, outDir, dataset, store);
        if (!preflight.valid()) {
            log.error("[RtmpFormalExperiment] Preflight failed; no Real LLM call issued. Errors: {}",
                    preflight.errors());
            return;
        }

        log.info("[RtmpFormalExperiment] Preflight passed; starting formal experiment execution...");
        RtmpFormalExperimentRunner.RtmpFormalExperimentResult result =
                formalRunner.run(experimentId, outDir);
        log.info("[RtmpFormalExperiment] Formal experiment complete: records={}, rawFile={}",
                result.recordsWritten(), result.rawFile());
    }
}
