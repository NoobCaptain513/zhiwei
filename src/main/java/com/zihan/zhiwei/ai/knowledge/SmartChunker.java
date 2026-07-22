package com.zihan.zhiwei.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能分块器：512 token + 64 重叠滑动窗口。
 *
 * 策略：
 * 1. 先按段落 / 换行 切分（保留自然边界）
 * 2. 对每段估算 token 数（中文 ≈ 1 字 ≈ 1.5 token，英文 ≈ 4 字符 ≈ 1 token）
 * 3. 超过 maxTokens 的段落再按字符滑动窗口切分
 * 4. 相邻块之间保留 overlapTokens 的重叠
 */
@Slf4j
@Component
public class SmartChunker {

    @Value("${zhiwei.ai.knowledge.max-tokens:512}")
    private int maxTokens = 512;

    @Value("${zhiwei.ai.knowledge.overlap-tokens:64}")
    private int overlapTokens = 64;

    /**
     * 将文档文本分块。
     *
     * @param text       原文
     * @param documentId 文档 ID
     * @param sourceFile 来源文件名
     * @return 分块列表
     */
    public List<DocumentChunk> chunk(String text, Long documentId, String sourceFile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 1. 按段落切分
        String[] paragraphs = text.split("\\n{2,}");
        List<String> rawSegments = new ArrayList<>();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                rawSegments.add(trimmed);
            }
        }

        // 2. 对每段估算 token，超长的再滑动窗口切分
        List<String> tokenSegments = new ArrayList<>();
        for (String seg : rawSegments) {
            int estTokens = estimateTokens(seg);
            if (estTokens <= maxTokens) {
                tokenSegments.add(seg);
            } else {
                // 超长段落：按字符滑动窗口切分
                tokenSegments.addAll(splitByWindow(seg));
            }
        }

        // 3. 合并过短的相邻块（小于 maxTokens 的 1/3）
        List<String> merged = mergeShort(tokenSegments);

        // 4. 组装 DocumentChunk
        List<DocumentChunk> chunks = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < merged.size(); i++) {
            String chunkText = merged.get(i);
            int chunkTokens = estimateTokens(chunkText);
            chunks.add(DocumentChunk.builder()
                    .documentId(documentId)
                    .sourceFile(sourceFile)
                    .chunkIndex(i)
                    .content(chunkText)
                    .startOffset(offset)
                    .endOffset(offset + chunkText.length())
                    .tokenCount(chunkTokens)
                    .build());
            offset += chunkText.length();
        }

        log.info("[Chunker] docId={} file={} paragraphs={} chunks={} maxTokens={} overlap={}",
                documentId, sourceFile, rawSegments.size(), chunks.size(), maxTokens, overlapTokens);
        return chunks;
    }

    /**
     * 简单 token 估算。
     * 中文：1 字 ≈ 1 token（现代 tokenizer 通常 1~1.5）
     * 英文：1 词 ≈ 1 token（约 4 字符）
     * 混合：按字符类型加权
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cn = 0, en = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x4E00 && c < 0x9FFF || c > 0x3400 && c < 0x4DBF) {
                cn++;
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                en++;
            }
        }
        // 中文 1 字 ≈ 1 token，英文 4 字符 ≈ 1 token
        return cn + (int) Math.ceil(en / 4.0);
    }

    /**
     * 按滑动窗口切分超长文本。
     * 窗口大小 = maxTokens 对应的字符数，步进 = (maxTokens - overlapTokens) 对应的字符数。
     */
    private List<String> splitByWindow(String text) {
        // 将 token 数换算为字符数（粗估：1 token ≈ 2 字符，中英混合）
        int charWindow = maxTokens * 2;
        int charStep = (maxTokens - overlapTokens) * 2;

        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + charWindow, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            if (end >= text.length()) break;
            start += charStep;
        }
        return result;
    }

    /**
     * 合并过短的相邻块。
     */
    private List<String> mergeShort(List<String> segments) {
        int minTokens = maxTokens / 3;
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String seg : segments) {
            if (buffer.isEmpty()) {
                buffer.append(seg);
            } else if (estimateTokens(buffer.toString()) + estimateTokens(seg) <= maxTokens) {
                buffer.append("\n\n").append(seg);
            } else {
                merged.add(buffer.toString());
                buffer = new StringBuilder(seg);
            }
        }
        if (!buffer.isEmpty()) {
            merged.add(buffer.toString());
        }
        return merged;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getOverlapTokens() {
        return overlapTokens;
    }
}