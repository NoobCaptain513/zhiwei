package com.zihan.zhiwei.ai.memory.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.MemoryAuditService;
import com.zihan.zhiwei.mapper.MemoryAuditEventMapper;
import com.zihan.zhiwei.pojo.entity.MemoryAuditEventEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;

@Service @RequiredArgsConstructor
public class MemoryAuditServiceImpl implements MemoryAuditService {
 private final MemoryAuditEventMapper mapper;
 private final ObjectMapper objectMapper;
 @Override public void append(AuditCommand c) {
  var row = new MemoryAuditEventEntity();
  row.setEventId(UUID.randomUUID().toString()); row.setUserId(c.userId());
  row.setResourceType(c.resourceType().name()); row.setResourceId(c.resourceId()); row.setAction(c.action().name());
  row.setOldVersion(c.oldVersion()); row.setNewVersion(c.newVersion()); row.setActorType(c.actorType().name());
  row.setActorId(c.actorId()); row.setRequestId(c.requestId()); row.setReason(c.reason());
  row.setPayloadHash(c.payload() == null ? null : sha256(c.payload()));
  try { row.setMetadataJson(c.metadata() == null ? null : objectMapper.writeValueAsString(c.metadata())); }
  catch (Exception e) { throw new IllegalArgumentException("audit metadata is not serializable", e); }
  row.setCreatedAt(LocalDateTime.now());
  mapper.insert(row);
 }
 @Override public List<MemoryAuditEvent> list(String userId, AuditFilter filter) {
  if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
  int limit = filter == null ? 25 : filter.limit();
  if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
  String type = filter == null || filter.resourceType() == null ? null : filter.resourceType().name();
  String resourceId = filter == null || filter.resourceId() == null || filter.resourceId().isBlank()
   ? null : filter.resourceId();
  return mapper.listOwned(userId, type, resourceId, limit).stream().map(this::toModel).toList();
 }
 private MemoryAuditEvent toModel(MemoryAuditEventEntity row) {
  try {
   return new MemoryAuditEvent(row.getEventId(), row.getUserId(),
    MemoryAuditEvent.ResourceType.valueOf(row.getResourceType()), row.getResourceId(),
    MemoryAuditEvent.Action.valueOf(row.getAction()), row.getOldVersion(), row.getNewVersion(),
    MemoryAuditEvent.ActorType.valueOf(row.getActorType()), row.getActorId(), row.getRequestId(),
    row.getReason(), row.getPayloadHash(), row.getMetadataJson() == null ? null : objectMapper.readTree(row.getMetadataJson()),
    row.getCreatedAt());
  } catch (Exception e) { throw new IllegalStateException("invalid stored audit event", e); }
 }
 private static String sha256(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch (Exception e) { throw new IllegalStateException(e); }
 }
}