package com.zihan.zhiwei.ai.knowledge.pipeline;

import com.zihan.zhiwei.ai.knowledge.DocumentChunk;
import com.zihan.zhiwei.ai.knowledge.DocumentParser;
import com.zihan.zhiwei.ai.knowledge.SmartChunker;
import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.mapper.KnowledgeDocumentMapper;
import com.zihan.zhiwei.pojo.entity.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("文档管道 Pipeline 测试")
class KnowledgePipelineTests {

    // ──────────────────────────────────────────
    // Producer 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("KnowledgePipelineProducer")
    class ProducerTests {

        @Mock private RabbitTemplate rabbitTemplate;

        @Test
        @DisplayName("发送消息 → convertAndSend 到正确的 exchange/routing")
        void shouldSendToCorrectExchangeAndRouting() {
            KnowledgePipelineProducer producer = new KnowledgePipelineProducer(rabbitTemplate);

            producer.sendDocumentMessage(1L, "u1", "redis.pdf");

            verify(rabbitTemplate).convertAndSend(
                    eq(KnowledgePipelineConfig.EXCHANGE),
                    eq(KnowledgePipelineConfig.ROUTING),
                    ArgumentMatchers.<KnowledgePipelineMessage>argThat(msg -> {
                        return msg.getDocumentId() == 1L
                                && "u1".equals(msg.getUserId())
                                && "redis.pdf".equals(msg.getFileName());
                    }));
        }
    }

    // ──────────────────────────────────────────
    // Message 序列化
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("KnowledgePipelineMessage")
    class MessageTests {

        @Test
        @DisplayName("构建 → 字段完整")
        void shouldBuildCorrectly() {
            KnowledgePipelineMessage msg = new KnowledgePipelineMessage(42L, "u1", "file.pdf");

            assertThat(msg.getDocumentId()).isEqualTo(42L);
            assertThat(msg.getUserId()).isEqualTo("u1");
            assertThat(msg.getFileName()).isEqualTo("file.pdf");
        }

        @Test
        @DisplayName("无参构造 + setter")
        void shouldWorkWithSetters() {
            KnowledgePipelineMessage msg = new KnowledgePipelineMessage();
            msg.setDocumentId(1L);
            msg.setUserId("u1");
            msg.setFileName("test.txt");

            assertThat(msg.getDocumentId()).isEqualTo(1L);
        }
    }

    // ──────────────────────────────────────────
    // Config 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("KnowledgePipelineConfig")
    class ConfigTests {

        @Test
        @DisplayName("Exchange/Queue/Routing 名称正确")
        void shouldHaveCorrectNames() {
            assertThat(KnowledgePipelineConfig.EXCHANGE).isEqualTo("knowledge.pipeline.exchange");
            assertThat(KnowledgePipelineConfig.QUEUE).isEqualTo("knowledge.pipeline.queue");
            assertThat(KnowledgePipelineConfig.ROUTING).isEqualTo("knowledge.pipeline.document");
        }

        @Test
        @DisplayName("Queue 持久化")
        void shouldCreateDurableQueue() {
            KnowledgePipelineConfig config = new KnowledgePipelineConfig();
            var queue = config.knowledgeQueue();

            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getName()).isEqualTo("knowledge.pipeline.queue");
        }

