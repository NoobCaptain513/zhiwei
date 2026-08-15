package com.zihan.zhiwei.ai.embedding;

/**
 * Embedding 客户端统一接口。
 * 定义 Embedding 服务的标准方法。
 */
public interface EmbeddingClient {

    /**
     * 生成单个文本的 Embedding 向量
     * @param text 输入文本
     * @return Embedding 向量（浮点数数组）
     */
    float[] embed(String text);

    /**
     * 获取向量维度
     * @return 向量维度（如 768, 1536）
     */
    int dimensions();

    /**
     * 批量生成 Embedding 向量
     * @param texts 输入文本列表
     * @return Embedding 向量数组
     */
    default float[][] embedBatch(String[] texts) {
        float[][] results = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            results[i] = embed(texts[i]);
        }
        return results;
    }
}
