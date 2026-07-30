package com.shopmind.memory.store;

import com.shopmind.memory.message.ChatMessage;

import java.util.List;

/**
 * 会话记忆存储接口 — Session_Memory.md 第 9 节规范。
 * <p>
 * 向上层 Agent 屏蔽底层 MongoDB 查询细节。
 */
public interface ChatMemoryStore {

    /**
     * 恢复上下文：根据 memoryId 提取历史对话列表。
     *
     * @param memoryId 会话记忆标识（如 "user_1001"）
     * @return 历史消息列表，无记录时返回空列表
     */
    List<ChatMessage> getMessages(Object memoryId);

    /**
     * 覆写上下文：原子更新会话快照。
     *
     * @param memoryId 会话记忆标识
     * @param messages 最新消息列表
     */
    void updateMessages(Object memoryId, List<ChatMessage> messages);

    /**
     * 清除会话：删除指定会话的全部记忆。
     *
     * @param memoryId 会话记忆标识
     */
    void deleteMessages(Object memoryId);
}
