package com.zihan.zhiwei.ai.memory;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import java.util.Map;
import java.util.List;
public interface MemoryAuditService {
 void append(AuditCommand command);
 List<MemoryAuditEvent> list(String userId, AuditFilter filter);
 record AuditCommand(String userId, MemoryAuditEvent.ResourceType resourceType, String resourceId,
  MemoryAuditEvent.Action action, Long oldVersion, Long newVersion, MemoryAuditEvent.ActorType actorType,
  String actorId, String requestId, String reason, String payload, Map<String, ?> metadata) {}
 record AuditFilter(MemoryAuditEvent.ResourceType resourceType, String resourceId, int limit) {}
}