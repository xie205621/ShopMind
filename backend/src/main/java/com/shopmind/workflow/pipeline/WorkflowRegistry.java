package com.shopmind.workflow.pipeline;

import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowDefinitionYaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Workflow Registry — Phase D.5: 自动发现并注册所有 Workflow YAML。
 * <p>
 * <b>核心思想：</b>Evaluation 不知道有哪些 Workflow。Registry 扫描
 * {@code workflows/} 目录，Evaluation 只接收 Registry 给出的列表。
 * <p>
 * <b>扫描约定：</b>
 * <pre>
 * classpath:workflows/{domain}/{version}.yaml
 * </pre>
 * 例如：{@code workflows/customer-service/v2.0.yaml} → domain=CustomerService, version=v2.0。
 * <p>
 * <b>使用方式：</b>
 * <pre>
 * for (WorkflowDefinition wf : WorkflowRegistry.listAll()) {
 *     BenchmarkRunner.run(wf, dataset);
 * }
 * </pre>
 * <p>
 * <b>线程安全：</b>扫描结果不可变，适合多线程并发读取。
 *
 * @see WorkflowDefinitionLoader
 * @see WorkflowDefinitionYaml
 */
public final class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);
    private static final String WORKFLOWS_PATTERN = "classpath:workflows/*/*.yaml";

    /** 懒加载缓存：首次调用时扫描，后续调用直接返回缓存。 */
    private static volatile List<WorkflowDefinition> CACHE = null;

    private WorkflowRegistry() { /* utility class */ }

    /**
     * 扫描并返回所有已注册的 WorkflowDefinition。
     * <p>
     * 返回按 (domain, version) 排序的不可变列表。
     * 扫描结果会被缓存，整个 JVM 生命周期内只扫描一次。
     *
     * @return 所有 WorkflowDefinition 的不可变列表
     */
    public static List<WorkflowDefinition> listAll() {
        if (CACHE != null) {
            return CACHE;
        }
        synchronized (WorkflowRegistry.class) {
            if (CACHE != null) {
                return CACHE;
            }
            CACHE = scanAndLoad();
            return CACHE;
        }
    }

    /**
     * 按 domain 过滤已注册的 WorkflowDefinition。
     *
     * @param domain 工作流域名，如 "customer-service"
     */
    public static List<WorkflowDefinition> listByDomain(String domain) {
        return listAll().stream()
                .filter(wf -> wf.id().equals(domain))
                .toList();
    }

    /**
     * 清除缓存（主要用于测试）。
     */
    static void clearCache() {
        synchronized (WorkflowRegistry.class) {
            CACHE = null;
        }
    }

    // ============================================================
    //  内部扫描实现
    // ============================================================

    private static List<WorkflowDefinition> scanAndLoad() {
        List<WorkflowDefinition> workflows = new ArrayList<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(WORKFLOWS_PATTERN);

            if (resources == null || resources.length == 0) {
                log.warn("[WorkflowRegistry] No workflow YAML files found under: {}", WORKFLOWS_PATTERN);
                return Collections.emptyList();
            }

            for (Resource resource : resources) {
                try {
                    WorkflowDefinition wf = loadFromResource(resource);
                    if (wf != null) {
                        workflows.add(wf);
                    }
                } catch (Exception e) {
                    log.error("[WorkflowRegistry] Failed to parse: {} — {}",
                            resource.getFilename(), e.getMessage());
                }
            }

            // 按 (id, version) 排序，保证扫描结果可重复
            workflows.sort(Comparator.comparing(WorkflowDefinition::id)
                    .thenComparing(WorkflowDefinition::version));

        } catch (IOException e) {
            log.error("[WorkflowRegistry] Failed to scan workflows: {}", e.getMessage());
        }

        log.info("[WorkflowRegistry] Registered {} workflows across {} domains",
                workflows.size(),
                workflows.stream().map(WorkflowDefinition::id).distinct().count());

        return Collections.unmodifiableList(workflows);
    }

    /**
     * 从 Resource 解析 WorkflowDefinition。
     * <p>
     * 从路径中提取 domain 和 version：
     * {@code workflows/{domain}/{version}.yaml}
     */
    private static WorkflowDefinition loadFromResource(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        String filename = resource.getFilename();
        if (filename == null) return null;

        // 从文件路径提取 domain 和 version
        // 路径格式: .../workflows/{domain}/{version}.yaml
        String[] parts = path.replace('\\', '/').split("/");
        String version = filename.endsWith(".yaml")
                ? filename.substring(0, filename.length() - 5)
                : filename;

        // domain 是倒数第二个目录名
        String domain = null;
        for (int i = parts.length - 2; i >= 0; i--) {
            if ("workflows".equals(parts[i]) && i + 1 < parts.length) {
                domain = parts[i + 1];
                break;
            }
        }

        if (domain == null) {
            log.warn("[WorkflowRegistry] Could not extract domain from path: {}", path);
            return null;
        }

        log.debug("[WorkflowRegistry] Scanning: domain={}, version={}", domain, version);

        try (InputStream is = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(is);
            return WorkflowDefinitionYaml.fromMap(map);
        }
    }
}
