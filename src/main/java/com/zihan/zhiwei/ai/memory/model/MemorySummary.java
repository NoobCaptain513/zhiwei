package com.zihan.zhiwei.ai.memory.model;
import java.time.LocalDateTime;
import java.util.List;
public record MemorySummary(Long id, Long conversationId, String userId, String summary,
 List<String> openLoops, List<String> decisions, List<String> entities,
 Long coveredThroughMessageId, Integer sourceMessageCount, Long version, Status status,
 LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
 public enum Status { ACTIVE, SUPERSEDED, DELETED }
}