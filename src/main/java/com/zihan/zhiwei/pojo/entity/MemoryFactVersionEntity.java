package com.zihan.zhiwei.pojo.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @TableName("memory_fact_version")
public class MemoryFactVersionEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private Long factId; private Integer versionNo; private String valueJson; private String normalizedValueHash;
 private String sourceType; private String sourceRef; private BigDecimal confidence; private LocalDateTime validFrom;
 private LocalDateTime validTo; private LocalDateTime recordedAt; private String createdBy; private String changeReason;
 private LocalDateTime createdAt;
}