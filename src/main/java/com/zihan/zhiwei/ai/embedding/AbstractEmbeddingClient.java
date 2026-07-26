package com.zihan.zhiwei.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.ai.provider.ProviderUtils;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * P1-9 修复：Embedding 客户端抽象基类。
 * 将 CompatibleEmbeddingClient 和 OllamaEmbeddingClient 中 ~90% 的重复代码提取到此基类。
 * 子类只需提供配置值和错误消息前缀。
 */
@Slf4j
public abstract class AbstractEmbeddingClient {

    protected final ObjectMapper objectMapper;

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected AbstractEmbeddingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 子类提供 ====================

    protected abstract String getBaseUrl();
    protected abstract String getApiKey();
    protected abstract String getModel();
    protected abstract int getDimensions();
    protected abstract long getTimeoutSeconds();

    /** 错误消息前缀，如 "Embedding" 或 "Ollama Embedding" */
    protected abstract String getErrorPrefix();

    /** 是否在请求体中包含 dimensions 字段（DashScope 需要，Ollama 的 nomic-embed-text 不支持） */
    protected abstract boolean includeDimensionsInRequest();

    // ==================== 公共工具方法（P3-20 修复：委托给 ProviderUtils） ====================

    protected static String trimSlash(String url) {
        return ProviderUtils.trimSlash(url);
    }

    protected static String safeBody(String body) {
        return ProviderUtils.safeBody(body);
    }

    // ==================== 公共实现 ====================

    public float[] embed(String text) {
        List<float[]> list = embedBatch(List.of(text));
        if (list.isEmpty()) {
            throw new BusinessException(getErrorPrefix() + " 返回为空");
        }
        return list.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", getModel());
            if (includeDimensionsInRequest()) {
                body.put("dimensions", getDimensions());
            }
            ArrayNode input = body.putArray("input");
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(getBaseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(getErrorPrefix() + " 调用失败: HTTP " + response.statusCode()
                        + " body=" + safeBody(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new BusinessException(getErrorPrefix() + " 响应缺少 data");
            }

            List<JsonNode> nodes = new ArrayList<>();
            data.forEach(nodes::add);
            nodes.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));

            List<float[]> vectors = new ArrayList<>(nodes.size());
            for (JsonNode node : nodes) {
                JsonNode emb = node.path("embedding");
                if (!emb.isArray() || emb.isEmpty()) {
                    throw new BusinessException(getErrorPrefix() + " 向量为空");
                }
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                if (vec.length != getDimensions()) {
                    log.warn("[{}] dim mismatch expect={} actual={}", getErrorPrefix(), getDimensions(), vec.length);
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(getErrorPrefix() + " 调用异常: " + ex.getMessage());
        }
    }

    public int dimensions() {
        return getDimensions();
    }

    public String model() {
        return getModel();
    }
}
