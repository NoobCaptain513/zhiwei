package com.zihan.zhiwei.common.exception;

import com.zihan.zhiwei.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一返回 {@link Result} 结构。
 * 注意：SSE 接口（produces = text/event-stream）响应头已设置，
 * 不能再用 @ResponseBody 返回 Result，必须直接写 SSE 格式的错误事件。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public Result<Void> handleIdempotencyConflict(IdempotencyConflictException ex,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) throws IOException {
        log.warn("幂等键冲突: uri={}, message={}", request.getRequestURI(), ex.getMessage());
        if (isSseRequest(response)) {
            writeSseError(response, ex.getMessage());
            return null;
        }
        response.setStatus(HttpStatus.CONFLICT.value());
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException ex, HttpServletRequest request,
                                                HttpServletResponse response) throws IOException {
        log.warn("业务异常: uri={}, code={}, message={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        if (isSseRequest(response)) {
            writeSseError(response, ex.getMessage());
            return null;
        }
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(Exception ex, HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
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
        if (isSseRequest(response)) {
            writeSseError(response, message);
            return null;
        }
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request,
                                                  HttpServletResponse response) throws IOException {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束失败: uri={}, message={}", request.getRequestURI(), message);
        if (isSseRequest(response)) {
            writeSseError(response, message);
            return null;
        }
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(Exception ex, HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
        log.warn("请求错误: uri={}, message={}", request.getRequestURI(), ex.getMessage());
        if (isSseRequest(response)) {
            writeSseError(response, ex.getMessage());
            return null;
        }
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException ex, HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        log.warn("资源不存在: uri={}", request.getRequestURI());
        if (isSseRequest(response)) {
            writeSseError(response, ErrorCode.NOT_FOUND.getMessage());
            return null;
        }
        return Result.fail(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex, HttpServletRequest request,
                                        HttpServletResponse response) throws IOException {
        log.error("系统异常: uri={}", request.getRequestURI(), ex);
        if (isSseRequest(response)) {
            writeSseError(response, ErrorCode.INTERNAL_ERROR.getMessage());
            return null;
        }
        return Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }

    // ==================== 工具方法 ====================

    /**
     * 判断当前响应是否已是 SSE 流（Content-Type 以 text/event-stream 开头）。
     * SSE 接口在 AiStreamAdvice 线程池中执行时抛出异常，此时响应头已由 Spring 设置好。
     */
    private boolean isSseRequest(HttpServletResponse response) {
        String ct = response.getContentType();
        return ct != null && ct.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /**
     * 向 SSE 流写入一条 error 事件，格式与 AiStreamAdvice.sendRaw 保持一致：
     *   data: {"error":"..."}\n\n
     */
    private void writeSseError(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) return;
        String safeMsg = message == null ? "系统错误" : message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String event = "data: {\"error\":\"" + safeMsg + "\"}\n\n";
        response.getWriter().write(event);
        response.getWriter().flush();
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}

