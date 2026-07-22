package com.zihan.zhiwei.common.exception;

import com.zihan.zhiwei.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一返回 {@link Result} 结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常: uri={}, code={}, message={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(Exception ex, HttpServletRequest request) {
        String message = "参数校验失败";
        if (ex instanceof MethodArgumentNotValidException validEx) {
            message = validEx.getBindingResult().getFieldErrors().stream()
                    .map(this::formatFieldError)
                    .collect(Collectors.joining("; "));
        } else if (ex instanceof BindException bindEx) {
            message = bindEx.getBindingResult().getFieldErrors().stream()
                    .map(this::formatFieldError)
                    .collect(Collectors.joining("; "));
        }
        log.warn("参数校验失败: uri={}, message={}", request.getRequestURI(), message);
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束失败: uri={}, message={}", request.getRequestURI(), message);
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("请求错误: uri={}, message={}", request.getRequestURI(), ex.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("资源不存在: uri={}", request.getRequestURI());
        return Result.fail(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex, HttpServletRequest request) {
        log.error("系统异常: uri={}", request.getRequestURI(), ex);
        return Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