        @Test
        @DisplayName("DirectExchange 类型正确")
        void shouldCreateDirectExchange() {
            KnowledgePipelineConfig config = new KnowledgePipelineConfig();
            var exchange = config.knowledgeExchange();

            assertThat(exchange.getType()).isEqualTo("direct");
            assertThat(exchange.getName()).isEqualTo("knowledge.pipeline.exchange");
        }
    }

    // ──────────────────────────────────────────
    // Consumer 测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("KnowledgePipelineConsumer")
    class ConsumerTests {

        @Mock private KnowledgeDocumentMapper documentMapper;
        @Mock private DocumentParser documentParser;
        @Mock private SmartChunker smartChunker;
        @Mock private AiRagService aiRagService;

        private KnowledgePipelineConsumer consumer;

        @BeforeEach
        void setUp() {
            consumer = new KnowledgePipelineConsumer(
                    documentMapper, documentParser, smartChunker, aiRagService);
        }

        @Test
        @DisplayName("onMessage → document 不存在 → warn 返回")
        void shouldWarnWhenDocumentNotFound() {
            when(documentMapper.selectById(42L)).thenReturn(null);

            consumer.onMessage(new KnowledgePipelineMessage(42L, "u1", "ghost.pdf"));

            verify(documentMapper).selectById(42L);
            verify(documentMapper, never()).updateById(ArgumentMatchers.<KnowledgeDocument>any());
        }

        @Test
        @DisplayName("onMessage → 找到 doc → 更新 PROCESSING → 成功 → SUCCESS")
        void shouldProcessDocumentSuccessfully() {
            KnowledgeDocument doc = buildDoc(1L, "redis.pdf", "PENDING");
            when(documentMapper.selectById(1L)).thenReturn(doc);

            consumer.onMessage(new KnowledgePipelineMessage(1L, "u1", "redis.pdf"));

            // 验证状态流转
            ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
            verify(documentMapper, atLeastOnce()).updateById(captor.capture());

            List<KnowledgeDocument> updates = captor.getAllValues();
            // 最终状态应为 SUCCESS
            KnowledgeDocument lastUpdate = updates.get(updates.size() - 1);
            assertThat(lastUpdate.getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("processDocument → 解析+分块+embedding → 状态演变")
        void shouldProcessDocumentPipeline() throws Exception {
            KnowledgeDocument doc = buildDoc(1L, "redis.pdf", "PENDING");
            String fileContent = "Redis 是一个开源的内存数据库";
            ByteArrayInputStream stream = new ByteArrayInputStream(
                    fileContent.getBytes(StandardCharsets.UTF_8));

            DocumentParser.ParseResult parseResult = new DocumentParser.ParseResult(
                    "redis.pdf", fileContent, "application/pdf", 100);
            when(documentParser.parse(any(), eq("redis.pdf"))).thenReturn(parseResult);

            DocumentChunk chunk = DocumentChunk.builder()
                    .documentId(1L).sourceFile("redis.pdf").chunkIndex(0)
                    .content(fileContent).tokenCount(10).build();
            when(smartChunker.chunk(fileContent, 1L, "redis.pdf"))
                    .thenReturn(List.of(chunk));

            when(aiRagService.upsertChunk(anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(1L);

            consumer.processDocument(doc, stream);

            ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
            verify(documentMapper, atLeastOnce()).updateById(captor.capture());

            KnowledgeDocument finalDoc = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(finalDoc.getStatus()).isEqualTo("SUCCESS");
            assertThat(finalDoc.getTotalChunks()).isEqualTo(1);
            assertThat(finalDoc.getIndexedChunks()).isEqualTo(1);
        }

        @Test
        @DisplayName("processDocument → 解析失败 → 状态 FAILED")
        void shouldSetFailedOnError() throws Exception {
            KnowledgeDocument doc = buildDoc(1L, "bad.pdf", "PENDING");
            ByteArrayInputStream stream = new ByteArrayInputStream("bad".getBytes());

            when(documentParser.parse(any(), eq("bad.pdf")))
                    .thenThrow(new RuntimeException("PDF 解析失败"));

            consumer.processDocument(doc, stream);

            ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
            verify(documentMapper, atLeastOnce()).updateById(captor.capture());
            // 找出最后一次更新
            KnowledgeDocument finalDoc = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(finalDoc.getStatus()).isEqualTo("FAILED");
            assertThat(finalDoc.getErrorMessage()).contains("PDF 解析失败");
        }

        @Test
        @DisplayName("部分 chunk embedding 失败 → 仍标记 SUCCESS，indexed < total")
        void shouldTrackPartialFailures() throws Exception {
            KnowledgeDocument doc = buildDoc(1L, "redis.pdf", "PENDING");
            String content = "content";
            ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            DocumentParser.ParseResult parseResult = new DocumentParser.ParseResult(
                    "redis.pdf", content, "application/pdf", 50);
            when(documentParser.parse(any(), eq("redis.pdf"))).thenReturn(parseResult);

            DocumentChunk c1 = chunk(0), c2 = chunk(1), c3 = chunk(2);
            when(smartChunker.chunk(content, 1L, "redis.pdf"))
                    .thenReturn(List.of(c1, c2, c3));
            when(aiRagService.upsertChunk(anyLong(), anyString(), anyString(), anyString()))
                    .thenReturn(1L)
                    .thenThrow(new RuntimeException("embedding failed"))
                    .thenReturn(1L);

            consumer.processDocument(doc, stream);

            ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
            verify(documentMapper, atLeastOnce()).updateById(captor.capture());
            KnowledgeDocument finalDoc = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(finalDoc.getStatus()).isEqualTo("SUCCESS");
            assertThat(finalDoc.getTotalChunks()).isEqualTo(3);
            assertThat(finalDoc.getIndexedChunks()).isEqualTo(2);  // 1 failed
        }
    }

    // ──────────────────────────────────────────
    // KnowledgeDocument 实体
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("KnowledgeDocument 实体")
    class EntityTests {

        @Test
        @DisplayName("状态常量符合预期")
        void shouldHaveCorrectStatusValues() {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setStatus("PENDING");
            assertThat(doc.getStatus()).isEqualTo("PENDING");

            doc.setStatus("PROCESSING");
            assertThat(doc.getStatus()).isEqualTo("PROCESSING");

            doc.setStatus("SUCCESS");
            assertThat(doc.getStatus()).isEqualTo("SUCCESS");

            doc.setStatus("FAILED");
            assertThat(doc.getStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("字段完整性")
        void shouldHaveAllFields() {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(1L);
            doc.setUserId("u1");
            doc.setFileName("redis.pdf");
            doc.setFileSize(1024L);
            doc.setMimeType("application/pdf");
            doc.setStatus("PENDING");
            doc.setTotalChunks(12);
            doc.setIndexedChunks(0);
            doc.setErrorMessage(null);

            assertThat(doc.getId()).isEqualTo(1L);
            assertThat(doc.getUserId()).isEqualTo("u1");
            assertThat(doc.getFileName()).isEqualTo("redis.pdf");
            assertThat(doc.getFileSize()).isEqualTo(1024L);
            assertThat(doc.getMimeType()).isEqualTo("application/pdf");
            assertThat(doc.getTotalChunks()).isEqualTo(12);
            assertThat(doc.getIndexedChunks()).isEqualTo(0);
            assertThat(doc.getErrorMessage()).isNull();
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private static KnowledgeDocument buildDoc(Long id, String fileName, String status) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setFileName(fileName);
        doc.setStatus(status);
        doc.setTotalChunks(0);
        doc.setIndexedChunks(0);
        doc.setUserId("u1");
        return doc;
    }

    private static DocumentChunk chunk(int index) {
        return DocumentChunk.builder()
                .documentId(1L).sourceFile("redis.pdf").chunkIndex(index)
                .content("chunk-" + index).tokenCount(10).build();
    }
}
