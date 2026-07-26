package com.zihan.zhiwei.ai.knowledge.pipeline;

import com.zihan.zhiwei.ai.knowledge.DocumentChunk;
import com.zihan.zhiwei.ai.knowledge.DocumentParser;
import com.zihan.zhiwei.ai.knowledge.SmartChunker;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.mapper.KnowledgeDocumentMapper;
import com.zihan.zhiwei.pojo.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * D18: 异步消费文档管道消息。
 *
 * 流程：收到消息 → 更新状态 PROCESSING → Tika 解析 → SmartChunker 分块
 *       → 逐块 Embedding + 写 pgvector → 更新状态 SUCCESS/FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgePipelineConsumer {

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentParser documentParser;
    private final SmartChunker smartChunker;
    private final AiRagService aiRagService;

    @RabbitListener(queues = KnowledgePipelineConfig.QUEUE)
    @Transactional
    public void onMessage(KnowledgePipelineMessage message) {
        Long documentId = message.getDocumentId();
        String fileName = message.getFileName();
        log.info("[Pipeline Consumer] received documentId={} fileName={}", documentId, fileName);

        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("[Pipeline Consumer] document not found: {}", documentId);
            return;
        }

        // P0-2 修复：从 MQ 消息中获取文件字节，调用 processDocument 真正执行异步处理
        byte[] fileContent = message.getFileContent();
        if (fileContent == null || fileContent.length == 0) {
            log.error("[Pipeline Consumer] fileContent is empty for documentId={}, cannot process", documentId);
            updateStatus(doc, "FAILED", 0, 0, "文件内容为空，无法处理");
            return;
        }

        try {
            processDocument(doc, new ByteArrayInputStream(fileContent));
        } catch (Exception e) {
            log.error("[Pipeline Consumer] processDocument failed for documentId={}: {}", documentId, e.getMessage(), e);
            updateStatus(doc, "FAILED", doc.getTotalChunks(), doc.getIndexedChunks(), e.getMessage());
        }
    }

    /**
     * 重载：接收 MultipartFile 场景（upload 时直接调用）
     * 或者在消费时，从文件存储中读取 InputStream 再调此方法
     */
    @Transactional
    public void processDocument(KnowledgeDocument doc, java.io.InputStream fileStream) {
        Long documentId = doc.getId();
        String fileName = doc.getFileName();
        log.info("[Pipeline] start processDocument id={} file={}", documentId, fileName);

        updateStatus(doc, "PROCESSING", 0, 0, null);

        try {
            // 1. Tika 解析
            DocumentParser.ParseResult parsed = documentParser.parse(fileStream, fileName);
            log.info("[Pipeline] parsed file={} textLen={}", fileName, parsed.text().length());

            // 2. 分块
            List<DocumentChunk> chunks = smartChunker.chunk(parsed.text(), documentId, fileName);
            int totalChunks = chunks.size();
            updateStatus(doc, "PROCESSING", totalChunks, 0, null);

            // 3. 逐块 Embedding + 入库
            int indexed = 0;
            for (DocumentChunk chunk : chunks) {
                try {
                    aiRagService.upsertChunk(
                            chunk.getDocumentId(),
                            chunk.getSourceFile() + "#" + chunk.getChunkIndex(),
                            chunk.getSection() != null ? chunk.getSection() : fileName,
                            chunk.getContent());
                    indexed++;
                } catch (Exception e) {
                    log.warn("[Pipeline] chunk {} failed: {}", chunk.getChunkIndex(), e.getMessage());
                }
            }

            // 4. 更新状态 → SUCCESS
            updateStatus(doc, "SUCCESS", totalChunks, indexed, null);
            log.info("[Pipeline] done id={} total={} indexed={}", documentId, totalChunks, indexed);

        } catch (Exception e) {
            log.error("[Pipeline] failed id={}: {}", documentId, e.getMessage());
            updateStatus(doc, "FAILED", doc.getTotalChunks(), doc.getIndexedChunks(), e.getMessage());
        }
    }

    private void updateStatus(KnowledgeDocument doc, String status, int total, int indexed, String error) {
        doc.setStatus(status);
        doc.setTotalChunks(total);
        doc.setIndexedChunks(indexed);
        doc.setErrorMessage(error);
        documentMapper.updateById(doc);
    }
}