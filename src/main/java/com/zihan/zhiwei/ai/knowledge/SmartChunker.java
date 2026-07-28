package com.zihan.zhiwei.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能分块器：512 token + 64 重叠滑动窗口。
 * <p>
 * FIX-9: 委托 TokenCounter（jtokkit BPE）进行真实计数，
 * 滑动窗口的"token→字符"换算按该段落实际 chars/token 比例。
 */
@Slf4j
@Component
public class SmartChunker {

    @Value("${zhiwei.ai.knowledge.max-tokens:512}")
    private int maxTokens = 512;

    @Value("${zhiwei.ai.knowledge.overlap-tokens:64}")
    private int overlapTokens = 64;

    private final TokenCounter tokenCounter;

    public SmartChunker(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<DocumentChunk> chunk(String text, Long documentId, String sourceFile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] paragraphs = text.split("\\n{2,}");
        List<String> rawSegments = new ArrayList<>();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                rawSegments.add(trimmed);
            }
        }

        List<String> tokenSegments = new ArrayList<>();
        for (String seg : rawSegments) {
            int tokens = estimateTokens(seg);
            if (tokens <= maxTokens) {
                tokenSegments.add(seg);
            } else {
                tokenSegments.addAll(splitByWindow(seg));
            }
        }

        List<String> merged = mergeShort(tokenSegments);

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

    /** FIX-9: 委托 TokenCounter */
    public int estimateTokens(String text) {
        return tokenCounter.count(text);
    }

    /** FIX-9: 按该段落实际 chars/token 比例换算字符窗口 */
    private List<String> splitByWindow(String text) {
        double charsPerToken = tokenCounter.charsPerToken(text);
        int charWindow = Math.max(64, (int) (maxTokens * charsPerToken));
        int charStep = Math.max(32, (int) ((maxTokens - overlapTokens) * charsPerToken));

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

    private List<String> mergeShort(List<String> segments) {
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
