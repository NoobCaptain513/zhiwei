package com.zihan.zhiwei.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
/**
 * 统一业务错误码。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无访问权限"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    INTERNAL_ERROR(500, "系统内部错误");
    private final int code;
    private final String message;
}
