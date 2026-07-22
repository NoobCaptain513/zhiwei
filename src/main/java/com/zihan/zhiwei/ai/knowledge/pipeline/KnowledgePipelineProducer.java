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

    public void sendDocumentMessage(Long documentId, String userId, String fileName) {
        KnowledgePipelineMessage message = new KnowledgePipelineMessage(documentId, userId, fileName);
        rabbitTemplate.convertAndSend(
                KnowledgePipelineConfig.EXCHANGE,
                KnowledgePipelineConfig.ROUTING,
                message);
        log.info("[Pipeline Producer] sent documentId={} fileName={}", documentId, fileName);
    }
}