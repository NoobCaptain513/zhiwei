package com.zihan.zhiwei.ai.stream;

/**
 * D15: 流式调用完成后的元数据。
 * 完整文本由调用方自行拼接；这里只保存 Provider/Model/Token 统计。
 */
public record StreamResult(
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
    public static StreamResult of(String model, String provider,
                                  int promptTokens, int completionTokens) {
        return new StreamResult(model, provider,
                promptTokens, completionTokens, promptTokens + completionTokens);
    }
}