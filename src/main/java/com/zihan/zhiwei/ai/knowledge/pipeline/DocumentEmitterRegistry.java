package com.zihan.zhiwei.ai.knowledge.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档上传 SSE 推送注册表。
 * 支持多实例部署：通过 Redis Pub/Sub 跨实例推送状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentEmitterRegistry {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    // 本地存储：documentId → emitter（只在持有 emitter 的实例有数据）
    private final Map<Long, SseEmitter> registry = new ConcurrentHashMap<>();

    /**
     * 注册 emitter 并订阅对应的 Redis channel。
     * 上传接口调用，在持有 emitter 的实例上执行。
     */
    public void register(Long documentId, SseEmitter emitter) {
        registry.put(documentId, emitter);
        log.info("[EmitterRegistry] register documentId={}", documentId);

        // 订阅这个文档的状态推送 channel
        String channel = channelName(documentId);
        MessageListener listener = (message, pattern) -> {
            String json = new String(message.getBody());
            log.debug("[EmitterRegistry] received channel={} json={}", channel, json);
            pushLocal(documentId, json);
        };
        redisMessageListenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        // emitter 生命周期结束时清理
        emitter.onCompletion(() -> cleanup(documentId, listener, channel));
        emitter.onTimeout(() -> cleanup(documentId, listener, channel));
        emitter.onError(e -> cleanup(documentId, listener, channel));
    }

    /**
     * 发布状态到 Redis channel（消费者调用，可能在不同实例）。
     * 持有 emitter 的实例会收到这条消息并转发给前端。
     */
    public void publish(Long documentId, String json) {
        String channel = channelName(documentId);
        try {
            redisTemplate.convertAndSend(channel, json);
            log.debug("[EmitterRegistry] publish documentId={} json={}", documentId, json);
        } catch (Exception e) {
            log.warn("[EmitterRegistry] publish failed documentId={}: {}", documentId, e.getMessage());
        }
    }

    /**
     * 直接推送到本地 emitter（当前实例持有时才有效）。
     */
    private void pushLocal(Long documentId, String json) {
        SseEmitter emitter = registry.get(documentId);
        if (emitter == null) {
            log.debug("[EmitterRegistry] emitter not found locally documentId={}", documentId);
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(json));
            log.debug("[EmitterRegistry] pushed to local emitter documentId={}", documentId);

            // 如果是终态（SUCCESS/FAILED），关闭连接
            if (json.contains("\"status\":\"SUCCESS\"") || json.contains("\"status\":\"FAILED\"")) {
                emitter.complete();
                registry.remove(documentId);
                log.info("[EmitterRegistry] completed documentId={}", documentId);
            }
        } catch (Exception e) {
            log.warn("[EmitterRegistry] push failed documentId={}: {}", documentId, e.getMessage());
            registry.remove(documentId);
        }
    }

    private void cleanup(Long documentId, MessageListener listener, String channel) {
        registry.remove(documentId);
        try {
            redisMessageListenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
            log.debug("[EmitterRegistry] cleanup documentId={} channel={}", documentId, channel);
        } catch (Exception e) {
            log.debug("[EmitterRegistry] cleanup failed: {}", e.getMessage());
        }
    }

    private String channelName(Long documentId) {
        return "doc:status:" + documentId;
    }
}
