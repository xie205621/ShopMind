package com.shopmind.mcp.registry;

import com.shopmind.mcp.annotation.McpParam;
import com.shopmind.mcp.annotation.McpTool;
import com.shopmind.mcp.model.ParameterSpec;
import com.shopmind.mcp.model.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册中心 — MCP_Engine.md 第 10 节规范。
 * <p>
 * 利用 Spring {@link BeanPostProcessor} 在系统启动阶段完成以下工作：
 * <ol>
 *   <li>扫描所有带 {@code @McpTool} 注解的方法</li>
 *   <li>解析 {@code @McpParam} 提取参数描述</li>
 *   <li>将工具元数据缓存到 {@code toolRegistry}（常驻内存，不落盘）</li>
 * </ol>
 * <p>
 * <b>注册时机</b>：在 Bean 初始化之后（{@code postProcessAfterInitialization}），
 * 确保目标 Bean 已完成依赖注入。
 */
@Component
public class ToolRegistry implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 工具注册表 — key=工具名, value=工具规格。
     * 使用 ConcurrentHashMap 保证启动阶段的线程安全。
     */
    private final Map<String, ToolSpecification> registry = new ConcurrentHashMap<>();

    /**
     * 在 Bean 初始化后扫描该 Bean 上所有带 @McpTool 的方法并注册。
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        // 遍历所有声明的方法（包括私有方法，但 @McpTool 理应标记在 public 方法上）
        for (Method method : clazz.getDeclaredMethods()) {
            McpTool toolAnnotation = method.getAnnotation(McpTool.class);
            if (toolAnnotation == null) {
                continue;
            }

            String toolName = toolAnnotation.name();
            String description = toolAnnotation.description();

            // 解析参数列表
            List<ParameterSpec> paramSpecs = buildParameterSpecs(method);

            // 注册前检查工具名是否重复
            if (registry.containsKey(toolName)) {
                ToolSpecification existing = registry.get(toolName);
                log.error("[MCP] Duplicate tool name '{}' detected! "
                                + "Existing: {}.{} | Conflict: {}.{}",
                        toolName,
                        existing.getTargetBean().getClass().getSimpleName(),
                        existing.getTargetMethod().getName(),
                        clazz.getSimpleName(),
                        method.getName());
                throw new IllegalStateException(
                        "Duplicate MCP tool name: " + toolName);
            }

            ToolSpecification spec = ToolSpecification.builder()
                    .toolName(toolName)
                    .description(description)
                    .targetBean(bean)
                    .targetMethod(method)
                    .parameters(paramSpecs)
                    .build();

            registry.put(toolName, spec);
            log.info("[MCP] Registered tool '{}' -> {}.{}({})",
                    toolName, clazz.getSimpleName(), method.getName(), paramSpecs.size());
        }
        return bean; // 必须返回 bean，否则会破坏 Spring 容器
    }

    /**
     * 根据工具名查找工具规格。
     *
     * @param toolName 工具名
     * @return 工具规格，不存在则返回 null
     */
    public ToolSpecification getTool(String toolName) {
        return registry.get(toolName);
    }

    /**
     * 获取所有已注册工具的只读快照。
     */
    public List<ToolSpecification> getAllTools() {
        return Collections.unmodifiableList(new ArrayList<>(registry.values()));
    }

    /**
     * 获取已注册工具总数。
     */
    public int getToolCount() {
        return registry.size();
    }

    // ============================================================
    //  私有方法
    // ============================================================

    /**
     * 解析方法的参数列表，生成 ParameterSpec 列表。
     * <p>
     * 注意：Java 字节码默认不保留参数名（需编译参数 -parameters），
     * 这里使用 arg{N} 作为后备名称，类型从 Parameter.getType() 获取。
     */
    private List<ParameterSpec> buildParameterSpecs(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return Collections.emptyList();
        }

        List<ParameterSpec> specs = new ArrayList<>(parameters.length);
        for (Parameter param : parameters) {
            McpParam paramAnnotation = param.getAnnotation(McpParam.class);

            // 从 @McpParam 获取元数据，不存在则使用默认值
            boolean required = paramAnnotation != null && paramAnnotation.required();
            String desc = paramAnnotation != null ? paramAnnotation.description() : "";
            // 优先使用 @McpParam 的 name，否则使用反射获取的实际参数名
            String paramName = param.getName();

            ParameterSpec spec = ParameterSpec.builder()
                    .name(paramName)
                    .type(param.getType().getSimpleName())
                    .required(required)
                    .description(desc)
                    .build();
            specs.add(spec);
        }
        return specs;
    }
}
