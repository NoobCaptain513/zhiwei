package com.zihan.zhiwei.ai.memory.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.MemoryAuditService;
import com.zihan.zhiwei.ai.memory.MemorySummaryService;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.ConversationMemorySummaryMapper;
import com.zihan.zhiwei.pojo.entity.ConversationMemorySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemorySummaryServiceImpl implements MemorySummaryService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final ConversationMemorySummaryMapper mapper;
    private final MemoryAuditService audit;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<MemorySummary> get(String userId, long conversationId) {
        requireOwner(userId);
        var row = mapper.selectOwned(userId, conversationId);
        if (row == null || !MemorySummary.Status.ACTIVE.name().equals(row.getStatus())
                || row.getExpiresAt() != null && !row.getExpiresAt().isAfter(LocalDateTime.now())) {
            return Optional.empty();
        }
        return Optional.of(toModel(row));
    }

    @Override
    @Transactional
    public MemorySummary save(String userId, long conversationId, SummaryWrite command) {
        requireOwner(userId);
        validate(command);
        var current = mapper.selectOwned(userId, conversationId);
        if (current == null) return create(userId, conversationId, command);
        if (command.coveredThroughMessageId() < current.getCoveredThroughMessageId()) {
            throw new IllegalArgumentException("coveredThroughMessageId cannot move backwards");
        }
        if (command.coveredThroughMessageId() == current.getCoveredThroughMessageId()) return toModel(current);
        if (command.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion is required for update");

        var updated = fromCommand(userId, conversationId, command);
        updated.setId(current.getId()); updated.setStatus(MemorySummary.Status.ACTIVE.name());
        if (mapper.casUpdate(updated, current.getId(), command.expectedVersion(), userId) == 0) {
            throw new MemoryVersionConflictException(command.expectedVersion());
        }
        updated.setVersion(command.expectedVersion() + 1);
        updated.setCreatedAt(current.getCreatedAt()); updated.setUpdatedAt(LocalDateTime.now());
        appendAudit(userId, current.getId(), MemoryAuditEvent.Action.UPDATE, command.expectedVersion(),
                updated.getVersion(), command.actorId(), command.reason(), command.requestId(), command.summary());
        return toModel(updated);
    }

    private MemorySummary create(String userId, long conversationId, SummaryWrite command) {
        var row = fromCommand(userId, conversationId, command);
        row.setVersion(1L); row.setStatus(MemorySummary.Status.ACTIVE.name()); row.setIsDeleted(0);
        var now = LocalDateTime.now(); row.setCreatedAt(now); row.setUpdatedAt(now);
        mapper.insert(row);
        appendAudit(userId, row.getId(), MemoryAuditEvent.Action.CREATE, null, 1L,
                command.actorId(), command.reason(), command.requestId(), command.summary());
        return toModel(row);
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
    public MemorySummary restore(String userId, long id, long expectedVersion, String actorId, String reason, String requestId) {
        requireOwner(userId);
        var row = mapper.selectOwnedIncludingDeleted(userId, id);
        if (row == null || row.getIsDeleted() == null || row.getIsDeleted() == 0) {
            throw new IllegalArgumentException("deleted summary not found");
        }
        if (mapper.casRestore(id, userId, expectedVersion, LocalDateTime.now()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        row.setIsDeleted(0); row.setDeletedAt(null); row.setStatus(MemorySummary.Status.ACTIVE.name());
        row.setVersion(expectedVersion + 1); row.setUpdatedAt(LocalDateTime.now());
        appendAudit(userId, id, MemoryAuditEvent.Action.RESTORE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId, null);
        return toModel(row);
    }

    private ConversationMemorySummary fromCommand(String userId, long conversationId, SummaryWrite command) {
        var row = new ConversationMemorySummary();
        row.setUserId(userId); row.setConversationId(conversationId); row.setSummary(command.summary());
        row.setOpenLoops(json(command.openLoops())); row.setDecisions(json(command.decisions())); row.setEntities(json(command.entities()));
        row.setCoveredThroughMessageId(command.coveredThroughMessageId()); row.setSourceMessageCount(command.sourceMessageCount());
        row.setExpiresAt(command.expiresAt());
        return row;
    }

    private MemorySummary toModel(ConversationMemorySummary row) {
        return new MemorySummary(row.getId(), row.getConversationId(), row.getUserId(), row.getSummary(),
                list(row.getOpenLoops()), list(row.getDecisions()), list(row.getEntities()), row.getCoveredThroughMessageId(),
                row.getSourceMessageCount(), row.getVersion(), MemorySummary.Status.valueOf(row.getStatus()),
                row.getExpiresAt(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values == null ? List.of() : values); }
        catch (Exception e) { throw new IllegalArgumentException("summary metadata is not serializable", e); }
    }

    private List<String> list(String value) {
        if (value == null) return List.of();
        try { return objectMapper.readValue(value, STRING_LIST); }
        catch (Exception e) { throw new IllegalStateException("invalid stored summary metadata", e); }
    }

    private void appendAudit(String userId, Long id, MemoryAuditEvent.Action action, Long oldVersion, Long newVersion,
                             String actorId, String reason, String requestId, String payload) {
        audit.append(new MemoryAuditService.AuditCommand(userId, MemoryAuditEvent.ResourceType.SUMMARY,
                String.valueOf(id), action, oldVersion, newVersion, MemoryAuditEvent.ActorType.SYSTEM,
                actorId, requestId, reason, payload, Map.of()));
    }

    private static void validate(SummaryWrite command) {
        if (command == null || command.summary() == null || command.summary().isBlank()) throw new IllegalArgumentException("summary is required");
        if (command.coveredThroughMessageId() <= 0) throw new IllegalArgumentException("coveredThroughMessageId must be positive");
        if (command.sourceMessageCount() < 0) throw new IllegalArgumentException("sourceMessageCount cannot be negative");
        if (command.actorId() == null || command.actorId().isBlank()) throw new IllegalArgumentException("actorId is required");
    }

    private static void requireOwner(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
    }
}
