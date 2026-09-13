package com.zihan.zhiwei.ai.memory.model;
import java.time.LocalDateTime;
public record MemoryFact(Long id, String userId, String namespace, String subject, String predicate,
 Long currentVersionId, State state, Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
 public enum State { PROPOSED, ACTIVE, CONFLICTED, REVOKED, DELETED }
 public enum SourceType { USER, AGENT_EXTRACTED, TOOL, IMPORT, ADMIN }
}