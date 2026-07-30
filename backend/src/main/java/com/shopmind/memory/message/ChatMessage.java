package com.shopmind.memory.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 对话消息基类 — 使用 Jackson 多态反序列化，
 * 根据 "type" 字段自动区分子类。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessage.class, name = "USER"),
        @JsonSubTypes.Type(value = AiMessage.class, name = "AI"),
        @JsonSubTypes.Type(value = SystemMessage.class, name = "SYSTEM")
})
public abstract class ChatMessage {

    public abstract String getType();

    public abstract String getContent();
}
