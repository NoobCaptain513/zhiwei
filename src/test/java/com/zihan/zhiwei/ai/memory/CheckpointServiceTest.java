package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.impl.CheckpointServiceImpl;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.AgentCheckpointMapper;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import com.zihan.zhiwei.pojo.entity.AgentCheckpointEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckpointServiceTest {
    private final AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
    private final MemoryAuditService audit = mock(MemoryAuditService.class);
    private final MemoryProperties properties = new MemoryProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CheckpointStateValidator validator = new CheckpointStateValidator(objectMapper, properties);
    private final CheckpointService service = new CheckpointServiceImpl(mapper, audit, validator, objectMapper, properties);

    @Test void rejectsSensitiveKeysAtAnyDepthAndOversizedUtf8State() {
        assertThatThrownBy(() -> validator.validate(state(objectMapper.readTree("{\"nested\":{\"Authorization\":\"secret\"}}"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sensitive");
        assertThatThrownBy(() -> validator.validate(state(objectMapper.readTree("{\"apiKey\":\"secret\"}"))))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setCheckpointMaxBytes(80);
        assertThatThrownBy(() -> validator.validate(state(objectMapper.createObjectNode().put("safe", "界".repeat(50)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
    }

    @Test void validatesSchemaVersionAndTransitionMatrix() {
        assertThatThrownBy(() -> validator.validate(new CheckpointState(0, "node", List.of(), List.of(), List.of(), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schemaVersion");
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.RUNNING, AgentCheckpoint.Status.PAUSED)).isTrue();
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.RUNNING, AgentCheckpoint.Status.COMPLETED)).isTrue();
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.PAUSED, AgentCheckpoint.Status.RUNNING)).isTrue();
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.FAILED, AgentCheckpoint.Status.RUNNING)).isTrue();
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.COMPLETED, AgentCheckpoint.Status.RUNNING)).isFalse();
        assertThat(CheckpointServiceImpl.canTransition(AgentCheckpoint.Status.ABANDONED, AgentCheckpoint.Status.RUNNING)).isFalse();
    }

    @Test void createAndReadAreOwnerScopedAndAudited() {
        doAnswer(i -> { ((AgentCheckpointEntity) i.getArgument(0)).setId(9L); return 1; })
                .when(mapper).insert(any(AgentCheckpointEntity.class));
        var created = service.create(new CheckpointService.CreateCommand("alice", "run-1", 10L,
                AgentCheckpoint.Type.TOOL_CHAIN, "approval", state(null), AgentCheckpoint.Status.PAUSED,
                0, null, null, "alice", "pause", "req"));
        assertThat(created.id()).isEqualTo(9L);
        assertThat(created.version()).isEqualTo(1L);
        verify(audit).append(argThat(a -> a.resourceType() == com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent.ResourceType.CHECKPOINT));

        when(mapper.selectOwned("bob", 9L)).thenReturn(null);
        assertThat(service.get("bob", 9L)).isEmpty();
        verify(mapper).selectOwned("bob", 9L);
    }

    @Test void agenticCheckpointAuditDoesNotCopyExecutableState() {
        doAnswer(i -> { ((AgentCheckpointEntity) i.getArgument(0)).setId(9L); return 1; })
                .when(mapper).insert(any(AgentCheckpointEntity.class));

        service.create(new CheckpointService.CreateCommand("alice", "run-1", 10L,
                AgentCheckpoint.Type.AGENTIC_RAG, "classify", state(objectMapper.createObjectNode()
                        .put("query", "private")), AgentCheckpoint.Status.RUNNING,
                0, null, null, "system", "start", "req"));

        verify(audit).append(argThat(command -> command.payload() == null));
    }

    @Test void resumeUsesOwnerStatusAndVersionCasSoOnlyOneExecutorWins() {
        when(mapper.selectOwned("alice", 9L)).thenReturn(row(AgentCheckpoint.Status.PAUSED, 4L));
        when(mapper.casTransition(any(), eq(9L), eq("alice"), eq(4L), eq("PAUSED"))).thenReturn(1, 0);

        var resumed = service.resume("alice", 9L, 4L, "alice", "approved", "req");
        assertThat(resumed.status()).isEqualTo(AgentCheckpoint.Status.RUNNING);
        assertThat(resumed.version()).isEqualTo(5L);
        assertThatThrownBy(() -> service.resume("alice", 9L, 4L, "alice", "duplicate", "req2"))
                .isInstanceOf(MemoryVersionConflictException.class);
        verify(audit, times(1)).append(any());
    }

    @Test void updateProgressAdvancesOneRunningCheckpointWithVersionCas() {
        var running = row(AgentCheckpoint.Status.RUNNING, 4L);
        when(mapper.selectOwned("alice", 9L)).thenReturn(running);
        when(mapper.casProgress(any(), eq(9L), eq("alice"), eq(4L))).thenReturn(1);
        CheckpointState next = new CheckpointState(
                1, "grade", List.of("retrieve"), List.of("grade"), List.of(), Map.of(), null);

        var advanced = service.updateProgress(
                "alice", 9L, 4L, "grade", next, 3, "system", "node advanced", "req");

        assertThat(advanced.id()).isEqualTo(9L);
        assertThat(advanced.status()).isEqualTo(AgentCheckpoint.Status.RUNNING);
        assertThat(advanced.nodeName()).isEqualTo("grade");
        assertThat(advanced.sequenceNo()).isEqualTo(3);
        assertThat(advanced.version()).isEqualTo(5L);
        verify(mapper).casProgress(argThat(row -> "grade".equals(row.getNodeName())
                        && row.getSequenceNo() == 3), eq(9L), eq("alice"), eq(4L));
    }

    @Test void terminalStatesCannotResumeAndNormalTransitionsAreCasProtected() {
        when(mapper.selectOwned("alice", 9L)).thenReturn(row(AgentCheckpoint.Status.COMPLETED, 2L));
        assertThatThrownBy(() -> service.resume("alice", 9L, 2L, "alice", "retry", "req"))
                .isInstanceOf(IllegalStateException.class);

        when(mapper.selectOwned("alice", 9L)).thenReturn(row(AgentCheckpoint.Status.RUNNING, 2L));
        when(mapper.casTransition(any(), eq(9L), eq("alice"), eq(2L), eq("RUNNING"))).thenReturn(0);
        assertThatThrownBy(() -> service.transition("alice", 9L, 2L, AgentCheckpoint.Status.FAILED,
                "failed-node", state(null), "TIMEOUT", null, "system", "timeout", "req"))
                .isInstanceOf(MemoryVersionConflictException.class);
    }

    @Test void deletedCheckpointIsHiddenAndCanBeRestoredByOwnerWithinRetention() {
        var deleted = row(AgentCheckpoint.Status.PAUSED, 5L);
        deleted.setIsDeleted(1);
        deleted.setDeletedAt(LocalDateTime.now().minusDays(2));
        when(mapper.selectOwned("alice", 9L)).thenReturn(null);
        when(mapper.selectOwnedIncludingDeleted("alice", 9L)).thenReturn(deleted);
        when(mapper.casRestore(eq(9L), eq("alice"), eq(5L), any())).thenReturn(1);

        assertThat(service.get("alice", 9L)).isEmpty();
        var restored = service.restore("alice", 9L, 5L, "alice", "undo delete", "req");

        assertThat(restored.version()).isEqualTo(6L);
        verify(mapper).casRestore(eq(9L), eq("alice"), eq(5L), any());
        verify(audit).append(argThat(a -> a.action() == com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent.Action.RESTORE));
    }

    @Test void checkpointCannotBeRestoredAfterRetentionWindow() {
        var deleted = row(AgentCheckpoint.Status.PAUSED, 5L);
        deleted.setIsDeleted(1);
        deleted.setDeletedAt(LocalDateTime.now().minusDays(31));
        when(mapper.selectOwnedIncludingDeleted("alice", 9L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.restore("alice", 9L, 5L, "alice", "too late", "req"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
        verify(mapper, never()).casRestore(anyLong(), anyString(), anyLong(), any());
    }

    private CheckpointState state(com.fasterxml.jackson.databind.JsonNode resume) {
        return new CheckpointState(1, "approval", List.of("plan"), List.of("execute"),
                List.of("tool-1"), Map.of("tool-1", "pending approval"), resume);
    }

    private AgentCheckpointEntity row(AgentCheckpoint.Status status, long version) {
        var row = new AgentCheckpointEntity();
        row.setId(9L); row.setUserId("alice"); row.setRunId("run-1"); row.setConversationId(10L);
        row.setCheckpointType(AgentCheckpoint.Type.TOOL_CHAIN.name()); row.setNodeName("approval");
        try { row.setStateJson(objectMapper.writeValueAsString(state(null))); } catch (Exception e) { throw new RuntimeException(e); }
        row.setStatus(status.name()); row.setSequenceNo(0); row.setVersion(version);
        row.setExpiresAt(LocalDateTime.now().plusDays(1)); row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
        return row;
    }
}
