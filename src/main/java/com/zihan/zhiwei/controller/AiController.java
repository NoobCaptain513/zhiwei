package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.stream.AiStreamAdvice;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.dto.ChatRequest;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import com.zihan.zhiwei.pojo.dto.IdempotencyPendingResponse;
import com.zihan.zhiwei.pojo.dto.UsageRecentItem;
import com.zihan.zhiwei.service.AgentService;
import com.zihan.zhiwei.service.IdempotentRequestCache;
import com.zihan.zhiwei.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI服务")
@RequiredArgsConstructor
public class AiController {

    private final ChatService chatService;
    private final AgentService agentService;
    private final IdempotentRequestCache idempotencyService;
    private final UsageRecorder usageRecorder;
    private final AiStreamAdvice sse;

    // ==================== 同步端点 ====================

    @PostMapping("/chat")
    @Operation(summary = "同步聊天")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chatService.chat(request));
    }

    @PostMapping("/agent")
    @Operation(summary = "Agent 全链路（意图 + 工具 + 卡片）（D14）")
    public Result<AgentResponse> agent(@Valid @RequestBody AgentRequest request) {
        return Result.ok(agentService.agent(request));
    }

    @GetMapping("/usage/recent")
    @Operation(summary = "最近用量明细（D9）")
    public Result<List<UsageRecentItem>> usageRecent(
            @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(usageRecorder.recent(limit));
    }

    // ==================== D15: SSE 流式端点 ====================

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天（SSE）（D15）")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        return sse.execute(emitter -> {
            var result = chatService.streamChat(request,
                    token -> sse.sendToken(emitter, token));
            sse.sendDone(emitter, result);
        });
    }

    @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Agent 流式全链路（SSE）（D15）")
    public SseEmitter agentStream(@Valid @RequestBody AgentRequest request) {
        return sse.execute(emitter -> {
            var result = agentService.streamAgent(request,
                    token -> sse.sendToken(emitter, token),
                    card  -> sse.sendCard(emitter, card));
            sse.sendDone(emitter, result);
        });
    }
    // ==================== D22: 幂等键轮询接口 ====================

    @GetMapping("/chat/status/{userKey}")
    @Operation(summary = "轮询聊天结果（幂等键）")
    public ResponseEntity<Result<?>> chatStatus(@PathVariable String userKey) {
        String[] parts = userKey.split(":", 2);
        if (parts.length != 2) {
            return ResponseEntity.badRequest()
                    .body(Result.error("Invalid userKey format, expect userId:idempotencyKey"));
        }
        var status = idempotencyService.peek("chat", parts[0], parts[1], ChatResponse.class, null);
        if (status.isCompleted()) {
            return ResponseEntity.ok(Result.ok(status.result()));
        } else if (status.isProcessing()) {
            String location = "/api/ai/chat/status/" + userKey;
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.LOCATION, location)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(Result.ok(IdempotencyPendingResponse.of(
                            parts[0], parts[1], "/api/ai/chat")));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error("未找到对应的请求"));
        }
    }

    @GetMapping("/agent/status/{userKey}")
    @Operation(summary = "轮询 Agent 结果（幂等键）")
    public ResponseEntity<Result<?>> agentStatus(@PathVariable String userKey) {
        String[] parts = userKey.split(":", 2);
        if (parts.length != 2) {
            return ResponseEntity.badRequest()
                    .body(Result.error("Invalid userKey format, expect userId:idempotencyKey"));
        }
        var status = idempotencyService.peek("agent", parts[0], parts[1], AgentResponse.class, null);
        if (status.isCompleted()) {
            return ResponseEntity.ok(Result.ok(status.result()));
        } else if (status.isProcessing()) {
            String location = "/api/ai/agent/status/" + userKey;
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.LOCATION, location)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(Result.ok(IdempotencyPendingResponse.of(
                            parts[0], parts[1], "/api/ai/agent")));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error("未找到对应的请求"));
        }
    }
}
