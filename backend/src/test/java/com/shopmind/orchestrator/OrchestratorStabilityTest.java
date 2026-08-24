package com.shopmind.orchestrator;

import com.shopmind.orchestrator.domain.ChatStreamEvent;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.exception.LlmProviderTimeoutException;
import com.shopmind.orchestrator.port.ChatModelPort;
import com.shopmind.orchestrator.port.ChatStreamingPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * P1-2 稳定性与异常处理验收测试。
 * <p>
 * 覆盖：LLM 超时 / LLM API 异常 / Circuit Breaker / Retry / SSE 错误事件。
 * 通过 {@code @MockBean ChatModelPort} 替换真实 LLM 适配器，注入可控的异常/挂起行为；
 * 通过 {@code @TestPropertySource} 将 LLM 超时缩短到 300ms，避免测试等待真实超时。
 * <p>
 * Tool 超时 / Tool 异常已由 {@code McpEngineTest} 覆盖，此处不重复。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "shopmind.llm.timeout-ms=300"
})
class OrchestratorStabilityTest {

    @Autowired
    private ChatStreamingPort streamingPort;

    @MockBean
    private ChatModelPort chatModelPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry.circuitBreaker("llmProvider").reset();
        when(chatModelPort.modelName()).thenReturn("mock");
    }

    // ============================================================
    //  场景 1b + 8 + 9：LLM 超时异常 → Retry → 降级 Error 事件
    // ============================================================

    @Test
    @DisplayName("LLM 超时异常：重试 2 次后降级为 TIMEOUT Error 事件并正常结束")
    void llmTimeoutExceptionRetriesThenDegrades() {
        AtomicInteger attempts = new AtomicInteger();
        when(chatModelPort.stream(anyList(), anyList()))
                .thenReturn(Flux.defer(() -> {
                    attempts.incrementAndGet();
                    return Flux.error(new LlmProviderTimeoutException("simulated timeout"));
                }));

        Flux<ChatStreamEvent> events = streamingPort.stream(
                new OrchestrationRequest("stability_timeout", "你好"));

        StepVerifier.create(events)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Intent)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Error err
                        && "TIMEOUT".equals(err.code()))
                .verifyComplete();

        // Retry 只对 LlmProviderTimeoutException 生效：1 原始 + 2 重试 = 3 次订阅
        assertThat(attempts.get()).isEqualTo(3);
    }

    // ============================================================
    //  场景 2 + 8 + 9：LLM API 异常 → 不重试 → 降级 Error 事件
    // ============================================================

    @Test
    @DisplayName("LLM API 异常（5xx）：不重试，直接降级为 LLM_ERROR Error 事件")
    void llmApiExceptionDegradesWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        when(chatModelPort.stream(anyList(), anyList()))
                .thenReturn(Flux.defer(() -> {
                    attempts.incrementAndGet();
                    return Flux.error(new RuntimeException("simulated 500"));
                }));

        Flux<ChatStreamEvent> events = streamingPort.stream(
                new OrchestrationRequest("stability_api_error", "你好"));

        StepVerifier.create(events)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Intent)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Error err
                        && "LLM_ERROR".equals(err.code()))
                .verifyComplete();

        // 非超时异常不重试：仅 1 次订阅
        assertThat(attempts.get()).isEqualTo(1);
    }

    // ============================================================
    //  场景 1a + 9：LLM 挂起（无响应）→ 真实超时 → 不无限等待
    // ============================================================

    @Test
    @DisplayName("LLM 挂起（无响应）：超时后降级并正常结束，不无限等待")
    void llmHangsThenTimeoutDegrades() {
        when(chatModelPort.stream(anyList(), anyList()))
                .thenReturn(Flux.never());

        Flux<ChatStreamEvent> events = streamingPort.stream(
                new OrchestrationRequest("stability_hang", "你好"));

        StepVerifier.create(events)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Intent)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Error)
                .verifyComplete();
    }

    // ============================================================
    //  场景 7：Circuit Breaker 连续失败 → OPEN
    // ============================================================

    @Test
    @DisplayName("Circuit Breaker：连续失败后进入 OPEN 状态")
    void circuitBreakerOpensAfterConsecutiveFailures() {
        when(chatModelPort.stream(anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("simulated 500")));

        // 非超时异常不触发 Retry，每次请求记 1 次失败；
        // minimum-number-of-calls=5 + sliding-window-size=5 + failure-rate=50%，
        // 连续 6 次失败必然超过阈值进入 OPEN。
        for (int i = 0; i < 6; i++) {
            streamingPort.stream(new OrchestrationRequest("stability_cb_" + i, "你好"))
                    .collectList()
                    .block();
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("llmProvider").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
