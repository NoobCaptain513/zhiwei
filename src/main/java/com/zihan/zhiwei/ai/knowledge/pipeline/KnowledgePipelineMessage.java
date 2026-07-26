package com.zihan.zhiwei.ai.knowledge.pipeline;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgePipelineMessage implements Serializable {

    private Long documentId;
    private String userId;
    private String fileName;

    /**
     * 修复 P0-2：将文件内容直接携带在 MQ 消息中，
     * 使 Consumer 消费时无需再从外部存储读取文件字节。
     */
    private byte[] fileContent;
}