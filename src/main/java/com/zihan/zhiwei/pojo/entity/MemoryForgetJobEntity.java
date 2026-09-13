package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("memory_forget_job")
public class MemoryForgetJobEntity {
 @TableId(type=IdType.INPUT) private String jobId;
 private String userId; private String scopeType; private String scopeId; private String status; private String requestedBy;
 private String reason; private LocalDateTime requestedAt; private LocalDateTime completedAt; private String resultJson;
}