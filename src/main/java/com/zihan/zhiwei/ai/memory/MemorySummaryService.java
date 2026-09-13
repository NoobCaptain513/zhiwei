package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemorySummaryService {
    Optional<MemorySummary> get(String userId, long conversationId);
    MemorySummary save(String userId, long conversationId, SummaryWrite command);
    void delete(String userId, long id, long expectedVersion, String actorId, String reason, String requestId);
    MemorySummary restore(String userId, long id, long expectedVersion, String actorId, String reason, String requestId);

    record SummaryWrite(String summary, List<String> openLoops, List<String> decisions, List<String> entities,
                        long coveredThroughMessageId, int sourceMessageCount, Long expectedVersion,
                        LocalDateTime expiresAt, String actorId, String reason, String requestId) {}
}
