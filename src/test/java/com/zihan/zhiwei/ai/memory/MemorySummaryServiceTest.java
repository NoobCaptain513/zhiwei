package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.impl.MemorySummaryServiceImpl;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.ConversationMemorySummaryMapper;
import com.zihan.zhiwei.pojo.entity.ConversationMemorySummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySummaryServiceTest {
    private final ConversationMemorySummaryMapper mapper = mock(ConversationMemorySummaryMapper.class);
    private final MemoryAuditService audit = mock(MemoryAuditService.class);
    private final MemorySummaryService service = new MemorySummaryServiceImpl(mapper, audit, new ObjectMapper());

    @Test void ownerScopedReadHidesOtherOwnersAndExpiredRows() {
        when(mapper.selectOwned("alice", 10L)).thenReturn(null);
        assertThat(service.get("alice", 10L)).isEmpty();
        verify(mapper).selectOwned("alice", 10L);

        var expired = row(1, "alice", 10, 3, 12);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOwned("alice", 10L)).thenReturn(expired);
        assertThat(service.get("alice", 10L)).isEmpty();
    }

    @Test void createsOnceAndSameCoveragePointIsIdempotent() {
        when(mapper.selectOwned("alice", 10L)).thenReturn(null);
        doAnswer(invocation -> { ((ConversationMemorySummary) invocation.getArgument(0)).setId(7L); return 1; })
                .when(mapper).insert(any(ConversationMemorySummary.class));
        var command = command(12, null);
        var created = service.save("alice", 10L, command);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.coveredThroughMessageId()).isEqualTo(12);
        verify(audit).append(argThat(a -> a.action() == MemoryAuditEvent.Action.CREATE && a.newVersion() == 1));

        var existing = row(7, "alice", 10, 1, 12);
        when(mapper.selectOwned("alice", 10L)).thenReturn(existing);
        var retry = service.save("alice", 10L, command);
        assertThat(retry.version()).isEqualTo(1);
        verify(mapper, never()).casUpdate(any(), anyLong(), anyLong(), anyString());
    }

    @Test void rejectsCoverageRegressionAndTurnsZeroRowCasIntoDedicatedConflict() {
        when(mapper.selectOwned("alice", 10L)).thenReturn(row(7, "alice", 10, 2, 12));
        assertThatThrownBy(() -> service.save("alice", 10L, command(11, 2L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("covered");

        when(mapper.casUpdate(any(), eq(7L), eq(2L), eq("alice"))).thenReturn(0);
        assertThatThrownBy(() -> service.save("alice", 10L, command(13, 2L)))
                .isInstanceOf(MemoryVersionConflictException.class);
    }

    @Test void updateDeleteAndRestoreUseOwnerAndCasAndAppendAudit() {
        when(mapper.selectOwned("alice", 10L)).thenReturn(row(7, "alice", 10, 2, 12));
        when(mapper.casUpdate(any(), eq(7L), eq(2L), eq("alice"))).thenReturn(1);
        assertThat(service.save("alice", 10L, command(13, 2L)).version()).isEqualTo(3);

        when(mapper.casSoftDelete(eq(7L), eq("alice"), eq(3L), any())).thenReturn(1);
        service.delete("alice", 7L, 3L, "alice", "cleanup", "req-2");

        var deleted = row(7, "alice", 10, 4, 13);
        deleted.setStatus("DELETED"); deleted.setIsDeleted(1);
        when(mapper.selectOwnedIncludingDeleted("alice", 7L)).thenReturn(deleted);
        when(mapper.casRestore(eq(7L), eq("alice"), eq(4L), any())).thenReturn(1);
        assertThat(service.restore("alice", 7L, 4L, "alice", "undo", "req-3").version()).isEqualTo(5);

        var audits = ArgumentCaptor.forClass(MemoryAuditService.AuditCommand.class);
        verify(audit, times(3)).append(audits.capture());
        assertThat(audits.getAllValues()).extracting(MemoryAuditService.AuditCommand::action)
                .containsExactly(MemoryAuditEvent.Action.UPDATE, MemoryAuditEvent.Action.DELETE, MemoryAuditEvent.Action.RESTORE);
        verify(mapper).casSoftDelete(eq(7L), eq("alice"), eq(3L), any());
        verify(mapper).casRestore(eq(7L), eq("alice"), eq(4L), any());
    }

    private MemorySummaryService.SummaryWrite command(long covered, Long expected) {
        return new MemorySummaryService.SummaryWrite("summary", List.of("loop"), List.of("decision"),
                List.of("entity"), covered, 4, expected, null, "alice", "reason", "req-1");
    }

    private ConversationMemorySummary row(long id, String user, long conversation, long version, long covered) {
        var row = new ConversationMemorySummary();
        row.setId(id); row.setUserId(user); row.setConversationId(conversation); row.setSummary("summary");
        row.setOpenLoops("[\"loop\"]"); row.setDecisions("[\"decision\"]"); row.setEntities("[\"entity\"]");
        row.setCoveredThroughMessageId(covered); row.setSourceMessageCount(4); row.setVersion(version);
        row.setStatus(MemorySummary.Status.ACTIVE.name()); row.setIsDeleted(0);
        row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
        return row;
    }
}
