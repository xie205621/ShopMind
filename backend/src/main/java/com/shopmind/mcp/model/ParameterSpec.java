package com.shopmind.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具参数描述 — 对应 MCP_Engine.md 第 8 节 parameters 结构。
 * <p>
 * 系统启动时由 ToolRegistry 从 @McpParam 注解解析生成，
 * 运行时作为 ToolSchema 的一部分返回给 LLM。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterSpec {

    /** 参数名称（从字节码反射获取） */
    private String name;

    /** Java 类型简名（如 String、int），供 LLM 参考 */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 参数语义描述，由 @McpParam 注解提供 */
    private String description;

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public void setName(String name) { this.name = name; }
    public void setType(String type) { this.type = type; }
    public void setRequired(boolean required) { this.required = required; }
    public void setDescription(String description) { this.description = description; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private String type;
        private boolean required;
        private String description;

        public Builder name(String name) { this.name = name; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder required(boolean required) { this.required = required; return this; }
        public Builder description(String description) { this.description = description; return this; }

        public ParameterSpec build() {
            ParameterSpec obj = new ParameterSpec();
            obj.setName(name);
            obj.setType(type);
            obj.setRequired(required);
            obj.setDescription(description);
            return obj;
        }
    }
}
