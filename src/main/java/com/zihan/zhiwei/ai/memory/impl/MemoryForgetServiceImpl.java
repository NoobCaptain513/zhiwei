package com.zihan.zhiwei.ai.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.MemoryAuditService;
import com.zihan.zhiwei.ai.memory.MemoryCache;
import com.zihan.zhiwei.ai.memory.MemoryForgetService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import com.zihan.zhiwei.mapper.MemoryForgetDataMapper;
import com.zihan.zhiwei.mapper.MemoryForgetJobMapper;
import com.zihan.zhiwei.pojo.dto.memory.MemoryForgetRequest;
import com.zihan.zhiwei.pojo.entity.MemoryForgetJobEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MemoryForgetServiceImpl implements MemoryForgetService {
    private static final String BACKUP_NOTICE = "Online memory stores cleared; backups expire under the configured rotation policy.";

    private final MemoryForgetJobMapper jobs;
    private final MemoryForgetDataMapper data;
    private final MemoryCache cache;
    private final MemoryAuditService audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final MemoryProperties properties;

    public MemoryForgetServiceImpl(MemoryForgetJobMapper jobs, MemoryForgetDataMapper data, MemoryCache cache,
                                   MemoryAuditService audit, TransactionTemplate transactions,
                                   ObjectMapper json, MemoryProperties properties) {
        this.jobs = jobs;
        this.data = data;
        this.cache = cache;
        this.audit = audit;
        this.transactions = transactions;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public MemoryForgetJob create(String userId, MemoryForgetRequest request, String idempotencyKey, String requestId) {
        validate(userId, request);
        String jobId = jobId(userId, idempotencyKey);
        MemoryForgetJobEntity existing = jobs.selectById(jobId);
        if (existing != null) {
            requireSameRequest(userId, request, existing);
            if (MemoryForgetJob.Status.COMPLETED.name().equals(existing.getStatus())) return toModel(existing);
        } else {
            existing = newJob(jobId, userId, request);
            try {
                MemoryForgetJobEntity inserted = existing;
                transactions.execute(status -> { jobs.insert(inserted); return null; });
            } catch (DuplicateKeyException race) {
                existing = jobs.selectById(jobId);
                if (existing == null) throw race;
                requireSameRequest(userId, request, existing);
                if (MemoryForgetJob.Status.COMPLETED.name().equals(existing.getStatus())) return toModel(existing);
            }
        }

        MemoryForgetJobEntity job = existing;
        updateJob(job, MemoryForgetJob.Status.RUNNING, null, false);
        int affectedRows;
        try {
            affectedRows = transactions.execute(status -> purgeMysql(request.scopeType(), userId, request.scopeId()));
        } catch (RuntimeException mysqlFailure) {
            JsonNode result = result("FAILED", 0, "SKIPPED", List.of("MYSQL"), mysqlFailure);
            updateJob(job, MemoryForgetJob.Status.FAILED, result, true);
            appendAudit(job, requestId, MemoryForgetJob.Status.FAILED, 0, List.of("MYSQL"));
            return toModel(job);
        }

        List<String> failures = new ArrayList<>();
        RuntimeException cacheFailure = null;
        try {
            cache.evict(request.scopeType(), userId, request.scopeId());
        } catch (RuntimeException failure) {
            failures.add("REDIS");
            cacheFailure = failure;
        }

        MemoryForgetJob.Status finalStatus = failures.isEmpty()
                ? MemoryForgetJob.Status.COMPLETED : MemoryForgetJob.Status.PARTIAL;
        JsonNode result = result("COMPLETED", affectedRows,
                failures.isEmpty() ? "COMPLETED" : "FAILED", failures, cacheFailure);
        updateJob(job, finalStatus, result, true);
        try {
            appendAudit(job, requestId, finalStatus, affectedRows, failures);
        } catch (RuntimeException auditFailure) {
            if (finalStatus == MemoryForgetJob.Status.COMPLETED) {
                failures.add("AUDIT");
                finalStatus = MemoryForgetJob.Status.PARTIAL;
                result = result("COMPLETED", affectedRows, "COMPLETED", failures, auditFailure);
                updateJob(job, finalStatus, result, true);
            }
        }
        return toModel(job);
    }

    @Override
    public Optional<MemoryForgetJob> get(String userId, String jobId) {
        if (blank(userId) || blank(jobId)) throw new IllegalArgumentException("userId and jobId are required");
        return Optional.ofNullable(jobs.selectOwned(userId, jobId)).map(this::toModel);
    }

    @Override
    public PurgeResult purgeExpired(int batchSize) {
        if (batchSize <= 0 || batchSize > 10_000) throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deletedBefore = now.minus(properties.getSoftDeleteRetention());
        int summaries = data.purgeExpiredSummaries(now, deletedBefore, batchSize);
        int remaining = Math.max(0, batchSize - summaries);
        int checkpoints = remaining == 0 ? 0 : data.purgeExpiredCheckpoints(now, deletedBefore, remaining);
        remaining = Math.max(0, remaining - checkpoints);
        int facts = remaining == 0 ? 0 : data.purgeDeletedFacts(deletedBefore, remaining);
        return new PurgeResult(summaries, checkpoints, facts);
    }

    @Scheduled(fixedDelayString = "${zhiwei.ai.memory.cleanup-delay-ms:3600000}")
    public void purgeExpiredScheduled() {
        purgeExpired(500);
    }

    private int purgeMysql(MemoryForgetJob.ScopeType scope, String userId, String scopeId) {
        return switch (scope) {
            case FACT -> data.purgeFact(userId, numericScope(scopeId));
            case SUMMARY -> data.purgeSummary(userId, numericScope(scopeId));
            case CHECKPOINT -> data.purgeCheckpoint(userId, numericScope(scopeId));
            case CONVERSATION -> data.purgeConversation(userId, numericScope(scopeId));
            case USER -> data.purgeUser(userId);
        };
    }

    private void appendAudit(MemoryForgetJobEntity job, String requestId, MemoryForgetJob.Status status,
                             int affectedRows, List<String> failedLayers) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("jobId", job.getJobId());
        metadata.put("scopeType", job.getScopeType());
        metadata.put("scopeId", job.getScopeId());
        metadata.put("status", status.name());
        metadata.put("affectedRows", affectedRows);
        metadata.put("failedLayers", List.copyOf(failedLayers));
        audit.append(new MemoryAuditService.AuditCommand(job.getUserId(), MemoryAuditEvent.ResourceType.FORGET_JOB,
                job.getJobId(), MemoryAuditEvent.Action.FORGET, null, null, MemoryAuditEvent.ActorType.USER,
                job.getRequestedBy(), requestId, job.getReason(), null, metadata));
    }

    private JsonNode result(String mysqlStatus, int affectedRows, String redisStatus,
                            List<String> failedLayers, RuntimeException failure) {
        Map<String, Object> mysql = Map.of("status", mysqlStatus, "affectedRows", affectedRows);
        Map<String, Object> redis = Map.of("status", redisStatus);
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("MYSQL", mysql); layers.put("REDIS", redis);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("layers", layers);
        value.put("failedLayers", List.copyOf(failedLayers));
        if (failure != null) value.put("failureType", failure.getClass().getSimpleName());
        value.put("backupNotice", BACKUP_NOTICE);
        return json.valueToTree(value);
    }

    private void updateJob(MemoryForgetJobEntity job, MemoryForgetJob.Status status, JsonNode result, boolean completed) {
        job.setStatus(status.name());
        job.setResultJson(result == null ? null : write(result));
        job.setCompletedAt(completed ? LocalDateTime.now() : null);
        transactions.execute(tx -> { jobs.updateById(job); return null; });
    }

    private MemoryForgetJobEntity newJob(String jobId, String userId, MemoryForgetRequest request) {
        var row = new MemoryForgetJobEntity();
        row.setJobId(jobId); row.setUserId(userId); row.setScopeType(request.scopeType().name());
        row.setScopeId(request.scopeId()); row.setStatus(MemoryForgetJob.Status.PENDING.name());
        row.setRequestedBy(request.requestedBy()); row.setReason(request.reason()); row.setRequestedAt(LocalDateTime.now());
        return row;
    }

    private MemoryForgetJob toModel(MemoryForgetJobEntity row) {
        return new MemoryForgetJob(row.getJobId(), row.getUserId(), MemoryForgetJob.ScopeType.valueOf(row.getScopeType()),
                row.getScopeId(), MemoryForgetJob.Status.valueOf(row.getStatus()), row.getRequestedBy(), row.getReason(),
                row.getRequestedAt(), row.getCompletedAt(), read(row.getResultJson()));
    }

    private void validate(String userId, MemoryForgetRequest request) {
        if (blank(userId) || request == null || !userId.equals(request.userId())) {
            throw new IllegalArgumentException("forget owner must match request owner");
        }
        if (request.scopeType() == null || blank(request.requestedBy()) || blank(request.reason())) {
            throw new IllegalArgumentException("scopeType, requestedBy and reason are required");
        }
        if (request.scopeType() == MemoryForgetJob.ScopeType.USER) {
            if (!blank(request.scopeId())) throw new IllegalArgumentException("USER scope must not have scopeId");
        } else {
            numericScope(request.scopeId());
        }
    }

    private void requireSameRequest(String userId, MemoryForgetRequest request, MemoryForgetJobEntity existing) {
        if (!userId.equals(existing.getUserId()) || !request.scopeType().name().equals(existing.getScopeType())
                || !java.util.Objects.equals(request.scopeId(), existing.getScopeId())) {
            throw new IllegalArgumentException("idempotency key was already used for another forget request");
        }
    }

    private String jobId(String userId, String idempotencyKey) {
        if (blank(idempotencyKey)) return UUID.randomUUID().toString();
        String normalized = idempotencyKey.trim();
        if (normalized.length() <= 36 && normalized.matches("[A-Za-z0-9._:-]+")) return normalized;
        return UUID.nameUUIDFromBytes((userId + "\u0000" + normalized).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private long numericScope(String scopeId) {
        if (blank(scopeId)) throw new IllegalArgumentException("scopeId is required");
        try {
            long value = Long.parseLong(scopeId);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("scopeId must be a positive numeric identifier");
        }
    }

    private JsonNode read(String value) {
        if (blank(value)) return null;
        try { return json.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("invalid forget result metadata", e); }
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize forget result metadata", e); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
