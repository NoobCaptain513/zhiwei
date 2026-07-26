package com.zihan.zhiwei.ai.provider.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.AbstractNativeHttpProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * D23: Ollama 本地模型 Provider。
 * 通过 Ollama 的 OpenAI 兼容接口（/v1/chat/completions）实现同步 + SSE 流式调用。
 * 作为降级链最后一环，云端全部不可用时本地兜底。
 *
 * P1-8 修复：继承 AbstractNativeHttpProvider，删除 ~150 行重复代码。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.ollama", name = "enabled", havingValue = "true")
public class OllamaProvider extends AbstractNativeHttpProvider {

    public static final String PROVIDER_NAME = "ollama";

    @Value("${zhiwei.ai.ollama.base-url:http://localhost:11434/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.ollama.api-key:ollama}")
    private String apiKey;

    @Value("${zhiwei.ai.ollama.model:qwen2.5:7b}")
    private String defaultModel;

    @Value("${zhiwei.ai.ollama.timeout-seconds:120}")
    private long timeoutSeconds;

    public OllamaProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    /**
     * 健康检查：请求 Ollama /api/tags 判断服务是否可达。
     * P1-8 注记：replace 改为 replaceFirst 防止多次替换。
     */
    @Override
    public boolean isAvailable() {
        try {
            String tagsUrl = trimSlash(baseUrl).replaceFirst("/v1", "") + "/api/tags";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tagsUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ex) {
            log.debug("[Ollama] health check failed: {}", ex.getMessage());
            return false;
        }
    }

    // ==================== 子类提供配置值 ====================

    @Override
    protected String getBaseUrl() { return baseUrl; }

    @Override
    protected String getApiKey() { return apiKey; }

    @Override
    protected String getDefaultModel() { return defaultModel; }

    @Override
    protected long getTimeoutSeconds() { return timeoutSeconds; }

    // Ollama 默认基类行为已符合需求，无需额外覆写
}
