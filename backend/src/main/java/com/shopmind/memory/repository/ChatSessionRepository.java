package com.shopmind.memory.repository;

import com.shopmind.memory.document.ChatSessionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MongoDB Repository — 提供基础 CRUD。
 * <p>
 * upsert 原生逻辑由 {@link com.shopmind.memory.store.MongoChatMemoryStore}
 * 结合 MongoTemplate 实现。
 */
@Repository
public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {

    /**
     * 根据 memoryId 查询会话文档。
     */
    Optional<ChatSessionDocument> findByMemoryId(String memoryId);

    /**
     * 根据 memoryId 删除会话文档。
     */
    void deleteByMemoryId(String memoryId);
}
