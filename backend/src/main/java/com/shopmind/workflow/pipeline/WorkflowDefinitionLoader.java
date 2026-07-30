package com.shopmind.workflow.pipeline;

import com.shopmind.workflow.domain.WorkflowDefinition;
import com.shopmind.workflow.domain.WorkflowDefinitionYaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * 工作流定义加载器 — 从 classpath 下的 YAML 文件加载 WorkflowDefinition。
 * <p>
 * <b>设计决策：</b>使用 SnakeYAML（Spring Boot 内置），无需额外依赖。
 * 不使用 Spring {@code @Component}，因为评测测试不启动 Spring 上下文。
 * 后续 Phase B 可通过 Spring {@code @Bean} 封装。
 * <p>
 * <b>文件约定：</b>
 * <pre>
 * classpath:workflows/{workflowId}/{version}.yaml
 * 例: workflows/customer-service/v2.0.yaml
 * </pre>
 * <p>
 * <b>线程安全：</b>无状态静态工具类。
 */
public final class WorkflowDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionLoader.class);

    private static final String WORKFLOWS_BASE = "workflows/";

    private WorkflowDefinitionLoader() { /* utility class */ }

    /**
     * 加载指定 ID 和版本的工作流定义。
     *
     * @param workflowId 工作流标识，如 "customer-service"
     * @param version    语义化版本，如 "v2.0"
     * @return 解析后的不可变 WorkflowDefinition
     * @throws IllegalArgumentException 如果 YAML 文件不存在或格式错误
     */
    public static WorkflowDefinition load(String workflowId, String version) {
        String resourcePath = WORKFLOWS_BASE + workflowId + "/" + version + ".yaml";

        log.debug("[WorkflowLoader] Loading workflow from classpath: {}", resourcePath);

        try (InputStream is = WorkflowDefinitionLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {

            if (is == null) {
                throw new IllegalArgumentException(
                        "Workflow YAML not found: " + resourcePath
                        + " — please create " + resourcePath + " under src/main/resources/");
            }

            // SnakeYAML 泛型加载（兼容 1.x 和 2.x API）
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(is);

            // 委托 WorkflowDefinitionYaml 完成 Map → 领域对象转换
            // 包括 toolRules → List<ToolRule> 和 constraints → List<Policy> 的嵌套解析
            WorkflowDefinition wf = WorkflowDefinitionYaml.fromMap(map);

            log.info("[WorkflowLoader] Loaded workflow: id={}, version={}, personaLength={}, toolRules={}, constraints={}",
                    wf.id(), wf.version(),
                    wf.persona() != null ? wf.persona().length() : 0,
                    wf.toolRules().size(), wf.constraints().size());

            return wf;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse workflow YAML: " + resourcePath + " — " + e.getMessage(), e);
        }
    }
}
