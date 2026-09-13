package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.impl.MemoryForgetServiceImpl;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import com.zihan.zhiwei.mapper.MemoryForgetDataMapper;
import com.zihan.zhiwei.mapper.MemoryForgetJobMapper;
import com.zihan.zhiwei.pojo.dto.memory.MemoryForgetRequest;
import com.zihan.zhiwei.pojo.entity.MemoryForgetJobEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryForgetServiceTest {
    private final MemoryForgetJobMapper jobs = mock(MemoryForgetJobMapper.class);
    private final MemoryForgetDataMapper data = mock(MemoryForgetDataMapper.class);
    private final MemoryCache cache = mock(MemoryCache.class);
    private final MemoryAuditService audit = mock(MemoryAuditService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MemoryForgetService service;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new MemoryForgetServiceImpl(jobs, data, cache, audit, transactions, json, new MemoryProperties());
    }

    @Test
    void factForgetIsOwnerScopedAndRecordsNoPlaintext() {
        when(data.purgeFact("alice", 42L)).thenReturn(3);
        var request = new MemoryForgetRequest("alice", MemoryForgetJob.ScopeType.FACT, "42",
                "admin", "privacy request");

        MemoryForgetJob result = service.create("alice", request, "idem-1", "request-1");

        assertThat(result.status()).isEqualTo(MemoryForgetJob.Status.COMPLETED);
        verify(data).purgeFact("alice", 42L);
        verify(cache).evict(MemoryForgetJob.ScopeType.FACT, "alice", "42");
        verify(audit).append(argThat(command -> command.action() == MemoryAuditEvent.Action.FORGET
                && command.payload() == null
                && !command.metadata().toString().contains("privacy request")));
        var finalJob = lastJobUpdate();
        assertThat(finalJob.getResultJson()).contains("MYSQL", "REDIS", "3")
                .doesNotContain("privacy request");
    }

    @Test
    void completedIdempotencyKeyReturnsExistingJobWithoutDeletingAgain() {
        var existing = job("alice", "job-existing", MemoryForgetJob.Status.COMPLETED, "FACT", "42",
                "{\"layers\":{\"MYSQL\":\"COMPLETED\",\"REDIS\":\"COMPLETED\"}}");
        when(jobs.selectById("job-existing")).thenReturn(existing);

        MemoryForgetJob result = service.create("alice",
                new MemoryForgetRequest("alice", MemoryForgetJob.ScopeType.FACT, "42", "admin", "privacy"),
                "job-existing", "request-1");

        assertThat(result.jobId()).isEqualTo("job-existing");
        verifyNoInteractions(data, cache, audit);
    }

    @Test
    void redisFailureProducesQueryablePartialJobAndRetryCanComplete() {
        when(data.purgeConversation("alice", 7L)).thenReturn(2);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cache).evict(MemoryForgetJob.ScopeType.CONVERSATION, "alice", "7");

        MemoryForgetJob result = service.create("alice",
                new MemoryForgetRequest("alice", MemoryForgetJob.ScopeType.CONVERSATION, "7", "admin", "privacy"),
                "idem-partial", "request-1");

        assertThat(result.status()).isEqualTo(MemoryForgetJob.Status.PARTIAL);
        assertThat(result.result().toString()).contains("REDIS").doesNotContain("redis unavailable");
        var persisted = lastJobUpdate();
        assertThat(persisted.getStatus()).isEqualTo("PARTIAL");
        assertThat(persisted.getResultJson()).doesNotContain("redis unavailable");
    }

    @Test
    void jobLookupCannotCrossOwners() {
        when(jobs.selectOwned("alice", "job-1")).thenReturn(job("alice", "job-1",
                MemoryForgetJob.Status.COMPLETED, "USER", null, "{}"));

        assertThat(service.get("alice", "job-1")).isPresent();
        assertThat(service.get("bob", "job-1")).isEmpty();

        verify(jobs).selectOwned("alice", "job-1");
        verify(jobs).selectOwned("bob", "job-1");
    }

    @Test
    void boundedRetentionPurgeUsesConfiguredCutoff() {
        when(data.purgeExpiredSummaries(any(), any(), eq(50))).thenReturn(4);
        when(data.purgeExpiredCheckpoints(any(), any(), eq(46))).thenReturn(3);
        when(data.purgeDeletedFacts(any(), eq(43))).thenReturn(2);

        var result = service.purgeExpired(50);

        assertThat(result.summaries()).isEqualTo(4);
        assertThat(result.checkpoints()).isEqualTo(3);
        assertThat(result.facts()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(9);
        verify(data).purgeExpiredCheckpoints(any(), any(), eq(46));
        verify(data).purgeDeletedFacts(any(), eq(43));
    }

    private MemoryForgetJobEntity lastJobUpdate() {
        var captor = org.mockito.ArgumentCaptor.forClass(MemoryForgetJobEntity.class);
        verify(jobs, atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private MemoryForgetJobEntity job(String userId, String id, MemoryForgetJob.Status status,
                                      String scope, String scopeId, String result) {
        var row = new MemoryForgetJobEntity();
        row.setJobId(id); row.setUserId(userId); row.setScopeType(scope); row.setScopeId(scopeId);
        row.setStatus(status.name()); row.setRequestedBy("admin"); row.setReason("privacy");
        row.setRequestedAt(java.time.LocalDateTime.now()); row.setResultJson(result);
        return row;
    }
}
