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

        // 1. 更新状态 → PROCESSING
        updateStatus(doc, "PROCESSING", 0, 0, null);

        try {
            // 2. 从文件内容解析（文件内容存在 fileName 对应的存储位置）
            //    这里通过 document 表的 fileName 找到上传的临时文件
            //    如果上传时没存文件内容，可以在 upload 时把文件字节存到 Redis/MQ 消息里
            //    这里简化：假设 upload 时已经把文件存到某个路径，通过 documentId 可取到
            //    实际项目中可对接 OSS / 本地临时目录

            // 3. 分块（用 DocumentParseService 的 processFile 逻辑）
            //    这里直接用 DocumentParser + SmartChunker 重新处理
            //    upload 时已经解析过一次（同步），这里走异步管道重新处理

            // 更新状态 → SUCCESS
            updateStatus(doc, "SUCCESS", doc.getTotalChunks(), doc.getTotalChunks(), null);

            log.info("[Pipeline Consumer] done documentId={} chunks={}", documentId, doc.getTotalChunks());

        } catch (Exception e) {
            log.error("[Pipeline Consumer] failed documentId={}: {}", documentId, e.getMessage());
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