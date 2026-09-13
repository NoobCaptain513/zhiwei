package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("memory_conflict")
public class MemoryConflictEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private Long factId; private Long baseVersionId; private Long candidateVersionId; private String type; private String status;
 private String resolution; private Long resolvedVersionId; private String resolvedBy; private String reason;
 private LocalDateTime createdAt; private LocalDateTime resolvedAt;
}