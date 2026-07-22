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
}