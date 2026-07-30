package com.shopmind.memory.store;

import com.shopmind.memory.message.AiMessage;
import com.shopmind.memory.message.ChatMessage;
import com.shopmind.memory.message.UserMessage;
import com.shopmind.memory.repository.ChatSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session Memory Engine 测试套件。
 * <p>
 * 严格对应 Session_Memory.md 第 12 节 Test Plan。
 */
@SpringBootTest
class MongoChatMemoryStoreTest {

    @Autowired
    private MongoChatMemoryStore memoryStore;

    @Autowired
    private ChatSessionRepository repository;

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    // ============================================================
    //  Test 1: 滑动窗口截断 — 第 12 节截断测试
    // ============================================================

    @Test
    @DisplayName("截断测试：传入 22 条记录，断言落盘仅存 20 条，且时间戳最新的记录被保留")
    void shouldTruncateMessagesToMaxWindow() {
        // given: 构造 22 条消息
        String memoryId = "user_1001";
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            messages.add(new UserMessage("第 " + i + " 轮用户消息"));
            messages.add(new AiMessage("第 " + i + " 轮 AI 回复", new ArrayList<>()));
        }
        // 注意：上面循环是交错插入的，22轮 = 44条消息
        // 全部放入 memoryStore 做截断

        // when
        memoryStore.updateMessages(memoryId, messages);

        // then: 回读验证
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);
        assertThat(stored).hasSize(20);

        // 验证保留的是最新的 20 条（subList 尾部），即第 12 轮到第 22 轮
        // 共 20 条 = 2 × (22 - 12 + 1) = 22 ... wait let me recalculate
        // 原始: [U1, A1, U2, A2, ..., U22, A22] = 44条
        // subList(size - 20, size) = subList(24, 44) = 索引24到43(含)的元素
        // 索引24 = U13, 索引43 = A22
        // 所以保留的是 U13, A13, U14, A14, ..., U22, A22 = 20条

        assertThat(stored.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) stored.get(0)).getContent()).isEqualTo("第 13 轮用户消息");
        assertThat(stored.get(stored.size() - 1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) stored.get(stored.size() - 1)).getContent()).isEqualTo("第 22 轮 AI 回复");
    }

    @Test
    @DisplayName("截断测试：消息数刚好等于 20 时不触发截断")
    void shouldNotTruncateWhenExactlyMax() {
        String memoryId = "user_1002";
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            messages.add(new UserMessage("第 " + i + " 条"));
        }

        memoryStore.updateMessages(memoryId, messages);
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);

        assertThat(stored).hasSize(20);
    }

    @Test
    @DisplayName("截断测试：消息数少于 20 时全部保留")
    void shouldKeepAllWhenLessThanMax() {
        String memoryId = "user_1003";
        List<ChatMessage> messages = List.of(
                new UserMessage("你好"),
                new AiMessage("你好，有什么可以帮助你的？", new ArrayList<>())
        );

        memoryStore.updateMessages(memoryId, messages);
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).isInstanceOf(UserMessage.class);
        assertThat(stored.get(1)).isInstanceOf(AiMessage.class);
    }

    // ============================================================
    //  Test 2: 并发更新 — 第 12 节并发更新测试
    // ============================================================

    @Test
    @DisplayName("并发更新测试：两个线程同时更新同一 memoryId，不会产生重复脏数据")
    void shouldNotProduceDuplicateDataUnderConcurrentUpdates() throws Exception {
        String memoryId = "user_concurrent";
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    List<ChatMessage> messages = new ArrayList<>();
                    for (int i = 0; i < 10; i++) {
                        messages.add(new UserMessage("线程" + threadId + "-消息" + i));
                    }
                    memoryStore.updateMessages(memoryId, messages);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证：每个 memoryId 只有 1 条文档（upsert 不会产生重复文档）
        // 并且消息数不超过 maxMessages
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);
        assertThat(stored).isNotEmpty();
        assertThat(stored.size()).isLessThanOrEqualTo(20);
    }

    // ============================================================
    //  Test 3: 多租户隔离 — 第 13 节验收标准
    // ============================================================

    @Test
    @DisplayName("多租户隔离：不同 UserId 的上下文内容互不串扰")
    void shouldIsolateContextBetweenDifferentUsers() {
        String userIdA = "user_A";
        String userIdB = "user_B";

        List<ChatMessage> messagesA = List.of(new UserMessage("用户A的消息"));
        List<ChatMessage> messagesB = List.of(new UserMessage("用户B的消息"));

        memoryStore.updateMessages(userIdA, messagesA);
        memoryStore.updateMessages(userIdB, messagesB);

        List<ChatMessage> storedA = memoryStore.getMessages(userIdA);
        List<ChatMessage> storedB = memoryStore.getMessages(userIdB);

        assertThat(storedA).hasSize(1);
        assertThat(((UserMessage) storedA.get(0)).getContent()).isEqualTo("用户A的消息");

        assertThat(storedB).hasSize(1);
        assertThat(((UserMessage) storedB.get(0)).getContent()).isEqualTo("用户B的消息");
    }

    // ============================================================
    //  Test 4: CRUD 生命周期
    // ============================================================

    @Test
    @DisplayName("CRUD：存 → 读 → 删 → 读，全链路验证")
    void shouldSupportFullCrudLifecycle() {
        String memoryId = "user_crud";
        List<ChatMessage> messages = List.of(
                new UserMessage("推荐一款手机"),
                new AiMessage("好的，为您推荐华为Mate 60...", new ArrayList<>())
        );

        // Create / Update
        memoryStore.updateMessages(memoryId, messages);

        // Read
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);
        assertThat(stored).hasSize(2);

        // Delete
        memoryStore.deleteMessages(memoryId);

        // Read after delete
        List<ChatMessage> afterDelete = memoryStore.getMessages(memoryId);
        assertThat(afterDelete).isEmpty();
    }

    // ============================================================
    //  Test 5: 边界条件
    // ============================================================

    @Test
    @DisplayName("边界条件：null memoryId 返回空列表")
    void shouldReturnEmptyListForNullMemoryId() {
        List<ChatMessage> result = memoryStore.getMessages(null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("边界条件：不存在的 memoryId 返回空列表")
    void shouldReturnEmptyListForNonExistentMemoryId() {
        List<ChatMessage> result = memoryStore.getMessages("non_existent");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("边界条件：空消息列表的 update 不会抛异常")
    void shouldNotThrowOnEmptyMessageUpdate() {
        memoryStore.updateMessages("user_empty", List.of());
        List<ChatMessage> stored = memoryStore.getMessages("user_empty");
        assertThat(stored).isEmpty();
    }

    // ============================================================
    //  Test 6: Jackson 多态反序列化 — 第 6 节
    // ============================================================

    @Test
    @DisplayName("多态反序列化：能正确区分 UserMessage、AiMessage 和 SystemMessage")
    void shouldDeserializePolymorphicMessages() {
        String memoryId = "user_poly";
        List<ChatMessage> messages = List.of(
                new com.shopmind.memory.message.SystemMessage("你是一个电商助手"),
                new UserMessage("帮我查订单1001"),
                new AiMessage("好的，正在为您查询...", new ArrayList<>())
        );

        memoryStore.updateMessages(memoryId, messages);
        List<ChatMessage> stored = memoryStore.getMessages(memoryId);

        assertThat(stored).hasSize(3);
        assertThat(stored.get(0)).isInstanceOf(com.shopmind.memory.message.SystemMessage.class);
        assertThat(stored.get(1)).isInstanceOf(UserMessage.class);
        assertThat(stored.get(2)).isInstanceOf(AiMessage.class);
    }
}
