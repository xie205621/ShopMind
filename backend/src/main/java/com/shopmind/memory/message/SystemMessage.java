package com.shopmind.memory.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class SystemMessage extends ChatMessage {

    private String content;

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public String getType() {
        return "SYSTEM";
    }
}
