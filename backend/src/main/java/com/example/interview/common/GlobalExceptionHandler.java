package com.example.interview.common;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * - 统一异常响应格式
 * - 屏蔽内部错误细节，对外只返回友好提示
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败 → 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return Result.error(400, "请求参数错误：" + ex.getMessage());
    }

    /**
     * Bean Validation 校验失败 → 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.error(400, "参数校验失败：" + msg);
    }

    /**
     * 文件超出大小限制 → 400
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return Result.error(400, "文件大小超出限制（最大 10MB）");
    }

    /**
     * AI 调用超时（通过 RuntimeException 包装）→ 504
     */
    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Result<Void> handleTimeout(java.util.concurrent.TimeoutException ex) {
        return Result.error(504, "AI 服务响应超时，请稍后重试");
    }

    /**
     * 数据库操作异常 → 500
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        return Result.error(500, "数据库操作失败，请联系管理员");
    }

    /**
     * 通用运行时异常 → 500
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntime(RuntimeException ex) {
        // 生产环境不暴露内部堆栈，仅记录日志
        System.err.println("[GlobalExceptionHandler] RuntimeException: " + ex.getMessage());
        return Result.error(500, "服务器内部错误，请稍后重试");
    }

    /**
     * 兜底异常 → 500
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleGeneral(Exception ex) {
        System.err.println("[GlobalExceptionHandler] Exception: " + ex.getMessage());
        return Result.error(500, "服务器内部错误");
    }
}
