package com.zihan.zhiwei.ai.memory.model;
import java.time.LocalDateTime;
public record MemoryConflict(Long id, Long factId, Long baseVersionId, Long candidateVersionId,
 Type type, Status status, Resolution resolution, Long resolvedVersionId, String resolvedBy,
 String reason, LocalDateTime createdAt, LocalDateTime resolvedAt) {
 public enum Type { VALUE_MISMATCH, CONCURRENT_UPDATE, VALIDITY_OVERLAP }
 public enum Status { OPEN, RESOLVED, DISMISSED }
 public enum Resolution { KEEP_CURRENT, ACCEPT_CANDIDATE, MERGE, REVOKE }
}