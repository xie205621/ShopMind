package com.shopmind.memory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoTimeoutException;
import com.shopmind.memory.document.ChatSessionDocument;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Session Memory Engine 核心实现。
 * <p>
 * 严格遵循 Session_Memory.md 规范：
 * <ul>
 *   <li>MongoDB upsert 原子覆写，绝不先 delete 再 insert</li>
 *   <li>Jackson 多态反序列化：区分 UserMessage / AiMessage / SystemMessage</li>
 *   <li>滑动窗口截断：默认保留最近 20 条消息（FIFO）</li>
 *   <li>MongoDB 故障兜底：返回空列表，不抛异常中断主流程</li>
 * </ul>
 */
@Component
public class MongoChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MongoChatMemoryStore.class);

    private final ChatSessionRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    /** 滑动窗口大小，默认 20 */
    @Value("${shopmind.memory.max-messages:20}")
    private int maxMessages;

    public MongoChatMemoryStore(ChatSessionRepository repository,
                                MongoTemplate mongoTemplate,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    //  getMessages — 恢复上下文
    // ============================================================

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) {
            return Collections.emptyList();
        }

        String id = memoryId.toString();
        try {
            Optional<ChatSessionDocument> docOpt = repository.findByMemoryId(id);
            return docOpt.map(ChatSessionDocument::getMessages)
                    .orElse(Collections.emptyList());
        } catch (MongoTimeoutException e) {
            // 规范 §11：MongoDB 连接故障 → 打 Error 日志，返回空上下文，不中断主流程
            log.error("[Memory] MongoDB timeout while loading memory for '{}'. "
                    + "Returning empty context to keep conversation alive.", id, e);
            return Collections.emptyList();
        } catch (DataAccessException e) {
            // Spring Data MongoDB 在 Jackson 反序列化失败时会抛出 DataAccessException
            if (containsJsonMappingException(e)) {
                return handleJsonMappingFailure(id, e);
            }
            log.error("[Memory] Data access error while loading memory for '{}'. "
                    + "Returning empty context.", id, e);
            return Collections.emptyList();
        }
    }

    /**
     * 递归检查异常链中是否包含 JsonMappingException。
     */
    private boolean containsJsonMappingException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof JsonMappingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // ============================================================
    //  updateMessages — 原子覆写（upsert + 滑动窗口截断）
    // ============================================================

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null) {
            return;
        }

        String id = memoryId.toString();

        // 滑动窗口截断：FIFO，保留最近 maxMessages 条
        List<ChatMessage> truncated = applySlidingWindow(messages);

        Instant now = Instant.now();

        // 将 truncated 序列化为 BSON，用于检测 JSON 兼容性
        try {
            objectMapper.writeValueAsString(truncated);
        } catch (JsonProcessingException e) {
            log.error("[Memory] Failed to serialize messages for '{}'. "
                    + "Aborting update to prevent data corruption.", id, e);
            return;
        }

        // MongoDB upsert：绝不先 delete 再 insert
        Query query = new Query(Criteria.where("memory_id").is(id));
        Update update = new Update()
                .set("memory_id", id)
                .set("messages", truncated)
                .set("updated_at", now);

        try {
            mongoTemplate.upsert(query, update, ChatSessionDocument.class);
            log.debug("[Memory] Upserted {} messages for '{}'", truncated.size(), id);
        } catch (MongoTimeoutException e) {
            log.error("[Memory] MongoDB timeout during upsert for '{}'. "
                    + "Messages not persisted.", id, e);
            // 不抛异常 — 规范要求不中断主流程
        }
    }

    // ============================================================
    //  deleteMessages — 清除会话
    // ============================================================

    @Override
    public void deleteMessages(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        String id = memoryId.toString();
        try {
            repository.deleteByMemoryId(id);
            log.debug("[Memory] Deleted session for '{}'", id);
        } catch (MongoTimeoutException e) {
            log.error("[Memory] MongoDB timeout while deleting session '{}'.", id, e);
            // 不抛异常
        }
    }

    // ============================================================
    //  私有方法
    // ============================================================

    /**
     * 滑动窗口截断：FIFO 保留最新 N 条。
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        if (messages.size() <= maxMessages) {
            return messages;
        }
        int fromIndex = messages.size() - maxMessages;
        log.debug("[Memory] Sliding window triggered: {} -> {} messages",
                messages.size(), maxMessages);
        // subList 是视图，必须 new ArrayList 拷贝以避免原 list 被修改时产生 ConcurrentModificationException
        return new ArrayList<>(messages.subList(fromIndex, messages.size()));
    }

    /**
     * 当 JSON 反序列化失败时的兜底策略（规范 §11）：
     * 清空 messages 字段，重置为新会话。
     * <p>
     * 调用时机：在 getMessages 过程中，MongoDB 读出的 BSON 无法被 Jackson
     * 反序列化为 ChatMessage 子类时触发。
     */
    private List<ChatMessage> handleJsonMappingFailure(String memoryId, Throwable e) {
        log.warn("[Memory] JSON mapping failure for '{}', resetting to empty session. "
                + "Cause: {}", memoryId, e.getMessage());
        // 清空该 memoryId 的消息，重置为新会话
        try {
            Query query = new Query(Criteria.where("memory_id").is(memoryId));
            Update update = new Update()
                    .set("messages", Collections.emptyList())
                    .set("updated_at", Instant.now());
            mongoTemplate.upsert(query, update, ChatSessionDocument.class);
        } catch (Exception ex) {
            log.error("[Memory] Failed to reset corrupted session for '{}'", memoryId, ex);
        }
        return Collections.emptyList();
    }
}
