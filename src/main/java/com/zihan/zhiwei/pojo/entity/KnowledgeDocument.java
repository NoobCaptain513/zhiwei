package com.zihan.zhiwei.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;
    private Integer totalChunks;
    private Integer indexedChunks;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;
}