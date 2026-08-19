package com.zihan.zhiwei.common.exception;

/**
 * 幂等键冲突异常：相同 idempotencyKey 但请求内容不同。
 */
public class IdempotencyConflictException extends BusinessException {
    public IdempotencyConflictException(String message) {
        super(409, message);
    }
}
