package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.zihan.zhiwei.ai.memory.model.MemoryConflict;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemoryFactVersion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemoryFactService {
    FactView create(String userId, FactWrite write);
    Optional<FactView> get(String userId, long factId);
    List<FactView> list(String userId, FactFilter filter);
    FactView update(String userId, long factId, long expectedVersion, FactWrite write);
    Optional<MemoryConflict> proposeCandidate(String userId, long factId, long expectedVersion, FactWrite write);
    void delete(String userId, long factId, long expectedVersion, String actorId, String reason, String requestId);
    MemoryFact restore(String userId, long factId, long expectedVersion, String actorId, String reason, String requestId);
    List<MemoryFactVersion> versions(String userId, long factId);
    List<MemoryConflict> conflicts(String userId, long factId);
    MemoryConflict resolveConflict(String userId, long conflictId, long expectedVersion,
                                   MemoryConflict.Resolution resolution, JsonNode mergedValue,
                                   String actorId, String reason, String requestId);

    record FactWrite(String namespace, String subject, String predicate, JsonNode value,
                     MemoryFact.SourceType sourceType, String sourceRef, BigDecimal confidence,
                     LocalDateTime validFrom, LocalDateTime validTo, String actorId, String reason,
                     String requestId) {}

    record FactFilter(String namespace, String subject, String predicate, MemoryFact.State state, int limit) {}
    record FactView(MemoryFact fact, MemoryFactVersion currentVersion) {}
}
