package com.zihan.zhiwei.ai.memory.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.MemoryAuditService;
import com.zihan.zhiwei.ai.memory.MemoryFactService;
import com.zihan.zhiwei.ai.memory.MemoryValueNormalizer;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemoryConflict;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemoryFactVersion;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.MemoryConflictMapper;
import com.zihan.zhiwei.mapper.MemoryFactMapper;
import com.zihan.zhiwei.mapper.MemoryFactVersionMapper;
import com.zihan.zhiwei.pojo.entity.MemoryConflictEntity;
import com.zihan.zhiwei.pojo.entity.MemoryFactEntity;
import com.zihan.zhiwei.pojo.entity.MemoryFactVersionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemoryFactServiceImpl implements MemoryFactService {
    private final MemoryFactMapper factMapper;
    private final MemoryFactVersionMapper versionMapper;
    private final MemoryConflictMapper conflictMapper;
    private final MemoryAuditService auditService;
    private final MemoryValueNormalizer normalizer;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public FactView create(String userId, FactWrite write) {
        validate(userId, write);
        String identityHash = identityHash(userId, write);
        MemoryFactEntity existing = factMapper.selectByIdentity(identityHash, userId);
        if (existing != null) {
            var current = requiredVersion(existing.getCurrentVersionId());
            if (current.getNormalizedValueHash().equals(normalizer.sha256(write.value()))) {
                return view(existing, current);
            }
            throw new IllegalStateException("memory fact already exists; update it with expectedVersion");
        }

        var now = LocalDateTime.now();
        var fact = new MemoryFactEntity();
        fact.setUserId(userId); fact.setNamespace(write.namespace()); fact.setSubject(write.subject());
        fact.setPredicate(write.predicate()); fact.setIdentityHash(identityHash); fact.setVersion(1L);
        fact.setState(initialState(write.sourceType()).name()); fact.setIsDeleted(0);
        fact.setCreatedAt(now); fact.setUpdatedAt(now);
        factMapper.insert(fact);

        var version = newVersion(fact.getId(), 1, write, now);
        versionMapper.insert(version);
        if (factMapper.casSetInitialVersion(fact.getId(), userId, 1L, version.getId(), fact.getState()) == 0) {
            throw new MemoryVersionConflictException(1L);
        }
        fact.setCurrentVersionId(version.getId());
        appendAudit(userId, MemoryAuditEvent.ResourceType.FACT, fact.getId(), MemoryAuditEvent.Action.CREATE,
                null, 1L, write, normalizer.canonicalJson(write.value()), Map.of());
        return view(fact, version);
    }

    @Override
    public Optional<FactView> get(String userId, long factId) {
        requireUser(userId);
        var fact = factMapper.selectOwned(factId, userId);
        return fact == null ? Optional.empty() : Optional.of(view(fact, requiredVersion(fact.getCurrentVersionId())));
    }

    @Override
    public List<FactView> list(String userId, FactFilter filter) {
        requireUser(userId);
        if (filter == null) filter = new FactFilter(null, null, null, null, 25);
        int limit = filter.limit() <= 0 ? 25 : Math.min(filter.limit(), 100);
        String state = filter.state() == null ? null : filter.state().name();
        return factMapper.listOwned(userId, blankToNull(filter.namespace()), blankToNull(filter.subject()),
                        blankToNull(filter.predicate()), state, limit).stream()
                .map(fact -> view(fact, requiredVersion(fact.getCurrentVersionId())))
                .toList();
    }

    @Override
    @Transactional
    public FactView update(String userId, long factId, long expectedVersion, FactWrite write) {
        validate(userId, write);
        if (write.sourceType() != MemoryFact.SourceType.USER && write.sourceType() != MemoryFact.SourceType.ADMIN) {
            throw new IllegalArgumentException("explicit updates require USER or ADMIN sourceType");
        }
        var fact = requiredFact(userId, factId);
        var current = requiredVersion(fact.getCurrentVersionId());
        if (current.getNormalizedValueHash().equals(normalizer.sha256(write.value()))) return view(fact, current);

        var version = insertNextVersion(factId, write);
        try {
            if (factMapper.casActivateVersion(factId, userId, expectedVersion, version.getId(), MemoryFact.State.ACTIVE.name()) == 0) {
                throw new MemoryVersionConflictException(expectedVersion);
            }
        } catch (DuplicateKeyException e) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        fact.setCurrentVersionId(version.getId()); fact.setState(MemoryFact.State.ACTIVE.name());
        fact.setVersion(expectedVersion + 1); fact.setUpdatedAt(LocalDateTime.now());
        appendAudit(userId, MemoryAuditEvent.ResourceType.FACT, factId, MemoryAuditEvent.Action.UPDATE,
                expectedVersion, expectedVersion + 1, write, normalizer.canonicalJson(write.value()), Map.of());
        return view(fact, version);
    }

    @Override
    @Transactional
    public Optional<MemoryConflict> proposeCandidate(String userId, long factId, long expectedVersion, FactWrite write) {
        validate(userId, write);
        if (write.sourceType() != MemoryFact.SourceType.AGENT_EXTRACTED) {
            throw new IllegalArgumentException("model candidates require AGENT_EXTRACTED sourceType");
        }
        var fact = requiredFact(userId, factId);
        var current = requiredVersion(fact.getCurrentVersionId());
        if (current.getNormalizedValueHash().equals(normalizer.sha256(write.value()))) return Optional.empty();

        var candidate = insertNextVersion(factId, write);
        if (factMapper.casMarkConflicted(factId, userId, expectedVersion) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        var row = new MemoryConflictEntity();
        row.setFactId(factId); row.setBaseVersionId(current.getId()); row.setCandidateVersionId(candidate.getId());
        row.setType(MemoryConflict.Type.VALUE_MISMATCH.name()); row.setStatus(MemoryConflict.Status.OPEN.name());
        row.setCreatedAt(LocalDateTime.now());
        conflictMapper.insert(row);
        appendAudit(userId, MemoryAuditEvent.ResourceType.CONFLICT, row.getId(), MemoryAuditEvent.Action.CREATE,
                expectedVersion, expectedVersion + 1, write, null,
                Map.of("factId", factId, "baseVersionId", current.getId(), "candidateVersionId", candidate.getId()));
        return Optional.of(toConflict(row));
    }

    @Override
    @Transactional
    public void delete(String userId, long factId, long expectedVersion, String actorId, String reason, String requestId) {
        requireActor(actorId, reason);
        if (factMapper.casSoftDelete(factId, userId, expectedVersion, LocalDateTime.now()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        appendSimpleAudit(userId, factId, MemoryAuditEvent.Action.DELETE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId);
    }

    @Override
    @Transactional
    public MemoryFact restore(String userId, long factId, long expectedVersion, String actorId, String reason, String requestId) {
        requireActor(actorId, reason);
        var fact = factMapper.selectOwnedIncludingDeleted(factId, userId);
        if (fact == null || fact.getIsDeleted() == null || fact.getIsDeleted() == 0) {
            throw new IllegalArgumentException("deleted memory fact not found");
        }
        if (factMapper.casRestore(factId, userId, expectedVersion, LocalDateTime.now()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        fact.setIsDeleted(0); fact.setDeletedAt(null); fact.setState(MemoryFact.State.ACTIVE.name());
        fact.setVersion(expectedVersion + 1); fact.setUpdatedAt(LocalDateTime.now());
        appendSimpleAudit(userId, factId, MemoryAuditEvent.Action.RESTORE, expectedVersion, expectedVersion + 1,
                actorId, reason, requestId);
        return toFact(fact);
    }

    @Override
    public List<MemoryFactVersion> versions(String userId, long factId) {
        requireUser(userId);
        return versionMapper.listOwned(factId, userId).stream().map(this::toVersion).toList();
    }

    @Override
    public List<MemoryConflict> conflicts(String userId, long factId) {
        requireUser(userId);
        return conflictMapper.listOwned(factId, userId).stream().map(this::toConflict).toList();
    }

    @Override
    @Transactional
    public MemoryConflict resolveConflict(String userId, long conflictId, long expectedVersion,
                                          MemoryConflict.Resolution resolution, com.fasterxml.jackson.databind.JsonNode mergedValue,
                                          String actorId, String reason, String requestId) {
        requireUser(userId);
        requireActor(actorId, reason);
        if (resolution == null) throw new IllegalArgumentException("resolution is required");
        var conflict = conflictMapper.selectOwned(conflictId, userId);
        if (conflict == null || !MemoryConflict.Status.OPEN.name().equals(conflict.getStatus())) {
            throw new IllegalArgumentException("open memory conflict not found");
        }
        var fact = requiredFact(userId, conflict.getFactId());
        Long resolvedVersionId = fact.getCurrentVersionId();
        MemoryFact.State targetState = MemoryFact.State.ACTIVE;
        if (resolution == MemoryConflict.Resolution.ACCEPT_CANDIDATE) {
            resolvedVersionId = conflict.getCandidateVersionId();
        } else if (resolution == MemoryConflict.Resolution.MERGE) {
            if (mergedValue == null) throw new IllegalArgumentException("mergedValue is required for MERGE");
            var merged = insertNextVersion(fact.getId(), new FactWrite(fact.getNamespace(), fact.getSubject(),
                    fact.getPredicate(), mergedValue, MemoryFact.SourceType.USER, "conflict:" + conflictId,
                    null, null, null, actorId, reason, requestId));
            resolvedVersionId = merged.getId();
        } else if (resolution == MemoryConflict.Resolution.REVOKE) {
            targetState = MemoryFact.State.REVOKED;
        }
        if (factMapper.casActivateVersion(fact.getId(), userId, expectedVersion, resolvedVersionId, targetState.name()) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        var now = LocalDateTime.now();
        if (conflictMapper.resolveOpen(conflictId, userId, MemoryConflict.Status.RESOLVED.name(), resolution.name(),
                resolvedVersionId, actorId, reason, now) == 0) {
            throw new MemoryVersionConflictException(expectedVersion);
        }
        conflict.setStatus(MemoryConflict.Status.RESOLVED.name());
        conflict.setResolution(resolution.name());
        conflict.setResolvedVersionId(resolvedVersionId);
        conflict.setResolvedBy(actorId);
        conflict.setReason(reason);
        conflict.setResolvedAt(now);
        auditService.append(new MemoryAuditService.AuditCommand(userId, MemoryAuditEvent.ResourceType.CONFLICT,
                String.valueOf(conflictId), MemoryAuditEvent.Action.RESOLVE_CONFLICT, expectedVersion,
                expectedVersion + 1, MemoryAuditEvent.ActorType.USER, actorId, requestId, reason, null,
                Map.of("factId", fact.getId(), "resolution", resolution.name())));
        return toConflict(conflict);
    }

    private MemoryFactVersionEntity insertNextVersion(long factId, FactWrite write) {
        var version = newVersion(factId, versionMapper.selectMaxVersionNo(factId) + 1, write, LocalDateTime.now());
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            throw new MemoryVersionConflictException(-1);
        }
        return version;
    }

    private MemoryFactVersionEntity newVersion(long factId, int number, FactWrite write, LocalDateTime now) {
        var row = new MemoryFactVersionEntity();
        row.setFactId(factId); row.setVersionNo(number); row.setValueJson(normalizer.canonicalJson(write.value()));
        row.setNormalizedValueHash(normalizer.sha256(write.value())); row.setSourceType(write.sourceType().name());
        row.setSourceRef(write.sourceRef()); row.setConfidence(write.confidence()); row.setValidFrom(write.validFrom());
        row.setValidTo(write.validTo()); row.setRecordedAt(now); row.setCreatedAt(now);
        row.setCreatedBy(write.actorId()); row.setChangeReason(write.reason());
        return row;
    }

    private FactView view(MemoryFactEntity fact, MemoryFactVersionEntity version) {
        return new FactView(toFact(fact), toVersion(version));
    }

    private MemoryFactEntity requiredFact(String userId, long factId) {
        requireUser(userId);
        var fact = factMapper.selectOwned(factId, userId);
        if (fact == null) throw new IllegalArgumentException("memory fact not found");
        return fact;
    }

    private MemoryFactVersionEntity requiredVersion(Long id) {
        if (id == null) throw new IllegalStateException("memory fact has no current version");
        var version = versionMapper.selectById(id);
        if (version == null) throw new IllegalStateException("memory fact current version not found");
        return version;
    }

    private MemoryFact toFact(MemoryFactEntity row) {
        return new MemoryFact(row.getId(), row.getUserId(), row.getNamespace(), row.getSubject(), row.getPredicate(),
                row.getCurrentVersionId(), MemoryFact.State.valueOf(row.getState()), row.getVersion(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private MemoryFactVersion toVersion(MemoryFactVersionEntity row) {
        try {
            return new MemoryFactVersion(row.getId(), row.getFactId(), row.getVersionNo(), objectMapper.readTree(row.getValueJson()),
                    row.getNormalizedValueHash(), MemoryFact.SourceType.valueOf(row.getSourceType()), row.getSourceRef(),
                    row.getConfidence(), row.getValidFrom(), row.getValidTo(), row.getRecordedAt(), row.getCreatedBy(), row.getChangeReason());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored memory fact version is invalid JSON", e);
        }
    }

    private MemoryConflict toConflict(MemoryConflictEntity row) {
        return new MemoryConflict(row.getId(), row.getFactId(), row.getBaseVersionId(), row.getCandidateVersionId(),
                MemoryConflict.Type.valueOf(row.getType()), MemoryConflict.Status.valueOf(row.getStatus()),
                row.getResolution() == null ? null : MemoryConflict.Resolution.valueOf(row.getResolution()),
                row.getResolvedVersionId(), row.getResolvedBy(), row.getReason(), row.getCreatedAt(), row.getResolvedAt());
    }

    private String identityHash(String userId, FactWrite write) {
        var identity = objectMapper.createObjectNode();
        identity.put("namespace", write.namespace()); identity.put("predicate", write.predicate());
        identity.put("subject", write.subject()); identity.put("userId", userId);
        return normalizer.sha256(identity);
    }

    private void validate(String userId, FactWrite write) {
        requireUser(userId);
        if (write == null || isBlank(write.namespace()) || isBlank(write.subject()) || isBlank(write.predicate())
                || write.value() == null || write.sourceType() == null || isBlank(write.actorId())) {
            throw new IllegalArgumentException("namespace, subject, predicate, value, sourceType and actorId are required");
        }
        if (write.validFrom() != null && write.validTo() != null && !write.validTo().isAfter(write.validFrom())) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
    }

    private void requireUser(String userId) { if (isBlank(userId)) throw new IllegalArgumentException("userId is required"); }
    private void requireActor(String actorId, String reason) {
        if (isBlank(actorId) || isBlank(reason)) throw new IllegalArgumentException("actorId and reason are required");
    }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String blankToNull(String value) { return isBlank(value) ? null : value; }
    private MemoryFact.State initialState(MemoryFact.SourceType source) {
        return source == MemoryFact.SourceType.AGENT_EXTRACTED ? MemoryFact.State.PROPOSED : MemoryFact.State.ACTIVE;
    }

    private void appendAudit(String userId, MemoryAuditEvent.ResourceType type, long resourceId,
                             MemoryAuditEvent.Action action, Long oldVersion, Long newVersion, FactWrite write,
                             String payload, Map<String, ?> metadata) {
        auditService.append(new MemoryAuditService.AuditCommand(userId, type, String.valueOf(resourceId), action,
                oldVersion, newVersion, actorType(write.sourceType()), write.actorId(), write.requestId(),
                write.reason(), payload, metadata));
    }

    private void appendSimpleAudit(String userId, long factId, MemoryAuditEvent.Action action, long oldVersion,
                                   long newVersion, String actorId, String reason, String requestId) {
        auditService.append(new MemoryAuditService.AuditCommand(userId, MemoryAuditEvent.ResourceType.FACT,
                String.valueOf(factId), action, oldVersion, newVersion, MemoryAuditEvent.ActorType.USER,
                actorId, requestId, reason, null, Map.of()));
    }

    private MemoryAuditEvent.ActorType actorType(MemoryFact.SourceType source) {
        return switch (source) {
            case ADMIN -> MemoryAuditEvent.ActorType.ADMIN;
            case AGENT_EXTRACTED -> MemoryAuditEvent.ActorType.AGENT;
            default -> MemoryAuditEvent.ActorType.USER;
        };
    }
}
