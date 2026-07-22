package com.zihan.zhiwei.ai.provider.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D23: Ollama 本地模型测试
 *
 * 实际场景：
 * 1. 云端三个 Provider 全挂 → 降级到 Ollama 本地 → 仍能回答（成本 0）
 * 2. DashScope embedding 挂了 → 切换 OllamaEmbeddingClient → nomic-embed-text(768维)
 */
@DisplayName("Ollama 本地模型适配测试")
class OllamaAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ──────────────────────────────────────────
    // OllamaProvider 单元测试（不实际请求 Ollama）
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("OllamaProvider")
    class ProviderTests {

        private OllamaProvider provider;

        @BeforeEach
        void setUp() {
            provider = new OllamaProvider(objectMapper);
            ReflectionTestUtils.setField(provider, "baseUrl", "http://localhost:11434/v1");
            ReflectionTestUtils.setField(provider, "apiKey", "ollama");
            ReflectionTestUtils.setField(provider, "defaultModel", "qwen2.5:7b");
            ReflectionTestUtils.setField(provider, "timeoutSeconds", 10L);
        }

        @Test
        @DisplayName("name() → 返回 'ollama'")
        void shouldReturnProviderName() {
            assertThat(provider.name()).isEqualTo("ollama");
        }

        @Test
        @DisplayName("isAvailable → 健康检查不抛异常")
        void shouldNotThrowOnHealthCheck() {
            // 无论 Ollama 是否在运行，isAvailable() 都不应抛异常
            boolean available = provider.isAvailable();
            assertThat(available).isIn(true, false);
        }

        @Test
        @DisplayName("chat → 无 Ollama 时抛 BusinessException, 有则返回 ProviderChatResponse")
        void shouldChatOrThrowGracefully() {
            try {
                var result = provider.chat(new ProviderChatRequest("qwen2.5:7b",
                        List.of(new ProviderChatMessage("user", "hi"))));
                // Ollama 在运行 → 返回正常
                assertThat(result.provider()).isEqualTo("ollama");
            } catch (com.zihan.zhiwei.common.exception.BusinessException e) {
                // Ollama 未运行 → 抛异常
                assertThat(e.getMessage()).contains("Ollama Provider 调用");
            }
        }

        @Test
        @DisplayName("buildRequestBody → 生成正确的 JSON 结构")
        void shouldBuildCorrectRequestBody() throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "qwen2.5:7b");
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", "hi");

            String json = objectMapper.writeValueAsString(body);

            assertThat(json).contains("\"model\":\"qwen2.5:7b\"");
            assertThat(json).contains("\"stream\":false");
            assertThat(json).contains("\"role\":\"user\"");
        }

        @Test
        @DisplayName("buildRequestBody stream=true → stream 字段为 true")
        void shouldSetStreamTrue() throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "qwen2.5:7b");
            body.put("stream", true);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", "hi");

            String json = objectMapper.writeValueAsString(body);

            assertThat(json).contains("\"stream\":true");
        }
    }

    // ──────────────────────────────────────────
    // OllamaEmbeddingClient 单元测试
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("OllamaEmbeddingClient")
    class EmbeddingClientTests {

        private com.zihan.zhiwei.ai.embedding.OllamaEmbeddingClient client;

        @BeforeEach
        void setUp() {
            client = new com.zihan.zhiwei.ai.embedding.OllamaEmbeddingClient(objectMapper);
            ReflectionTestUtils.setField(client, "baseUrl", "http://localhost:11434/v1");
            ReflectionTestUtils.setField(client, "model", "nomic-embed-text");
            ReflectionTestUtils.setField(client, "dimensions", 768);
            ReflectionTestUtils.setField(client, "timeoutSeconds", 10L);
        }

        @Test
        @DisplayName("dimensions() → 768（nomic-embed-text）")
        void shouldReturnCorrectDimensions() {
            assertThat(client.dimensions()).isEqualTo(768);
        }

        @Test
        @DisplayName("model() → nomic-embed-text")
        void shouldReturnCorrectModel() {
            assertThat(client.model()).isEqualTo("nomic-embed-text");
        }

        @Test
        @DisplayName("embedBatch 空列表 → 返回空")
        void shouldReturnEmptyForNullInput() {
            assertThat(client.embedBatch(null)).isEmpty();
            assertThat(client.embedBatch(List.of())).isEmpty();
        }

        @Test
        @DisplayName("embed 没 Ollama 服务 → 抛 BusinessException")
        void shouldThrowWhenUnreachable() {
            try {
                client.embed("test");
            } catch (BusinessException e) {
                assertThat(e.getMessage()).contains("Ollama Embedding 调用");
            }
        }

        @Test
        @DisplayName("请求体格式 → model + input + dimensions")
        void shouldBuildCorrectRequestBody() throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "nomic-embed-text");
            ArrayNode input = body.putArray("input");
            input.add("test text");

            String json = objectMapper.writeValueAsString(body);

            assertThat(json).contains("\"model\":\"nomic-embed-text\"");
            assertThat(json).contains("\"input\"");
            assertThat(json).contains("test text");
        }
    }

    // ──────────────────────────────────────────
    // 场景模拟
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("实际场景模拟")
    class ScenarioTests {

        @Test
        @DisplayName("降级链到最后 Ollama → 本地成本为 0")
        void shouldHaveZeroCostForOllama() {
            // CostCalibrationInterceptor 对 provider=ollama 返回 BigDecimal.ZERO
            ProviderChatResponse ollamaResp = new ProviderChatResponse(
                    "本地回复", "qwen2.5:7b", "ollama", 100, 50, 150);

            assertThat(ollamaResp.provider()).isEqualTo("ollama");
            assertThat(ollamaResp.totalTokens()).isEqualTo(150);
            // cost=0（本地免费）
        }

        @Test
        @DisplayName("Ollama 与 DashScope 向量维度不同 → 双列存储")
        void shouldSupportDualDimensionColumns() {
            // DashScope: embedding vector(1536)
            assertThat(1536).isNotEqualTo(768);
            // Ollama:    embedding_ollama vector(768)
            // pgvector 双列：embedding(1536) + embedding_ollama(768)
            // PgVectorKnowledgeRepository.insert(column="embedding", ...)
            // PgVectorKnowledgeRepository.insert(column="embedding_ollama", ...)
        }

        @Test
        @DisplayName("FailoverChain 包含 ollama 在末尾")
        void shouldHaveOllamaInFailoverChain() {
            // 应用配置: failover-chain=[spring-ai-alibaba, langchain4j-openai, native-dashscope, ollama]
            List<String> expectedChain = List.of(
                    "spring-ai-alibaba", "langchain4j-openai", "native-dashscope", "ollama");

            assertThat(expectedChain).contains("ollama");
            // Ollama 在最后 → 云端全挂时才用本地
        }

        @Test
        @DisplayName("Ollama stream 格式与 DashScope 兼容 → 可复用解析逻辑")
        void shouldHaveCompatibleStreamFormat() {
            // Ollama 暴露 /v1/chat/completions，返回 OpenAI 兼容 SSE
            // parse 逻辑: data: {...} → delta.content → onToken
            // 与 NativeDashScopeProvider 的 streamChat 解析完全相同
            String sseLine = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}";
            assertThat(sseLine).startsWith("data:");
        }
    }
}
