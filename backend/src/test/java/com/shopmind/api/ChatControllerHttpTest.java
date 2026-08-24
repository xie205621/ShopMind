package com.shopmind.api;

import com.shopmind.orchestrator.domain.ChatStreamEvent;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.port.ChatStreamingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P1-3 HTTP 层集成测试。
 * <p>
 * 用 {@code @WebFluxTest} 只加载 Controller 层，{@code @MockBean ChatStreamingPort}
 * 模拟 Orchestrator 返回事件，验证 {@code POST /api/chat} 的 SSE 协议、参数校验
 * 与异常兜底，不依赖 MongoDB / 真实 LLM。
 */
@WebFluxTest(ChatController.class)
class ChatControllerHttpTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ChatStreamingPort chatStreamingPort;

    private static ChatStreamEvent.Done doneEvent() {
        return new ChatStreamEvent.Done("mem_001",
                new ChatStreamEvent.Stats(1, 2, new ChatStreamEvent.TokenUsage(10, 1)));
    }

    // ============================================================
    //  Case A：正常请求
    // ============================================================

    @Test
    @DisplayName("Case A 正常请求：2xx + SSE Content-Type + Intent/Token/Done")
    void normalRequestReturnsSseEvents() {
        when(chatStreamingPort.stream(any(OrchestrationRequest.class)))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Intent("闲聊", false, false, 0.85),
                        new ChatStreamEvent.Token("你好"),
                        doneEvent()));

        Flux<ChatStreamEvent> body = webTestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("memoryId", "mem_001", "query", "你好"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(ChatStreamEvent.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Intent)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Token)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Done)
                .verifyComplete();
    }

    // ============================================================
    //  Case B：请求参数异常
    // ============================================================

    @Test
    @DisplayName("Case B 参数异常：query 为空 → SSE error 事件")
    void blankQueryReturnsSseError() {
        Flux<ChatStreamEvent> body = webTestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("memoryId", "mem_001", "query", ""))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Error err
                        && "LLM_ERROR".equals(err.code()))
                .verifyComplete();
    }

    // ============================================================
    //  Case C：Agent 内部异常
    // ============================================================

    @Test
    @DisplayName("Case C 内部异常：orchestrator 抛异常 → SSE error 事件并正常结束")
    void orchestratorFailureReturnsSseError() {
        when(chatStreamingPort.stream(any(OrchestrationRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        Flux<ChatStreamEvent> body = webTestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("memoryId", "mem_001", "query", "你好"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Error err
                        && "LLM_ERROR".equals(err.code()))
                .verifyComplete();
    }

    // ============================================================
    //  Case D：SSE 完整结束
    // ============================================================

    @Test
    @DisplayName("Case D SSE 完整结束：以 Done 结束，流正常 complete")
    void sseCompletesWithDoneEvent() {
        when(chatStreamingPort.stream(any(OrchestrationRequest.class)))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Intent("闲聊", false, false, 0.85),
                        doneEvent()));

        Flux<ChatStreamEvent> body = webTestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("memoryId", "mem_001", "query", "你好"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Intent)
                .expectNextMatches(e -> e instanceof ChatStreamEvent.Done)
                .verifyComplete();
    }
}
