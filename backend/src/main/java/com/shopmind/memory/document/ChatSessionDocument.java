package com.shopmind.memory.document;

import com.shopmind.memory.message.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 实体 — 映射集合 chat_session_memory。
 * <p>
 * 数据结构参见 Session_Memory.md 第 8 节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_session_memory")
public class ChatSessionDocument {

    @Id
    private String id;

    /** 主键索引：对应 memoryId（如 "user_1001"） */
    @Field("memory_id")
    private String memoryId;

    /** 对话消息列表（Jackson 多态序列化） */
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    /** 最近更新时间 */
    @Field("updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public List<ChatMessage> getMessages() {
        return messages;
    }
}
