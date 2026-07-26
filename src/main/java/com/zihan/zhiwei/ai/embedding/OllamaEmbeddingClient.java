package com.zihan.zhiwei.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D23: Ollama 本地 Embedding 客户端（nomic-embed-text / 768 维）。
 * 通过 Ollama 的 OpenAI 兼容接口（/v1/embeddings）实现向量化。
 * 作为 DashScope embedding 的本地降级方案。
 *
 * P1-9 修复：继承 AbstractEmbeddingClient，删除 ~90% 重复代码。
 */
@Slf4j
@Component("ollamaEmbeddingClient")
@ConditionalOnProperty(prefix = "zhiwei.ai.embedding.ollama", name = "enabled", havingValue = "true")
public class OllamaEmbeddingClient extends AbstractEmbeddingClient {

    @Value("${zhiwei.ai.embedding.ollama.base-url:http://localhost:11434/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.embedding.ollama.model:nomic-embed-text}")
    private String model;

    @Value("${zhiwei.ai.embedding.ollama.dimensions:768}")
    private int dimensions;

    @Value("${zhiwei.ai.embedding.ollama.timeout-seconds:30}")
    private long timeoutSeconds;

    public OllamaEmbeddingClient(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected String getBaseUrl() { return baseUrl; }

    @Override
    protected String getApiKey() { return "ollama"; }

    @Override
    protected String getModel() { return model; }

    @Override
    protected int getDimensions() { return dimensions; }

    @Override
    protected long getTimeoutSeconds() { return timeoutSeconds; }

    @Override
    protected String getErrorPrefix() { return "Ollama Embedding"; }

    @Override
    protected boolean includeDimensionsInRequest() { return false; }
}
