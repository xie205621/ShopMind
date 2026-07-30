package com.shopmind.memory.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AiMessage extends ChatMessage {

    private String content;

    private List<ToolCall> toolCalls = new ArrayList<>();

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public String getType() {
        return "AI";
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;
    }
}
