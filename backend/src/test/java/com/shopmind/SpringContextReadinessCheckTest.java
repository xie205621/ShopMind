package com.shopmind;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.shopmind.evaluation.rtmp.formal.RtmpFormalExperimentEntryPoint;
import com.shopmind.memory.store.ChatMemoryStore;
import com.shopmind.memory.store.MongoChatMemoryStore;
import com.shopmind.orchestrator.adapter.DashScopeChatAdapter;
import com.shopmind.orchestrator.port.ChatModelPort;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final Experiment Readiness Gate — 非正式实验的 Spring context readiness check。
 * <p>
 * 目的：在正式实验启动前，验证「qwen profile + formal disabled」下：
 * <ol>
 *   <li>Spring context 能正常启动</li>
 *   <li>{@link MongoChatMemoryStore} bean 成功创建</li>
 *   <li>真实 Mongo 可连接 {@code localhost:27017}</li>
 *   <li>不装配 / 不执行任何正式实验（formal opt-in 关闭）</li>
 * </ol>
 * <p>
 * 本测试<b>不调用 Real LLM</b>、<b>不执行 {@link RtmpFormalExperimentRunner#run}</b>、
 * <b>不产生任何正式 Raw 数据</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("qwen")
class SpringContextReadinessCheckTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatMemoryStore chatMemoryStore;

    @Autowired
    private ChatModelPort chatModelPort;

    @Test
    @DisplayName("1. Spring context 能正常启动")
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getStartupDate()).isPositive();
    }

    @Test
    @DisplayName("2. MongoChatMemoryStore bean 成功创建")
    void mongoChatMemoryStoreBeanCreated() {
        assertThat(chatMemoryStore).isNotNull();
        assertThat(chatMemoryStore).isInstanceOf(MongoChatMemoryStore.class);
    }

    @Test
    @DisplayName("3. qwen profile 激活（ChatModelPort 为 DashScopeChatAdapter）")
    void qwenProfileActive() {
        assertThat(chatModelPort).isInstanceOf(DashScopeChatAdapter.class);
    }

    @Test
    @DisplayName("4. formal experiment 未装配（opt-in 关闭，不执行正式实验）")
    void formalExperimentNotLoaded() {
        RtmpFormalExperimentEntryPoint entry = applicationContext
                .getBeanProvider(RtmpFormalExperimentEntryPoint.class).getIfAvailable();
        assertThat(entry).isNull();
    }

    @Test
    @DisplayName("5. 真实 Mongo 可连接 localhost:27017")
    void realMongoReachableAtLocalhost() {
        try (MongoClient client = MongoClients.create(
                "mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            Document result = client.getDatabase("shopmind")
                    .runCommand(new Document("ping", 1));
            Number ok = result.get("ok", Number.class);
            assertThat(ok.doubleValue()).isEqualTo(1.0);
        }
    }
}
