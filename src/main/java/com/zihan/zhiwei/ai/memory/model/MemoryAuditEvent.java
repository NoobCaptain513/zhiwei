package com.zihan.zhiwei.ai.memory.model;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
public record MemoryAuditEvent(String eventId, String userId, ResourceType resourceType, String resourceId,
 Action action, Long oldVersion, Long newVersion, ActorType actorType, String actorId,
 String requestId, String reason, String payloadHash, JsonNode metadata, LocalDateTime createdAt) {
 public enum ResourceType { SUMMARY, CHECKPOINT, FACT, CONFLICT, FORGET_JOB }
 public enum Action { CREATE, READ, UPDATE, DELETE, RESTORE, RESOLVE_CONFLICT, FORGET, EXPORT }
 public enum ActorType { USER, AGENT, SYSTEM, ADMIN }
}