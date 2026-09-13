package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("memory_audit_event")
public class MemoryAuditEventEntity {
 @TableId(type=IdType.INPUT) private String eventId;
 private String userId; private String resourceType; private String resourceId; private String action;
 private Long oldVersion; private Long newVersion; private String actorType; private String actorId; private String requestId;
 private String reason; private String payloadHash; private String metadataJson; private LocalDateTime createdAt;
}