package com.zihan.zhiwei.ai.provider.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * D23: Ollama 本地模型 Provider。
 * 通过 Ollama 的 OpenAI 兼容接口（/v1/chat/completions）实现同步 + SSE 流式调用。
 * 作为降级链最后一环，云端全部不可用时本地兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.ollama", name = "enabled", havingValue = "true")
public class OllamaProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "ollama";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${zhiwei.ai.ollama.base-url:http://localhost:11434/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.ollama.api-key:ollama}")
    private String apiKey;

    @Value("${zhiwei.ai.ollama.model:qwen2.5:7b}")
    private String defaultModel;

    @Value("${zhiwei.ai.ollama.timeout-seconds:120}")
    private long timeoutSeconds;

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    /**
     * 健康检查：请求 Ollama /api/tags 判断服务是否可达。
     */
    @Override
    public boolean isAvailable() {
        try {
            String tagsUrl = trimSlash(baseUrl).replace("/v1", "") + "/api/tags";
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

    // ==================== 同步 chat ====================

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        try {
            ObjectNode body = buildRequestBody(request, model, false);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new BusinessException("Ollama Provider 调用失败: HTTP " + httpResponse.statusCode()
                        + " body=" + safeBody(httpResponse.body()));
            }

            JsonNode root = objectMapper.readTree(httpResponse.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessException("Ollama Provider 返回内容为空");
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

            return new ProviderChatResponse(content, model, PROVIDER_NAME, promptTokens, completionTokens, totalTokens);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Ollama Provider 调用异常: " + ex.getMessage());
        }
    }

    // ==================== 流式 streamChat ====================

    @Override
    public StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        String model = request.model() != null ? request.model() : defaultModel;
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();

        try {
            ObjectNode body = buildRequestBody(request, model, true);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<InputStream> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new BusinessException("Ollama Stream 调用失败: HTTP " + httpResponse.statusCode()
                        + " body=" + safeBody(errorBody));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    JsonNode chunk;
                    try {
                        chunk = objectMapper.readTree(data);
                    } catch (Exception e) {
                        log.debug("[OllamaStream] skip unparseable chunk: {}", data);
                        continue;
                    }

                    JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        String content = delta.path("content").asText(null);
                        if (content != null && !content.isEmpty()) {
                            onToken.accept(content);
                        }
                    }

                    JsonNode usage = chunk.path("usage");
                    if (!usage.isMissingNode()) {
                        promptTokens.set(usage.path("prompt_tokens").asInt(0));
                        completionTokens.set(usage.path("completion_tokens").asInt(0));
                    }
                }
            }

            int pt = promptTokens.get();
            int ct = completionTokens.get();
            log.debug("[OllamaStream] done model={} promptTokens={} completionTokens={}", model, pt, ct);
            return StreamResult.of(model, PROVIDER_NAME, pt, ct);

        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Ollama Stream 调用异常: " + ex.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private ObjectNode buildRequestBody(ProviderChatRequest request, String model, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ProviderChatMessage item : request.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", item.role());
            msg.put("content", item.content());
        }
        return body;
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
