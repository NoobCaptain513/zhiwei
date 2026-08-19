package com.zihan.zhiwei.ai.stream;

import com.zihan.zhiwei.ai.provider.dto.ToolCall;

import java.util.List;

/**
 * D15: 流式调用完成后的元数据。
 * 完整文本由调用方自行拼接；这里只保存 Provider/Model/Token 统计。
 */
public record StreamResult(
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        List<ToolCall> toolCalls
) {
    public StreamResult(String model, String provider, int promptTokens,
                        int completionTokens, int totalTokens) {
        this(model, provider, promptTokens, completionTokens, totalTokens, List.of());
    }

    public static StreamResult of(String model, String provider,
                                  int promptTokens, int completionTokens) {
        return new StreamResult(model, provider,
                promptTokens, completionTokens, promptTokens + completionTokens, List.of());
    }
}
