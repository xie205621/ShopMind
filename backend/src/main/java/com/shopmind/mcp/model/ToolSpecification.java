package com.shopmind.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 工具规格描述 — 对应 MCP_Engine.md 第 8 节 ToolRegistry Schema 结构。
 * <p>
 * 注册中心启动时扫描 @McpTool 生成，常驻应用内存（不落盘）。
 * 包含工具名、描述、目标方法元信息、参数列表等完整契约。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpecification {

    /** 工具全局唯一名称 */
    private String toolName;

    /** 工具功能描述（供 LLM Prompt 使用） */
    private String description;

    /** 目标 Bean 实例（Spring 管理的单例） */
    private Object targetBean;

    /** 目标方法（java.lang.reflect.Method） */
    private Method targetMethod;

    /** 参数描述列表 */
    private List<ParameterSpec> parameters;

    public String getToolName() {
        return toolName;
    }

    public String getDescription() {
        return description;
    }

    public Object getTargetBean() {
        return targetBean;
    }

    public Method getTargetMethod() {
        return targetMethod;
    }

    public List<ParameterSpec> getParameters() {
        return parameters;
    }

    public void setToolName(String toolName) { this.toolName = toolName; }
    public void setDescription(String description) { this.description = description; }
    public void setTargetBean(Object targetBean) { this.targetBean = targetBean; }
    public void setTargetMethod(Method targetMethod) { this.targetMethod = targetMethod; }
    public void setParameters(List<ParameterSpec> parameters) { this.parameters = parameters; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String toolName;
        private String description;
        private Object targetBean;
        private Method targetMethod;
        private List<ParameterSpec> parameters;

        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder targetBean(Object targetBean) { this.targetBean = targetBean; return this; }
        public Builder targetMethod(Method targetMethod) { this.targetMethod = targetMethod; return this; }
        public Builder parameters(List<ParameterSpec> parameters) { this.parameters = parameters; return this; }

        public ToolSpecification build() {
            ToolSpecification obj = new ToolSpecification();
            obj.setToolName(toolName);
            obj.setDescription(description);
            obj.setTargetBean(targetBean);
            obj.setTargetMethod(targetMethod);
            obj.setParameters(parameters);
            return obj;
        }
    }
}
