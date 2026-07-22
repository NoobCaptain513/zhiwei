package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.stream.AiStreamAdvice;
import com.zihan.zhiwei.ai.usage.UsageRecorder;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.AgentRequest;
import com.zihan.zhiwei.pojo.dto.AgentResponse;
import com.zihan.zhiwei.pojo.dto.ChatRequest;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import com.zihan.zhiwei.pojo.dto.UsageRecentItem;
import com.zihan.zhiwei.service.AgentService;
import com.zihan.zhiwei.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
}