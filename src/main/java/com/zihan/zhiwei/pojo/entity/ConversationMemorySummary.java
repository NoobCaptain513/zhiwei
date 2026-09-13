package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("conversation_memory_summary")
public class ConversationMemorySummary {
 @TableId(type=IdType.AUTO) private Long id;
 private Long conversationId; private String userId; private String summary;
 private String openLoops; private String decisions; private String entities;
 private Long coveredThroughMessageId; private Integer sourceMessageCount; private Long version;
 private String status; private LocalDateTime expiresAt; private LocalDateTime createdAt;
 private LocalDateTime updatedAt; private LocalDateTime deletedAt; @TableLogic private Integer isDeleted;
}