package com.example.interview.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * - 统一异常响应格式
 * - 屏蔽内部错误细节，对外只返回友好提示
 * - 使用 SLF4J 记录完整堆栈
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return Result.error(400, "请求参数错误：" + ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.error(400, "参数校验失败：" + msg);
    }

    /**
     * 路径变量/查询参数类型不匹配（2026-09-20 阶段四线上实测发现）。
     *
     * <p>此类异常继承自 RuntimeException，此前落入 handleRuntime 返回 500——
     * 线上实测三例均 500：{@code /api/jobs/abc}（非数字 id）、
     * {@code /api/jobs?page=abc}（非数字分页）。属客户端输入错误，应为 400，
     * 否则既误导用户（显示「服务器内部错误」），又让用户手误/爬虫污染 5xx 告警。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        // 只回显参数名（服务端定义），不回显用户传入的原始值，避免反射型注入面
        return Result.error(400, "参数类型错误：" + ex.getName());
    }

    /**
     * 缺失必填请求参数（同上，2026-09-20 实测 {@code /api/knowledge/search} 无 query 时返回 500）。
     * 该异常继承 ServletException，此前落 handleGeneral 兜底 500。
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return Result.error(400, "缺少必填参数：" + ex.getParameterName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return Result.error(400, "文件大小超出限制（最大 10MB）");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return Result.error(405, "不支持的请求方法：" + ex.getMethod());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return Result.error(400, "请求体格式错误，请检查 JSON 语法");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoHandler(NoHandlerFoundException ex) {
        return Result.error(404, "接口不存在：" + ex.getRequestURL());
    }

    /**
     * Spring Boot 3.2+ 未匹配路径由静态资源处理器抛 {@code NoResourceFoundException}
     * （而非 NoHandlerFoundException），落到兜底 Exception 会返回 500——
     * 线上实测（2026-09-20，阶段四场景验证）任何不存在的 API 路径均 500，
     * 既给客户端错误语义，也污染 5xx 告警。归位为 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return Result.error(404, "接口不存在");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(AuthenticationException ex) {
        return Result.error(401, "未认证或登录已过期");
    }

    /**
     * 面向用户的业务异常：message 本身即设计给用户看的可重试文案，原样返回
     * （v1.31.4 B-11：与内部 IllegalStateException 区分，避免泄露内部细节）
     * v1.33.0（U1）：业务故障语义改为 503（服务暂不可用），替代 500"内部错误"——
     * 用户可据此理解"是 AI 服务暂时不可用"而非"平台坏了"。
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleBusiness(BusinessException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return Result.error(503, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException ex) {
        return Result.error(403, "无权访问该资源");
    }

    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Result<Void> handleTimeout(java.util.concurrent.TimeoutException ex) {
        log.warn("AI 服务超时：{}", ex.getMessage());
        return Result.error(504, "AI 服务响应超时，请稍后重试");
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleOptimisticLocking(org.springframework.dao.OptimisticLockingFailureException ex) {
        // P2-20：并发提交同一资源（如双端同时提交同一题答案）时乐观锁冲突，
        // 返回 409 让前端提示刷新重试，而非 last-write-wins 静默覆盖先提交的数据
        log.warn("乐观锁冲突（并发更新被拒绝）：{}", ex.getMessage());
        return Result.error(409, "内容已被更新，请刷新后重试");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        log.error("数据库操作失败", ex);
        return Result.error(500, "数据库操作失败，请联系管理员");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleIllegalState(IllegalStateException ex) {
        // v1.31.4 B-11：非 BusinessException 的非法状态视为内部错误，不透出 ex.getMessage()（防暴露路径/SQL等细节）
        log.error("非法状态异常", ex);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntime(RuntimeException ex) {
        log.error("未预期的运行时异常", ex);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleGeneral(Exception ex) {
        log.error("未捕获异常", ex);
        return Result.error(500, "服务器内部错误");
    }
}
