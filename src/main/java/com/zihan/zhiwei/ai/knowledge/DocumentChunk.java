package com.zihan.zhiwei.ai.knowledge;

import lombok.Builder;
import lombok.Data;

/**
 * 文档分块结果。
 */
@Data
@Builder
public class DocumentChunk {

    /** 所属文档 ID */
    private Long documentId;

    /** 来源文件名 */
    private String sourceFile;

    /** 块序号（从 0 开始） */
    private int chunkIndex;

    /** 分块内容 */
    private String content;

    /** 该块在原文中的起始字符偏移 */
    private int startOffset;

    /** 该块在原文中的结束字符偏移 */
    private int endOffset;

    /** 该块大约的 token 数 */
    private int tokenCount;

    /** 所属章节 / 段落标题（可选） */
    private String section;
}