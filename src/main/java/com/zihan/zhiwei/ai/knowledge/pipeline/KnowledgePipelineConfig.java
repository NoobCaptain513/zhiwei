package com.zihan.zhiwei.ai.knowledge.pipeline;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FIX-10: 知识管道 MQ 拓扑升级——主队列 + DLX + TTL 重试队列 + 停车场队列。
 *
 * <pre>
 * producer → [pipeline.exchange] → [pipeline.queue] ─消费失败 reject──→ [dlx] → [retry.queue]
 *                ▲                                                              (TTL 30s,无消费者)
 *                │                                                                   │ TTL 到期
 *                └────────────────── dead-letter 回主 exchange ←────────────────────┘
 *
 *  重试耗尽 / 确定性失败 ─→ [parking.queue]（停车场,人工介入）
 * </pre>
 */
@Configuration
public class KnowledgePipelineConfig {

    public static final String EXCHANGE = "knowledge.pipeline.exchange";
    public static final String QUEUE    = "knowledge.pipeline.queue";
    public static final String ROUTING  = "knowledge.pipeline.document";

    public static final String DLX_EXCHANGE   = "knowledge.pipeline.dlx";
    public static final String RETRY_QUEUE    = "knowledge.pipeline.retry.queue";
    public static final String RETRY_ROUTING  = "knowledge.pipeline.retry";
    public static final String PARKING_QUEUE  = "knowledge.pipeline.parking.queue";
    public static final String PARKING_ROUTING = "knowledge.pipeline.parking";

    @Value("${zhiwei.ai.knowledge.mq.retry-ttl-ms:30000}")
    private long retryTtlMs;

    @Bean
    public DirectExchange knowledgeExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public DirectExchange knowledgeDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    /** 主队列：消费失败被 reject 后，死信路由到 DLX → 重试队列 */
    @Bean
    public Queue knowledgeQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RETRY_ROUTING)
                .build();
    }

    /** 重试队列：无消费者，消息躺满 TTL 后死信回主 exchange */
    @Bean
    public Queue knowledgeRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", retryTtlMs)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING)
                .build();
    }

    /** 停车场（最终 DLQ）：毒消息终点，保留现场，人工介入 */
    @Bean
    public Queue knowledgeParkingQueue() {
        return QueueBuilder.durable(PARKING_QUEUE).build();
    }

    @Bean
    public Binding knowledgeBinding(Queue knowledgeQueue, DirectExchange knowledgeExchange) {
        return BindingBuilder.bind(knowledgeQueue).to(knowledgeExchange).with(ROUTING);
    }

    @Bean
    public Binding knowledgeRetryBinding(Queue knowledgeRetryQueue, DirectExchange knowledgeDlxExchange) {
        return BindingBuilder.bind(knowledgeRetryQueue).to(knowledgeDlxExchange).with(RETRY_ROUTING);
    }

    @Bean
    public Binding knowledgeParkingBinding(Queue knowledgeParkingQueue, DirectExchange knowledgeDlxExchange) {
        return BindingBuilder.bind(knowledgeParkingQueue).to(knowledgeDlxExchange).with(PARKING_ROUTING);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }
}
