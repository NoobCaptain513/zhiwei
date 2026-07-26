package com.zihan.zhiwei.ai.knowledge.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgePipelineProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 修复 P0-2：发送文档处理消息时携带文件字节，
     * 使 Consumer 可以真正执行解析+分块+入库。
     */
    public void sendDocumentMessage(Long documentId, String userId, String fileName, byte[] fileContent) {
        KnowledgePipelineMessage message = new KnowledgePipelineMessage(documentId, userId, fileName, fileContent);
        rabbitTemplate.convertAndSend(
                KnowledgePipelineConfig.EXCHANGE,
                KnowledgePipelineConfig.ROUTING,
                message);
        log.info("[Pipeline Producer] sent documentId={} fileName={} size={}bytes",
                documentId, fileName, fileContent == null ? 0 : fileContent.length);
    }
}