package com.zihan.zhiwei.ai.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * D15: SSE 流式传输通用组件。
 * 封装 SseEmitter 生命周期、异步线程池、send 工具方法，
 * 让 Controller / Service 不必关心 SSE 细节。
 */
@Slf4j
@Component
public class AiStreamAdvice {

    private static final long DEFAULT_TIMEOUT_MS = 180_000L; // 3 分钟
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ==================== 核心执行 ====================

    /**
     * 统一 SSE 生命周期：创建 Emitter → 异步执行 action → 发送 [DONE] → complete。
     * action 内部抛出任何异常都会被兜底并 completeWithError。
     */
    public SseEmitter execute(SseEmitterAction action) {
        return execute(DEFAULT_TIMEOUT_MS, action);
    }

    public SseEmitter execute(long timeoutMs, SseEmitterAction action) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        executor.execute(() -> {
            try {
                action.run(emitter);
                sendDone(emitter);
            } catch (Exception e) {
                log.error("[SSE] error: {}", e.getMessage(), e);
                sendRaw(emitter, "{\"error\":" + jsonEscape(e.getMessage()) + "}");
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ==================== 事件发送 ====================

    public void sendToken(SseEmitter emitter, String token) {
        sendRaw(emitter, "{\"token\":" + jsonEscape(token) + "}");
    }

    public void sendCard(SseEmitter emitter, String cardJson) {
        sendRaw(emitter, "{\"card\":" + cardJson + "}");
    }

    public void sendDone(SseEmitter emitter, String model, String provider, int totalTokens) {
        sendRaw(emitter, String.format(
                "{\"done\":true,\"model\":\"%s\",\"provider\":\"%s\",\"totalTokens\":%d}",
                nvl(model), nvl(provider), totalTokens));
    }

    public void sendDone(SseEmitter emitter, StreamResult result) {
        sendDone(emitter, result.model(), result.provider(), result.totalTokens());
    }

    public void sendDone(SseEmitter emitter, AgentStreamResult result) {
        sendDone(emitter, result.getModel(), result.getProvider(), result.getTotalTokens());
    }

    // ==================== 工具方法 ====================

    private static void sendDone(SseEmitter emitter) {
        sendRaw(emitter, "[DONE]");
    }

    private static void sendRaw(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("[SSE] send failed: {}", e.getMessage());
        }
    }

    static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    @FunctionalInterface
    public interface SseEmitterAction {
        void run(SseEmitter emitter) throws Exception;
    }
}