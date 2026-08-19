package com.zihan.zhiwei.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 幂等键降级响应（HTTP 202 Accepted）。
 * <p>
 * 场景：并发重试时，第一个请求正在处理（tryAcquire 成功），
 * 后续重试 tryAcquire 失败 → 返回 202 + Location 头，让客户端轮询。
 */
@Schema(description = "幂等键处理中响应（202 Accepted）")
public record IdempotencyPendingResponse(

        @Schema(description = "提示信息", example = "请求正在处理中，请稍后重试")
        String message,

        @Schema(description = "轮询地址（相对路径）", example = "/api/ai/chat/status/user123:key-abc")
        String location,

        @Schema(description = "建议重试间隔（毫秒）", example = "2000")
        int retryAfterMs
) {
    public static IdempotencyPendingResponse of(String userId, String idempotencyKey, String endpoint) {
        return new IdempotencyPendingResponse(
                "请求正在处理中，请稍后轮询 Location 头获取结果",
                endpoint + "/status/" + userId + ":" + idempotencyKey,
                2000
        );
    }
}
