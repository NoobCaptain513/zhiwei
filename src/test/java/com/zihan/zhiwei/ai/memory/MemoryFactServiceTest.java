package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.impl.MemoryFactServiceImpl;
import com.zihan.zhiwei.ai.memory.model.MemoryConflict;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.mapper.MemoryConflictMapper;
import com.zihan.zhiwei.mapper.MemoryFactMapper;
import com.zihan.zhiwei.mapper.MemoryFactVersionMapper;
import com.zihan.zhiwei.pojo.entity.MemoryConflictEntity;
import com.zihan.zhiwei.pojo.entity.MemoryFactEntity;
import com.zihan.zhiwei.pojo.entity.MemoryFactVersionEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryFactServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryFactMapper facts = mock(MemoryFactMapper.class);
    private final MemoryFactVersionMapper versions = mock(MemoryFactVersionMapper.class);
    private final MemoryConflictMapper conflicts = mock(MemoryConflictMapper.class);
    private final MemoryAuditService audit = mock(MemoryAuditService.class);
    private final MemoryFactService service = new MemoryFactServiceImpl(
            facts, versions, conflicts, audit, new MemoryValueNormalizer(objectMapper), objectMapper);

    @Test
    void createsActiveFactAndImmutableFirstVersion() throws Exception {
        doAnswer(i -> { ((MemoryFactEntity) i.getArgument(0)).setId(10L); return 1; }).when(facts).insert(any(MemoryFactEntity.class));
        doAnswer(i -> { ((MemoryFactVersionEntity) i.getArgument(0)).setId(20L); return 1; }).when(versions).insert(any(MemoryFactVersionEntity.class));
        when(facts.casSetInitialVersion(10L, "alice", 1L, 20L, "ACTIVE")).thenReturn(1);

        var created = service.create("alice", write("{\"b\":2,\"a\":1}", MemoryFact.SourceType.USER, null));

        assertThat(created.fact().currentVersionId()).isEqualTo(20L);
        assertThat(created.fact().state()).isEqualTo(MemoryFact.State.ACTIVE);
        assertThat(created.currentVersion().value()).isEqualTo(objectMapper.readTree("{\"a\":1,\"b\":2}"));
        assertThat(created.currentVersion().versionNo()).isEqualTo(1);
        verify(versions).insert(argThat((MemoryFactVersionEntity v) -> v.getNormalizedValueHash().matches("[0-9a-f]{64}")
                && v.getVersionNo() == 1));
        verify(audit).append(argThat(a -> a.action() == com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent.Action.CREATE));
    }

    @Test
    void sameModelCandidateIsIdempotentButMismatchOpensConflictWithoutReplacingCurrent() throws Exception {
        var fact = fact(10, 20, 3, "ACTIVE");
        var current = version(20, 10, 1, "{\"x\":1}", new MemoryValueNormalizer(objectMapper).sha256(objectMapper.readTree("{\"x\":1}")));
        when(facts.selectOwned(10L, "alice")).thenReturn(fact);
        when(versions.selectById(20L)).thenReturn(current);

        var same = service.proposeCandidate("alice", 10L, 3L,
                write("{\"x\":1.0}", MemoryFact.SourceType.AGENT_EXTRACTED, "message-1"));
        assertThat(same).isEmpty();
        verify(versions, never()).insert(any(MemoryFactVersionEntity.class));

        doAnswer(i -> { ((MemoryFactVersionEntity) i.getArgument(0)).setId(21L); return 1; }).when(versions).insert(any(MemoryFactVersionEntity.class));
        doAnswer(i -> { ((MemoryConflictEntity) i.getArgument(0)).setId(30L); return 1; }).when(conflicts).insert(any(MemoryConflictEntity.class));
        when(facts.casMarkConflicted(10L, "alice", 3L)).thenReturn(1);

        var opened = service.proposeCandidate("alice", 10L, 3L,
                write("{\"x\":2}", MemoryFact.SourceType.AGENT_EXTRACTED, "message-2"));

        assertThat(opened).get().extracting(MemoryConflict::status).isEqualTo(MemoryConflict.Status.OPEN);
        assertThat(opened.get().baseVersionId()).isEqualTo(20L);
        assertThat(opened.get().candidateVersionId()).isEqualTo(21L);
        verify(facts).casMarkConflicted(10L, "alice", 3L);
        verify(facts, never()).casActivateVersion(eq(10L), anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void explicitUserUpdateInsertsVersionThenUsesOwnerScopedCas() throws Exception {
        when(facts.selectOwned(10L, "alice")).thenAnswer(i -> fact(10, 20, 3, "ACTIVE"));
        when(versions.selectById(20L)).thenReturn(version(20, 10, 1, "{\"x\":1}", "different-hash"));
        when(versions.selectMaxVersionNo(10L)).thenReturn(1);
        doAnswer(i -> { ((MemoryFactVersionEntity) i.getArgument(0)).setId(22L); return 1; }).when(versions).insert(any(MemoryFactVersionEntity.class));
        when(facts.casActivateVersion(10L, "alice", 3L, 22L, "ACTIVE")).thenReturn(1);

        var updated = service.update("alice", 10L, 3L,
                write("{\"x\":2}", MemoryFact.SourceType.USER, null));

        assertThat(updated.fact().version()).isEqualTo(4L);
        assertThat(updated.currentVersion().versionNo()).isEqualTo(2);
        verify(versions).insert(any(MemoryFactVersionEntity.class));
        verify(facts).casActivateVersion(10L, "alice", 3L, 22L, "ACTIVE");

        when(facts.casActivateVersion(10L, "alice", 3L, 22L, "ACTIVE")).thenReturn(0);
        assertThatThrownBy(() -> service.update("alice", 10L, 3L,
                write("{\"x\":3}", MemoryFact.SourceType.ADMIN, null)))
                .isInstanceOf(MemoryVersionConflictException.class);
    }

    @Test
    void readsListsDeletesRestoresAndListsHistoryWithOwnerIsolation() {
        var fact = fact(10, 20, 3, "ACTIVE");
        when(facts.selectOwned(10L, "alice")).thenReturn(fact);
        when(versions.selectById(20L)).thenReturn(version(20, 10, 1, "{\"x\":1}", "hash"));
        assertThat(service.get("alice", 10L)).isPresent();
        verify(facts).selectOwned(10L, "alice");

        when(facts.listOwned("alice", "profile", null, null, null, 25)).thenReturn(List.of(fact));
        assertThat(service.list("alice", new MemoryFactService.FactFilter("profile", null, null, null, 25))).hasSize(1);

        when(versions.listOwned(10L, "alice")).thenReturn(List.of(version(20, 10, 1, "{\"x\":1}", "hash")));
        assertThat(service.versions("alice", 10L)).extracting(v -> v.versionNo()).containsExactly(1);

        when(facts.casSoftDelete(eq(10L), eq("alice"), eq(3L), any())).thenReturn(1);
        service.delete("alice", 10L, 3L, "alice", "cleanup", "req-delete");
        when(facts.selectOwnedIncludingDeleted(10L, "alice")).thenReturn(fact(10, 20, 4, "DELETED"));
        when(facts.casRestore(eq(10L), eq("alice"), eq(4L), any())).thenReturn(1);
        assertThat(service.restore("alice", 10L, 4L, "alice", "undo", "req-restore").version()).isEqualTo(5L);
    }

    private MemoryFactService.FactWrite write(String json, MemoryFact.SourceType source, String sourceRef) throws Exception {
        return new MemoryFactService.FactWrite("profile", "alice", "timezone", objectMapper.readTree(json), source,
                sourceRef, null, null, null, "alice", "test", "req-1");
    }

    private MemoryFactEntity fact(long id, long currentId, long aggregateVersion, String state) {
        var row = new MemoryFactEntity();
        row.setId(id); row.setUserId("alice"); row.setNamespace("profile"); row.setSubject("alice"); row.setPredicate("timezone");
        row.setCurrentVersionId(currentId); row.setVersion(aggregateVersion); row.setState(state); row.setIsDeleted("DELETED".equals(state) ? 1 : 0);
        row.setCreatedAt(LocalDateTime.now()); row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private MemoryFactVersionEntity version(long id, long factId, int no, String json, String hash) {
        var row = new MemoryFactVersionEntity();
        row.setId(id); row.setFactId(factId); row.setVersionNo(no); row.setValueJson(json); row.setNormalizedValueHash(hash);
        row.setSourceType("USER"); row.setRecordedAt(LocalDateTime.now()); row.setCreatedAt(LocalDateTime.now()); row.setCreatedBy("alice");
        return row;
    }
}
