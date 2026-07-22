package com.zihan.zhiwei.ai.provider.health;

import com.zihan.zhiwei.ai.provider.failover.FailoverEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * D16: 降级事件日志（内存环形缓冲）。
 * FailoverHandler 每次降级时写入，供 /api/system/events 查看。
 */
@Slf4j
@Component
public class FailoverEventLog {

    @Value("${zhiwei.ai.health.event-log-size:200}")
    private int maxSize;

    private final ConcurrentLinkedDeque<FailoverEvent> events = new ConcurrentLinkedDeque<>();

    public void record(FailoverEvent event) {
        events.addLast(event);
        while (events.size() > maxSize) {
            events.pollFirst();
        }
        log.info("[FailoverEvent] {} → {} reason={} at={}",
                event.fromProvider(), event.toProvider(), event.reason(), event.occurredAt());
    }

    /** 获取最近 N 条降级事件 */
    public List<FailoverEvent> recent(int limit) {
        int n = Math.min(limit, events.size());
        return new ArrayList<>(events).subList(events.size() - n, events.size());
    }

    public List<FailoverEvent> recent() {
        return recent(20);
    }

    public void clear() {
        events.clear();
    }
}