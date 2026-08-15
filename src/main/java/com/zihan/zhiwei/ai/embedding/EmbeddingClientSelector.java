package com.zihan.zhiwei.ai.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Embedding 客户端选择器。
 * 根据当前使用的 Provider 动态选择对应的 Embedding 客户端和向量列名。
 * 
 * 设计目标：
 * - Ollama 使用本地 nomic-embed-text (768维) → embedding_ollama 列
 * - 其他 Provider 使用 DashScope text-embedding-v4 (1536维) → embedding 列
 */
@Slf4j
@Component
public class EmbeddingClientSelector {

    private final CompatibleEmbeddingClient dashscopeClient;
    private final OllamaEmbeddingClient ollamaClient;

    public EmbeddingClientSelector(
            CompatibleEmbeddingClient dashscopeClient,
            ObjectProvider<OllamaEmbeddingClient> ollamaClientProvider) {
        this.dashscopeClient = dashscopeClient;
        // Ollama Embedding 是可选的，如果未启用则为 null
        this.ollamaClient = ollamaClientProvider.getIfAvailable();
    }

    /**
     * 根据 Provider 选择 Embedding 客户端
     * @param provider Provider 名称（如 "ollama", "native-dashscope"）
     * @return 对应的 Embedding 客户端
     */
    public EmbeddingClient select(String provider) {
        if ("ollama".equals(provider) && ollamaClient != null) {
            log.debug("[EmbeddingSelector] select ollama client (768d)");
            return ollamaClient;
        }
        log.debug("[EmbeddingSelector] select dashscope client (1536d)");
        return dashscopeClient;
    }

    /**
     * 根据 Provider 获取对应的向量列名
     * @param provider Provider 名称
     * @return 向量列名（"embedding" 或 "embedding_ollama"）
     */
    public String getVectorColumn(String provider) {
        if ("ollama".equals(provider) && ollamaClient != null) {
            return "embedding_ollama";
        }
        return "embedding";
    }

    /**
     * 根据 Provider 获取 Embedding 维度
     * @param provider Provider 名称
     * @return 向量维度（768 或 1536）
     */
    public int getDimensions(String provider) {
        if ("ollama".equals(provider) && ollamaClient != null) {
            return 768;
        }
        return 1536;
    }

    /**
     * 检查指定 Provider 的 Embedding 是否可用
     * @param provider Provider 名称
     * @return 是否可用
     */
    public boolean isAvailable(String provider) {
        if ("ollama".equals(provider)) {
            return ollamaClient != null;
        }
        return dashscopeClient != null;
    }
}
