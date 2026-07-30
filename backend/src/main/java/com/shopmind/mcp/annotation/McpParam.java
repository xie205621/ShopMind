package com.shopmind.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 参数注解 — 标记工具方法的参数，为 LLM 提供参数描述。
 * <p>
 * 必须与 {@link McpTool} 配合使用，标注在方法参数上。
 * 系统启动时，ToolRegistry 会解析此注解生成 ParameterSpec 供 LLM 参考。
 *
 * @see com.shopmind.mcp.annotation.McpTool
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpParam {

    /**
     * 是否必填，默认 false。
     * LLM 收到此信息后，会在准备调用时确保传入必填参数。
     */
    boolean required() default false;

    /**
     * 参数描述，向 LLM 说明该参数的含义和格式约束。
     * 描述越精确，LLM 传参越准确。
     */
    String description() default "";
}
