package com.zihan.zhiwei.ai.provider.nativehttp;

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
 * D6+D15: Native HTTP DashScope Provider。
 * D15: 实现真正的 SSE 流式输出（stream: true）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "zhiwei.ai.native", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NativeDashScopeProvider implements ModelProvider {

    public static final String PROVIDER_NAME = "native-dashscope";

    private final ObjectMapper objectMapper;
    private final CostCalibrationInterceptor costCalibrationInterceptor;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${zhiwei.ai.native.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.native.api-key:${spring.ai.dashscope.api-key:}}")
    private String apiKey;

    @Value("${zhiwei.ai.native.model:qwen-plus}")
    private String defaultModel;

    @Value("${zhiwei.ai.native.timeout-seconds:60}")
    private long timeoutSeconds;

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    // ==================== 同步 chat（保持不变）====================

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
                throw new BusinessException("Native Provider 调用失败: HTTP " + httpResponse.statusCode());
            }

            JsonNode root = objectMapper.readTree(httpResponse.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessException("Native Provider 返回内容为空");
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

            ProviderChatResponse response = new ProviderChatResponse(
                    content, model, PROVIDER_NAME, promptTokens, completionTokens, totalTokens);
            costCalibrationInterceptor.calibrate(response);
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Native Provider 调用异常: " + ex.getMessage());
        }
    }

    // ==================== D15: 流式 streamChat ====================

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
                throw new BusinessException("Native Stream 调用失败: HTTP " + httpResponse.statusCode()
                        + " body=" + errorBody);
            }

            // 逐行读取 SSE 流
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    // SSE 格式: data: {...} 或 data: [DONE]
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
                        log.debug("[NativeStream] skip unparseable chunk: {}", data);
                        continue;
                    }

                    // 提取 delta content
                    JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        String content = delta.path("content").asText(null);
                        if (content != null && !content.isEmpty()) {
                            onToken.accept(content);
                        }
                    }

                    // 最后一个 chunk 通常带 usage
                    JsonNode usage = chunk.path("usage");
                    if (!usage.isMissingNode()) {
                        promptTokens.set(usage.path("prompt_tokens").asInt(0));
                        completionTokens.set(usage.path("completion_tokens").asInt(0));
                    }
                }
            }

            int pt = promptTokens.get();
            int ct = completionTokens.get();
            log.debug("[NativeStream] done model={} promptTokens={} completionTokens={}", model, pt, ct);
            return StreamResult.of(model, PROVIDER_NAME, pt, ct);

        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Native Stream 调用异常: " + ex.getMessage());
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
}