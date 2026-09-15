package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

public interface CheckpointService {
    AgentCheckpoint create(CreateCommand command);
    Optional<AgentCheckpoint> get(String userId, long id);
    List<AgentCheckpoint> list(String userId, Long conversationId, AgentCheckpoint.Status status, int limit);
    boolean hasLaterInRun(String userId, String runId, int sequenceNo, long checkpointId);
    AgentCheckpoint transition(String userId, long id, long expectedVersion, AgentCheckpoint.Status target,
                               String nodeName, CheckpointState state, String errorCode, LocalDateTime resumeAfter,
                               String actorId, String reason, String requestId);
    AgentCheckpoint updateProgress(String userId, long id, long expectedVersion, String nodeName,
                                   CheckpointState state, int sequenceNo,
                                   String actorId, String reason, String requestId);
    AgentCheckpoint resume(String userId, long id, long expectedVersion, String actorId, String reason, String requestId);
    void delete(String userId, long id, long expectedVersion, String actorId, String reason, String requestId);
    AgentCheckpoint restore(String userId, long id, long expectedVersion, String actorId, String reason, String requestId);

    record CreateCommand(String userId, String runId, long conversationId, AgentCheckpoint.Type checkpointType,
                         String nodeName, CheckpointState state, AgentCheckpoint.Status status, int sequenceNo,
                         LocalDateTime resumeAfter, String errorCode, String actorId, String reason, String requestId) {}
}
