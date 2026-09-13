package com.zihan.zhiwei.ai.memory.model;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
public record MemoryForgetJob(String jobId, String userId, ScopeType scopeType, String scopeId,
 Status status, String requestedBy, String reason, LocalDateTime requestedAt,
 LocalDateTime completedAt, JsonNode result) {
 public enum ScopeType { FACT, SUMMARY, CHECKPOINT, CONVERSATION, USER }
 public enum Status { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED }
}