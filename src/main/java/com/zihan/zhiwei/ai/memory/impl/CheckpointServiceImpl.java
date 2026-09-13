package com.zihan.zhiwei.ai.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.*;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.AgentCheckpointMapper;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import com.zihan.zhiwei.pojo.entity.AgentCheckpointEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CheckpointServiceImpl implements CheckpointService {
    private final AgentCheckpointMapper mapper;
    private final MemoryAuditService audit;
    private final CheckpointStateValidator validator;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    @Override
    @Transactional
    public AgentCheckpoint create(CreateCommand command) {
        validateCreate(command);
        var row = new AgentCheckpointEntity();
        row.setUserId(command.userId()); row.setRunId(command.runId()); row.setConversationId(command.conversationId());
        row.setCheckpointType(command.checkpointType().name()); row.setNodeName(command.nodeName());
        row.setStateJson(validator.validate(command.state())); row.setStatus(command.status().name()); row.setSequenceNo(command.sequenceNo());
        row.setVersion(1L); row.setResumeAfter(command.resumeAfter()); row.setErrorCode(command.errorCode());
        var now = LocalDateTime.now(); row.setCreatedAt(now); row.setUpdatedAt(now);
        row.setExpiresAt(now.plus(properties.getCheckpointTtl())); row.setIsDeleted(0);
        mapper.insert(row);
        appendAudit(command.userId(), row.getId(), MemoryAuditEvent.Action.CREATE, null, 1L,
                command.actorId(), command.reason(), command.requestId(), row.getStateJson());
        return toModel(row);
    }

    @Override
    public Optional<AgentCheckpoint> get(String userId, long id) {
        requireOwner(userId);
        var row = mapper.selectOwned(userId, id);
        if (row == null || row.getExpiresAt() != null && !row.getExpiresAt().isAfter(LocalDateTime.now())) return Optional.empty();
        return Optional.of(toModel(row));
    }

    @Override
    public List<AgentCheckpoint> list(String userId, Long conversationId, AgentCheckpoint.Status status, int limit) {
        requireOwner(userId);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        var now = LocalDateTime.now();
        return mapper.listOwned(userId, conversationId, status == null ? null : status.name(), limit).stream()
                .filter(row -> row.getExpiresAt() == null || row.getExpiresAt().isAfter(now))
                .map(this::toModel)
                .toList();
    }

    @Override
    @Transactional
    public AgentCheckpoint transition(String userId, long id, long expectedVersion, AgentCheckpoint.Status target,
                                      String nodeName, CheckpointState state, String errorCode, LocalDateTime resumeAfter,
                                      String actorId, String reason, String requestId) {
        requireOwner(userId);
        var current = mapper.selectOwned(userId, id);
        if (current == null) throw new IllegalArgumentException("checkpoint not found");
        var source = AgentCheckpoint.Status.valueOf(current.getStatus());
        if (!canTransition(source, target)) throw new IllegalStateException("invalid checkpoint transition: " + source + " -> " + target);
        if (current.getExpiresAt() != null && !current.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("checkpoint is expired");
        }
        var updated = copy(current);
        updated.setNodeName(nodeName == null ? current.getNodeName() : nodeName);
        updated.setStateJson(state == null ? current.getStateJson() : validator.validate(state));
        updated.setStatus(target.name()); updated.setErrorCode(errorCode); updated.setResumeAfter(resumeAfter);
        updated.setExpiresAt(LocalDateTime.now().plus(properties.getCheckpointTtl()));
        if (mapper.casTransition(updated, id, userId, expectedVersion, source.name()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        updated.setVersion(expectedVersion + 1); updated.setUpdatedAt(LocalDateTime.now());
        appendAudit(userId, id, MemoryAuditEvent.Action.UPDATE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId, updated.getStateJson());
        return toModel(updated);
    }

    @Override
    public AgentCheckpoint resume(String userId, long id, long expectedVersion, String actorId, String reason, String requestId) {
        return transition(userId, id, expectedVersion, AgentCheckpoint.Status.RUNNING, null, null,
                null, null, actorId, reason, requestId);
    }

    @Override
    @Transactional
    public void delete(String userId, long id, long expectedVersion, String actorId, String reason, String requestId) {
        requireOwner(userId);
        if (mapper.casSoftDelete(id, userId, expectedVersion, LocalDateTime.now()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        appendAudit(userId, id, MemoryAuditEvent.Action.DELETE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId, null);
    }

    @Override
    @Transactional
    public AgentCheckpoint restore(String userId, long id, long expectedVersion,
                                   String actorId, String reason, String requestId) {
        requireOwner(userId);
        var row = mapper.selectOwnedIncludingDeleted(userId, id);
        if (row == null || row.getIsDeleted() == null || row.getIsDeleted() == 0) {
            throw new IllegalArgumentException("deleted checkpoint not found");
        }
        if (row.getDeletedAt() == null
                || row.getDeletedAt().isBefore(LocalDateTime.now().minus(properties.getSoftDeleteRetention()))) {
            throw new IllegalStateException("checkpoint restore retention window has expired");
        }
        if (mapper.casRestore(id, userId, expectedVersion, LocalDateTime.now()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        row.setIsDeleted(0);
        row.setDeletedAt(null);
        row.setVersion(expectedVersion + 1);
        row.setUpdatedAt(LocalDateTime.now());
        appendAudit(userId, id, MemoryAuditEvent.Action.RESTORE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId, null);
        return toModel(row);
    }

    public static boolean canTransition(AgentCheckpoint.Status source, AgentCheckpoint.Status target) {
        if (source == null || target == null || source == target) return false;
        return switch (source) {
            case RUNNING -> Set.of(AgentCheckpoint.Status.PAUSED, AgentCheckpoint.Status.COMPLETED,
                    AgentCheckpoint.Status.FAILED, AgentCheckpoint.Status.ABANDONED).contains(target);
            case PAUSED -> Set.of(AgentCheckpoint.Status.RUNNING, AgentCheckpoint.Status.ABANDONED).contains(target);
            case FAILED -> Set.of(AgentCheckpoint.Status.RUNNING, AgentCheckpoint.Status.ABANDONED).contains(target);
            case COMPLETED, ABANDONED -> false;
        };
    }

    private AgentCheckpoint toModel(AgentCheckpointEntity row) {
        try {
            return new AgentCheckpoint(row.getId(), row.getRunId(), row.getConversationId(), row.getUserId(),
                    AgentCheckpoint.Type.valueOf(row.getCheckpointType()), row.getNodeName(), objectMapper.readTree(row.getStateJson()),
                    AgentCheckpoint.Status.valueOf(row.getStatus()), row.getSequenceNo(), row.getVersion(), row.getResumeAfter(),
                    row.getErrorCode(), row.getExpiresAt(), row.getCreatedAt(), row.getUpdatedAt());
        } catch (Exception e) { throw new IllegalStateException("invalid stored checkpoint state", e); }
    }

    private static AgentCheckpointEntity copy(AgentCheckpointEntity source) {
        var row = new AgentCheckpointEntity();
        row.setId(source.getId()); row.setRunId(source.getRunId()); row.setConversationId(source.getConversationId());
        row.setUserId(source.getUserId()); row.setCheckpointType(source.getCheckpointType()); row.setNodeName(source.getNodeName());
        row.setStateJson(source.getStateJson()); row.setStatus(source.getStatus()); row.setSequenceNo(source.getSequenceNo());
        row.setVersion(source.getVersion()); row.setResumeAfter(source.getResumeAfter()); row.setErrorCode(source.getErrorCode());
        row.setCreatedAt(source.getCreatedAt()); row.setUpdatedAt(source.getUpdatedAt()); row.setExpiresAt(source.getExpiresAt());
        return row;
    }

    private void appendAudit(String userId, Long id, MemoryAuditEvent.Action action, Long oldVersion, Long newVersion,
                             String actorId, String reason, String requestId, String payload) {
        audit.append(new MemoryAuditService.AuditCommand(userId, MemoryAuditEvent.ResourceType.CHECKPOINT, String.valueOf(id),
                action, oldVersion, newVersion, MemoryAuditEvent.ActorType.SYSTEM, actorId, requestId, reason, payload, Map.of()));
    }

    private static void validateCreate(CreateCommand command) {
        if (command == null) throw new IllegalArgumentException("checkpoint command is required");
        requireOwner(command.userId());
        if (command.runId() == null || command.runId().isBlank()) throw new IllegalArgumentException("runId is required");
        if (command.conversationId() <= 0) throw new IllegalArgumentException("conversationId must be positive");
        if (command.checkpointType() == null || command.status() == null) throw new IllegalArgumentException("checkpoint type and status are required");
        if (command.status() == AgentCheckpoint.Status.COMPLETED || command.status() == AgentCheckpoint.Status.ABANDONED)
            throw new IllegalArgumentException("cannot create a terminal checkpoint");
        if (command.sequenceNo() < 0) throw new IllegalArgumentException("sequenceNo cannot be negative");
    }

    private static void requireOwner(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
    }
}
