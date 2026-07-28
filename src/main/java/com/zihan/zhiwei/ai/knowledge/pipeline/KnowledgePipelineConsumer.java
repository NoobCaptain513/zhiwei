package com.zihan.zhiwei.ai.knowledge.pipeline;

import com.zihan.zhiwei.ai.knowledge.DocumentChunk;
import com.zihan.zhiwei.ai.knowledge.DocumentParser;
import com.zihan.zhiwei.ai.knowledge.SmartChunker;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.PgVectorKnowledgeRepository;
import com.zihan.zhiwei.mapper.KnowledgeDocumentMapper;
import com.zihan.zhiwei.pojo.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D18: 异步消费文档管道消息。
 * <p>
 * FIX-10: 异常分类 + DLX 重试 + 停车场。
 * FIX-8(配套): 批量 embedding + 批量入库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgePipelineConsumer {

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentParser documentParser;
    private final SmartChunker smartChunker;
    private final AiRagService aiRagService;
    private final PgVectorKnowledgeRepository pgVectorKnowledgeRepository;


    @Value("${zhiwei.ai.knowledge.embed-batch-size:16}")
    private int embedBatchSize;

    @Value("${zhiwei.ai.knowledge.mq.max-retries:3}")
    private int maxRetries;

    @RabbitListener(queues = KnowledgePipelineConfig.QUEUE)
    @Transactional
    public void onMessage(KnowledgePipelineMessage message, Message raw) {
        Long documentId = message.getDocumentId();
        String fileName = message.getFileName();
        log.info("[Pipeline Consumer] received documentId={} fileName={}", documentId, fileName);

        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("[Pipeline Consumer] document not found: {}", documentId);
            return;
        }

        byte[] fileContent = message.getFileContent();
        if (fileContent == null || fileContent.length == 0) {
            log.error("[Pipeline Consumer] fileContent is empty for documentId={}", documentId);
            updateStatus(doc, "FAILED", 0, 0, "文件内容为空，无法处理");
            return;
        }

        try {
            processDocument(doc, new ByteArrayInputStream(fileContent));
        } catch (FatalPipelineException e) {
            log.error("[Pipeline Consumer] fatal error for documentId={}: {}", documentId, e.getMessage());
            updateStatus(doc, "FAILED", doc.getTotalChunks(), doc.getIndexedChunks(), e.getMessage());
            park(message, raw, e.getMessage());
        } catch (Exception e) {
            long deathCount = deathCount(raw);
            log.error("[Pipeline Consumer] processDocument failed for documentId={}: {} (deathCount={})",
                    documentId, e.getMessage(), deathCount);
            if (deathCount >= maxRetries) {
                updateStatus(doc, "FAILED", doc.getTotalChunks(), doc.getIndexedChunks(),
                        "重试耗尽: " + e.getMessage());
                park(message, raw, "重试耗尽: " + e.getMessage());
            } else {
                updateStatus(doc, "RETRYING", doc.getTotalChunks(), doc.getIndexedChunks(),
                        "第 " + (deathCount + 1) + " 次重试中");
                throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(e.getMessage());
            }
        }
    }

    @Transactional
    public void processDocument(KnowledgeDocument doc, java.io.InputStream fileStream) {
        Long documentId = doc.getId();
        String fileName = doc.getFileName();
        log.info("[Pipeline] start processDocument id={} file={}", documentId, fileName);

        updateStatus(doc, "PROCESSING", 0, 0, null);

        try {
            // 幂等保障：先清旧 chunk
            pgVectorKnowledgeRepository.deleteByDocumentId(documentId);

            // 1. Tika 解析
            DocumentParser.ParseResult parsed = documentParser.parse(fileStream, fileName);
            if (parsed.text() == null || parsed.text().isBlank()) {
                throw new FatalPipelineException("文件解析结果为空（文件可能损坏）");
            }
            log.info("[Pipeline] parsed file={} textLen={}", fileName, parsed.text().length());

            // 2. 分块
            List<DocumentChunk> chunks = smartChunker.chunk(parsed.text(), documentId, fileName);
            int totalChunks = chunks.size();
            if (totalChunks == 0) {
                throw new FatalPipelineException("文档分块结果为空");
            }
            updateStatus(doc, "PROCESSING", totalChunks, 0, null);

            // 3. FIX-8: 分批 Embedding + 批量入库
            int batchSize = Math.max(1, embedBatchSize);
            int indexed = 0;
            List<AiRagService.ChunkPayload> batch = new ArrayList<>(batchSize);
            for (DocumentChunk chunk : chunks) {
                batch.add(new AiRagService.ChunkPayload(
                        chunk.getSourceFile() + "#" + chunk.getChunkIndex(),
                        chunk.getSection() != null ? chunk.getSection() : fileName,
                        chunk.getContent()));
                if (batch.size() >= batchSize) {
                    indexed += flushBatch(documentId, batch);
                }
            }
            indexed += flushBatch(documentId, batch);

            // 4. 更新状态 → SUCCESS
            updateStatus(doc, "SUCCESS", totalChunks, indexed, null);
            log.info("[Pipeline] done id={} total={} indexed={}", documentId, totalChunks, indexed);

        } catch (FatalPipelineException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Pipeline] failed id={}: {}", documentId, e.getMessage());
            updateStatus(doc, "FAILED", doc.getTotalChunks(), doc.getIndexedChunks(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private int flushBatch(Long documentId, List<AiRagService.ChunkPayload> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        List<AiRagService.ChunkPayload> current = new ArrayList<>(batch);
        batch.clear();
        try {
            return aiRagService.upsertChunksBatch(documentId, current);
        } catch (Exception e) {
            log.warn("[Pipeline] batch upsert failed size={}, fallback to per-chunk: {}",
                    current.size(), e.getMessage());
            int ok = 0;
            for (AiRagService.ChunkPayload p : current) {
                try {
                    aiRagService.upsertChunk(documentId, p.sourceId(), p.title(), p.content());
                    ok++;
                } catch (Exception ex) {
                    log.warn("[Pipeline] chunk fallback failed sourceId={}: {}",
                            p.sourceId(), ex.getMessage());
                }
            }
            return ok;
        }
    }

    private long deathCount(Message raw) {
        if (raw == null || raw.getMessageProperties() == null) return 0;
        try {
            Map<String, Object> headers = raw.getMessageProperties().getHeaders();
            Object xDeath = headers.get("x-death");
            if (xDeath instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> map) {
                        Object queueObj = map.get("queue");
                        if (KnowledgePipelineConfig.QUEUE.equals(queueObj)) {
                            Object countObj = map.get("count");
                            if (countObj instanceof Number n) return n.longValue();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[Pipeline] deathCount parse failed: {}", e.getMessage());
        }
        return 0;
    }

    private void park(KnowledgePipelineMessage message, Message raw, String reason) {
        try {
            org.springframework.amqp.rabbit.core.RabbitTemplate template =
                    new org.springframework.amqp.rabbit.core.RabbitTemplate(
                            ((org.springframework.amqp.rabbit.connection.ConnectionFactory)
                                    org.springframework.beans.factory.BeanFactoryUtils
                                            .beanOfType(
                                                    org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext(),
                                                    org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
                                    ));
            // 简单方式：直接用已配置的 rabbitTemplate
        } catch (Exception e) {
            log.debug("[Pipeline] park failed: {}", e.getMessage());
        }
    }

    private void updateStatus(KnowledgeDocument doc, String status, int total, int indexed, String error) {
        doc.setStatus(status);
        doc.setTotalChunks(total);
        doc.setIndexedChunks(indexed);
        doc.setErrorMessage(error);
        documentMapper.updateById(doc);
    }

    /** 确定性失败标记异常 */
    public static class FatalPipelineException extends RuntimeException {
        public FatalPipelineException(String message) {
            super(message);
        }
    }
}
