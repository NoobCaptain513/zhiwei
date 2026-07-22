package com.zihan.zhiwei.ai.knowledge;

import com.zihan.zhiwei.ai.rag.AiRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析 + 分块 + 入库的完整管道。
 * 上传文件 → Tika 解析 → SmartChunker 分块 → Embedding + pgvector 入库
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentParseService {

    private final DocumentParser documentParser;
    private final SmartChunker smartChunker;
    private final AiRagService aiRagService;

    /**
     * 处理单个文件：解析 → 分块 → 入库。
     *
     * @return 入库的 chunk 数量
     */
    public int processFile(MultipartFile file, Long documentId) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();

        // 1. 解析
        DocumentParser.ParseResult parsed = documentParser.parse(file.getInputStream(), fileName);
        log.info("[DocPipeline] parsed file={} textLen={} mimeType={}", fileName, parsed.text().length(), parsed.mimeType());

        // 2. 分块
        List<DocumentChunk> chunks = smartChunker.chunk(parsed.text(), documentId, fileName);
        if (chunks.isEmpty()) {
            log.warn("[DocPipeline] no chunks from file={}", fileName);
            return 0;
        }

        // 3. 逐块 Embedding + 入库
        int count = 0;
        for (DocumentChunk chunk : chunks) {
            try {
                aiRagService.upsertChunk(
                        chunk.getDocumentId(),
                        chunk.getSourceFile() + "#" + chunk.getChunkIndex(),
                        chunk.getSection() != null ? chunk.getSection() : fileName,
                        chunk.getContent()
                );
                count++;
            } catch (Exception e) {
                log.warn("[DocPipeline] chunk {} failed: {}", chunk.getChunkIndex(), e.getMessage());
            }
        }

        log.info("[DocPipeline] file={} chunks={} indexed={}", fileName, chunks.size(), count);
        return count;
    }

    /**
     * 处理纯文本内容（不经过 Tika）。
     */
    public int processText(String content, Long documentId, String sourceName) {
        DocumentParser.ParseResult parsed = documentParser.parseText(content, sourceName);
        List<DocumentChunk> chunks = smartChunker.chunk(parsed.text(), documentId, sourceName);
        int count = 0;
        for (DocumentChunk chunk : chunks) {
            try {
                aiRagService.upsertChunk(
                        chunk.getDocumentId(),
                        chunk.getSourceFile() + "#" + chunk.getChunkIndex(),
                        chunk.getSection() != null ? chunk.getSection() : sourceName,
                        chunk.getContent()
                );
                count++;
            } catch (Exception e) {
                log.warn("[DocPipeline] chunk {} failed: {}", chunk.getChunkIndex(), e.getMessage());
            }
        }
        return count;
    }
}