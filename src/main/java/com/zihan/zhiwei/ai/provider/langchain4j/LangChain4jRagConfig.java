package com.zihan.zhiwei.ai.provider.langchain4j;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D11: 装配 RetrievalAugmentor，供 AiServices / Provider 使用。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LangChain4jRagConfig {

    @Bean
    @ConditionalOnBean(LangChain4jRagContentRetriever.class)
    public RetrievalAugmentor langChain4jRetrievalAugmentor(LangChain4jRagContentRetriever retriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(retriever)
                .build();
    }
}