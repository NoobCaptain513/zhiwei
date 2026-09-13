package com.zihan.zhiwei.ai.memory.model;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
public record AgentCheckpoint(Long id, String runId, Long conversationId, String userId,
 Type checkpointType, String nodeName, JsonNode state, Status status, Integer sequenceNo,
 Long version, LocalDateTime resumeAfter, String errorCode, LocalDateTime expiresAt,
 LocalDateTime createdAt, LocalDateTime updatedAt) {
 public enum Type { AGENT, AGENTIC_RAG, TOOL_CHAIN }
 public enum Status { RUNNING, PAUSED, COMPLETED, FAILED, ABANDONED }
}