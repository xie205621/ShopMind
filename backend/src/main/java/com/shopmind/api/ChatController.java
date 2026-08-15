package com.shopmind.api;

import com.shopmind.orchestrator.domain.ChatStreamEvent;
import com.shopmind.orchestrator.domain.OrchestrationRequest;
import com.shopmind.orchestrator.port.ChatStreamingPort;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 对话 HTTP API — 对外暴露 {@code POST /api/chat}，以 SSE 流式返回。
 * <p>
 * 返回事件协议见 {@link ChatStreamEvent}，与前端 {@code SSEEvent} 一一对应：
 * intent → token → tool_call → tool_result → done（或 error）。
 */
@RestController
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatStreamingPort chatStreamingPort;

    public ChatController(ChatStreamingPort chatStreamingPort) {
        this.chatStreamingPort = chatStreamingPort;
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chat(@RequestBody ChatApiRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            ChatStreamEvent error = new ChatStreamEvent.Error("LLM_ERROR", "消息内容不能为空。");
            return Flux.just(ServerSentEvent.<ChatStreamEvent>builder(error).build());
        }

        String memoryId = (request.memoryId() == null || request.memoryId().isBlank())
                ? "session_" + UUID.randomUUID()
                : request.memoryId();

        OrchestrationRequest orchestrationRequest = new OrchestrationRequest(memoryId, request.query());
        return chatStreamingPort.stream(orchestrationRequest)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder(event).build());
    }
}
