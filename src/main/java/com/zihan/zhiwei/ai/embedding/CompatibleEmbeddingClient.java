package com.zihan.zhiwei.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * D10: Claude 兼容 Embedding 客户端（DashScope text-embedding-v4 / 1536 维）
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.embedding", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CompatibleEmbeddingClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
        this.objectMapper = objectMapper;
    }

    public float[] embed(String text) {
        List<float[]> list = embedBatch(List.of(text));
        if (list.isEmpty()) {
            throw new BusinessException("Embedding 返回为空");
        }
        return list.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Embedding api-key 未配置");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("dimensions", dimensions);
            ArrayNode input = body.putArray("input");
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/embeddings"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Embedding 调用失败: HTTP " + response.statusCode()
                        + " body=" + safeBody(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new BusinessException("Embedding 响应缺少 data");
            }

            List<JsonNode> nodes = new ArrayList<>();
            data.forEach(nodes::add);
            nodes.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));

            List<float[]> vectors = new ArrayList<>(nodes.size());
            for (JsonNode node : nodes) {
                JsonNode emb = node.path("embedding");
                if (!emb.isArray() || emb.isEmpty()) {
                    throw new BusinessException("Embedding 向量为空");
                }
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                if (vec.length != dimensions) {
                    log.warn("[Embedding] dim mismatch expect={} actual={}", dimensions, vec.length);
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Embedding 调用异常: " + ex.getMessage());
        }
    }

    public int dimensions() {
        return dimensions;
    }

    public String model() {
        return model;
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