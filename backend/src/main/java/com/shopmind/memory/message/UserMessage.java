package com.shopmind.memory.message;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class UserMessage extends ChatMessage {

    private String content;

    public UserMessage() {}

    public UserMessage(String content) {
        this.content = content;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public String getType() {
        return "USER";
    }
}
