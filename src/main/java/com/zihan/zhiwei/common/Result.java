package com.zihan.zhiwei.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 API 响应结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int SUCCESS_CODE = 0;
    public static final int FAIL_CODE = -1;
    public static final String SUCCESS_MESSAGE = "success";

    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(String message) {
        return fail(FAIL_CODE, message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}