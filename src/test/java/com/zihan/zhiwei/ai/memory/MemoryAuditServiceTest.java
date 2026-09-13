package com.zihan.zhiwei.ai.memory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.impl.MemoryAuditServiceImpl;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.mapper.MemoryAuditEventMapper;
import com.zihan.zhiwei.pojo.entity.MemoryAuditEventEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MemoryAuditServiceTest {
 @Test void appendStoresOnlyHashAndMetadataNeverResourceBody() {
  var mapper = mock(MemoryAuditEventMapper.class);
  var service = new MemoryAuditServiceImpl(mapper, new ObjectMapper());
  service.append(new MemoryAuditService.AuditCommand("u", MemoryAuditEvent.ResourceType.FACT, "7",
    MemoryAuditEvent.Action.UPDATE, 1L, 2L, MemoryAuditEvent.ActorType.USER, "u", "req", "correction",
    "secret fact body", java.util.Map.of("namespace", "profile")));
  var captor = ArgumentCaptor.forClass(MemoryAuditEventEntity.class);
  verify(mapper, times(1)).insert(captor.capture());
  verifyNoMoreInteractions(mapper);
  var row = captor.getValue();
  assertThat(row.getPayloadHash()).matches("[0-9a-f]{64}");
  assertThat(row.getMetadataJson()).doesNotContain("secret fact body");
 }
}