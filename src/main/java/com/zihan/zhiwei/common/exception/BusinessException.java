package com.zihan.zhiwei.common.exception;

import com.zihan.zhiwei.common.Result;
import lombok.Getter;

/**
 * 业务异常，携带错误码与提示信息。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(String message) {
        this(Result.FAIL_CODE, message);
    }
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage());
    }
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message);
    }
    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}