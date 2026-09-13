package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("memory_fact")
public class MemoryFactEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private String userId; private String namespace; private String subject; private String predicate; private String identityHash;
 private Long currentVersionId; private String state; private Long version; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 private LocalDateTime deletedAt; @TableLogic private Integer isDeleted;
}