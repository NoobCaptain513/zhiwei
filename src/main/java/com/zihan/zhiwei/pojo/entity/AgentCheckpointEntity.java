package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("agent_checkpoint")
public class AgentCheckpointEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private String runId; private Long conversationId; private String userId; private String checkpointType;
 private String nodeName; private String stateJson; private String status; private Integer sequenceNo; private Long version;
 private LocalDateTime resumeAfter; private String errorCode; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 private LocalDateTime expiresAt; private LocalDateTime deletedAt; @TableLogic private Integer isDeleted;
}