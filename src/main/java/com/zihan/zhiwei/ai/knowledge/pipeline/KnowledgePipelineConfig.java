package com.zihan.zhiwei.ai.knowledge.pipeline;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgePipelineConfig {

    public static final String EXCHANGE = "knowledge.pipeline.exchange";
    public static final String QUEUE    = "knowledge.pipeline.queue";
    public static final String ROUTING  = "knowledge.pipeline.document";

    @Bean
    public DirectExchange knowledgeExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue knowledgeQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding knowledgeBinding(Queue knowledgeQueue, DirectExchange knowledgeExchange) {
        return BindingBuilder.bind(knowledgeQueue).to(knowledgeExchange).with(ROUTING);
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
