package com.zihan.zhiwei.ai.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D10: Claude 兼容 Embedding 客户端（DashScope text-embedding-v4 / 1536 维）
 *
 * P1-9 修复：继承 AbstractEmbeddingClient，删除 ~90% 重复代码。
 *
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.embedding", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CompatibleEmbeddingClient extends AbstractEmbeddingClient {

    @Value("${zhiwei.ai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.embedding.api-key:${spring.ai.dashscope.api-key:}}")
    private String apiKey;

    @Value("${zhiwei.ai.embedding.model:text-embedding-v4}")
    private String model;

    @Value("${zhiwei.ai.embedding.dimensions:1536}")
    private int dimensions;

    @Value("${zhiwei.ai.embedding.timeout-seconds:60}")
    private long timeoutSeconds;

    public CompatibleEmbeddingClient(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected String getBaseUrl() { return baseUrl; }

    @Override
    protected String getApiKey() { return apiKey; }

    @Override
    protected String getModel() { return model; }

    @Override
    protected int getDimensions() { return dimensions; }

    @Override
    protected long getTimeoutSeconds() { return timeoutSeconds; }

    @Override
    protected String getErrorPrefix() { return "Embedding"; }

    @Override
    protected boolean includeDimensionsInRequest() { return true; }
}
