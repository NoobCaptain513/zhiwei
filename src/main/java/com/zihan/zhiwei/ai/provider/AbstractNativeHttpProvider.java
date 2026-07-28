package com.zihan.zhiwei.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.probe.ProbeResult;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

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
 * P1-8 修复：Native HTTP Provider 抽象基类。
 * 将 OllamaProvider 和 NativeDashScopeProvider 中 ~80% 的重复代码提取到此基类。
 * 子类只需提供配置值（baseUrl / apiKey / model / timeout）和名称。
 */
@Slf4j
public abstract class AbstractNativeHttpProvider implements ModelProvider {

    // P3-22 修复：魔法字符串提取为常量
    public static final String SSE_DATA_PREFIX = "data:";
    public static final String SSE_DONE = "[DONE]";
    public static final String PROBE_TEXT = "ping";
    public static final String ROLE_USER = "user";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_ASSISTANT = "assistant";

    protected final ObjectMapper objectMapper;

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected AbstractNativeHttpProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 子类提供 ====================

    protected abstract String getBaseUrl();
    protected abstract String getApiKey();
    protected abstract String getDefaultModel();
    protected abstract long getTimeoutSeconds();

    // ==================== 公共工具方法（P3-20 修复：委托给 ProviderUtils） ====================

    /**
     * 构建 OpenAI 兼容的请求体。
     */
    protected ObjectNode buildRequestBody(ProviderChatRequest request, String model, boolean stream) {
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

    protected static String trimSlash(String url) {
        return ProviderUtils.trimSlash(url);
    }

    protected static String safeBody(String body) {
        return ProviderUtils.safeBody(body);
    }

    // ==================== probe ====================

    @Override
    public ProbeResult probe() {
        long start = System.currentTimeMillis();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", getDefaultModel());
            body.put("max_tokens", 1);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", ROLE_USER);
            msg.put("content", PROBE_TEXT);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(10, getTimeoutSeconds() / 3)))
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                return ProbeResult.ok(name(), System.currentTimeMillis() - start);
            }
            return ProbeResult.fail(name(), System.currentTimeMillis() - start,
                    "HTTP " + httpResponse.statusCode());
        } catch (Exception e) {
            return ProbeResult.fail(name(), System.currentTimeMillis() - start, e.getMessage());
        }
    }

    // ==================== 钩子方法（子类可选覆写） ====================

    /** 同步响应后回调（可用于成本校准等额外处理） */
    protected void afterSyncResponse(ProviderChatResponse response) {
        // 默认空实现，子类可按需覆写
    }

    /** 是否在同步错误中包含响应body（Ollama 需要，DashScope 不需要） */
    protected boolean includeBodyInSyncError() {
        return true;
    }

    // ==================== 同步 chat（模板方法） ====================

    @Override
    public ProviderChatResponse chat(ProviderChatRequest request) {
        String model = request.model() != null ? request.model() : getDefaultModel();
        try {
            ObjectNode body = buildRequestBody(request, model, false);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                String errorDetail = includeBodyInSyncError()
                        ? " body=" + safeBody(httpResponse.body())
                        : "";
                throw new BusinessException(name() + " Provider 调用失败: HTTP "
                        + httpResponse.statusCode() + errorDetail);
            }

            JsonNode root = objectMapper.readTree(httpResponse.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessException(name() + " Provider 返回内容为空");
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

            ProviderChatResponse response = new ProviderChatResponse(
                    content, model, name(), promptTokens, completionTokens, totalTokens);

            afterSyncResponse(response); // 钩子：如成本校准

            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(name() + " Provider 调用异常: " + ex.getMessage());
        }
    }

    // ==================== 流式 streamChat（模板方法） ====================

    @Override
    public StreamResult streamChat(ProviderChatRequest request, Consumer<String> onToken) {
        String model = request.model() != null ? request.model() : getDefaultModel();
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();

        try {
            ObjectNode body = buildRequestBody(request, model, true);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<InputStream> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                String errorBody = new String(httpResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new BusinessException(name() + " Stream 调用失败: HTTP "
                        + httpResponse.statusCode() + " body=" + safeBody(errorBody));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!line.startsWith(SSE_DATA_PREFIX)) {
                        continue;
                    }
                    String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                    if (SSE_DONE.equals(data)) {
                        break;
                    }

                    JsonNode chunk;
                    try {
                        chunk = objectMapper.readTree(data);
                    } catch (Exception e) {
                        log.debug("[{}Stream] skip unparseable chunk: {}", name(), data);
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
            log.debug("[{}Stream] done model={} promptTokens={} completionTokens={}", name(), model, pt, ct);
            return StreamResult.of(model, name(), pt, ct);

        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(name() + " Stream 调用异常: " + ex.getMessage());
        }
    }
}
