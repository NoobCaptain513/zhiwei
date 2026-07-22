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
 * D23: Ollama 本地 Embedding 客户端（nomic-embed-text / 768 维）。
 * 通过 Ollama 的 OpenAI 兼容接口（/v1/embeddings）实现向量化。
 * 作为 DashScope embedding 的本地降级方案。
 */
@Slf4j
@Component("ollamaEmbeddingClient")
@ConditionalOnProperty(prefix = "zhiwei.ai.embedding.ollama", name = "enabled", havingValue = "true")
public class OllamaEmbeddingClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${zhiwei.ai.embedding.ollama.base-url:http://localhost:11434/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.embedding.ollama.model:nomic-embed-text}")
    private String model;

    @Value("${zhiwei.ai.embedding.ollama.dimensions:768}")
    private int dimensions;

    @Value("${zhiwei.ai.embedding.ollama.timeout-seconds:30}")
    private long timeoutSeconds;

    public OllamaEmbeddingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public float[] embed(String text) {
        List<float[]> list = embedBatch(List.of(text));
        if (list.isEmpty()) {
            throw new BusinessException("Ollama Embedding 返回为空");
        }
        return list.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode input = body.putArray("input");
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/embeddings"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer ollama")
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Ollama Embedding 调用失败: HTTP " + response.statusCode()
                        + " body=" + safeBody(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new BusinessException("Ollama Embedding 响应缺少 data");
            }

            List<JsonNode> nodes = new ArrayList<>();
            data.forEach(nodes::add);
            nodes.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));

            List<float[]> vectors = new ArrayList<>(nodes.size());
            for (JsonNode node : nodes) {
                JsonNode emb = node.path("embedding");
                if (!emb.isArray() || emb.isEmpty()) {
                    throw new BusinessException("Ollama Embedding 向量为空");
                }
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                if (vec.length != dimensions) {
                    log.warn("[OllamaEmbedding] dim mismatch expect={} actual={}", dimensions, vec.length);
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Ollama Embedding 调用异常: " + ex.getMessage());
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
