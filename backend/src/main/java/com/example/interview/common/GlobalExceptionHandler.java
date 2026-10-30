package com.example.interview.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * 资源不存在 → 404（v1.44.0，第三轮 P3-01）。
     *
     * <p>此前「按 ID 查不到」也走下面的 {@link IllegalArgumentException} 分支被映射成 400，
     * 与 {@code docs/api-error-contract.md}（「404 资源不存在」）以及
     * {@code GET /api/jobs/{id}}（正确返回 404）都不一致。
     *
     * <p>Spring 按**最具体**的异常类型匹配处理器，所以本方法优先于下面的 400 分支生效；
     * 其余 {@code IllegalArgumentException} 语义不变。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleResourceNotFound(ResourceNotFoundException ex) {
        return Result.error(404, ex.getMessage());
    }

    /**
     * 触发限流 → <b>429</b>（2026-10-21，429 口径收口）。
     *
     * <h2>本处理器存在的唯一目的：让「429 只有一个出口」</h2>
     *
     * <p>此前限流分散在 10 处手写返回值（{@code return Result.error(429, ...)} → HTTP 200，
     * 以及 2 处手写 {@code response.setStatus(429)}）。同一个语义两种载体，新增限流点时
     * 必然漏改其中一处——这正是「清单跟不上演进」的典型形态。
     *
     * <p>现在业务代码只 {@code throw new RateLimitExceededException(msg)}，
     * HTTP 状态码与 body 结构<b>全部由这一处决定</b>。要调整 429 的形态，
     * 只改这个方法即可，不存在「散落各处必须同步」的问题。
     *
     * <p>形态说明：状态码 429 是主判据（网关/监控/客户端重试中间件据此分流，
     * 见 {@code docs/api-error-contract.md} §4）；body 仍保持 {@link Result} 结构并带
     * {@code code=429}，兼容按 body code 判读的调用方。二者同源，不会出现「状态码与
     * body 不一致」的情形。
     *
     * <p>注：{@code Retry-After} 不在此处统一设置——窗口长度因限流器而异
     * （注册 1/小时、登录锁定 5 分钟、AI 60/分钟、Agent 20/分钟），
     * 写死一个值只会给出错误倒计时。带窗口语义的两处（{@code AiRateLimitFilter}、
     * {@code JobAgentController#refresh}）各自在响应里设置该头。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result<Void> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("触发限流（429）：{}", ex.getMessage());
        return Result.error(429, ex.getMessage());
    }

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

    /**
     * 缺少 multipart 文件部件（v1.34.1，P2-1 错误语义修正）
     *
     * <p>真机实测（2026-09-20）触发面：{@code POST /api/interview/upload-image} 与
     * {@code POST /api/resume/upload} 在「multipart 请求未带 file 部件」时返回
     * 500「服务器内部错误」——用户无法判断是自己传错了文件。
     * 本异常继承 ServletException（与上方 MissingServletRequestParameterException 同类），
     * 此前无处理器而落入 RuntimeException 兜底 500，现归位 400 并指明缺失的部件名。
     */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException ex) {
        log.warn("缺少 multipart 请求部件：{}", ex.getRequestPartName());
        return Result.error(400, "请上传文件（缺少 " + ex.getRequestPartName() + " 部件）");
    }

    /**
     * multipart 请求体解析失败（v1.34.1，P2-1 错误语义修正）
     *
     * <p>真机实测触发面：请求声明 multipart 但无 boundary，或直接以 JSON 调用 multipart 接口。
     * 此前返回 500，现归位 400 并给出可操作的提示。
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMultipart(org.springframework.web.multipart.MultipartException ex) {
        log.warn("multipart 请求解析失败：{}", ex.getMessage());
        return Result.error(400, "请求格式不正确，请使用 multipart/form-data 上传文件");
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
     *
     * <p>2026-10-30（P2-09）：状态码不再写死 503，改由 {@link BusinessException#getStatus()}
     * 决定——「故事库已满」是资源冲突（409）、「标题不能为空」是参数错误（400），
     * 报成 503 会把用户自己的操作问题归因成平台故障。未显式声明状态码的抛出方
     * （AI 全链失败等）仍保持 503，历史契约不变。
     *
     * <p>返回值改用 {@link ResponseEntity} 承载动态状态码；body 结构与
     * {@code code} 字段同步取同一状态码，避免「状态码与 body 不一致」。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        if (status == null || !status.isError()) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        log.warn("业务异常（{}）：{}", status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(Result.error(status.value(), ex.getMessage()));
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

    /**
     * 业务冲突 → 409（第三批 C：导入覆盖模式的指纹冲突保护）。
     *
     * <p>与乐观锁冲突（409「内容已被更新」）同族语义：数据在客户端导出之后已被改动，
     * 覆盖前需用户显式确认（{@code force:true}）。文案由抛出方给出（面向用户、可操作），
     * 原样透出。
     */
    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(ConflictException ex) {
        log.warn("业务冲突（409）：{}", ex.getMessage());
        return Result.error(409, ex.getMessage());
    }

    /**
     * AI 并发闸门排队超时（v1.34.1，P3-4 异常语义统一）
     *
     * <p>本质是「AI 服务暂不可用」的可重试业务故障，语义应与降级链全失败（503）一致；
     * 此前落入下方 {@link IllegalStateException} 分支返回 500「服务器内部错误」，
     * 用户无法区分是自己操作有误还是服务繁忙。
     *
     * <p>必须声明在 {@code handleIllegalState} 之前由 Spring 按最具体类型匹配——
     * 本类型继承 IllegalStateException，若无本处理器则会命中后者。
     * 文案为闸门自述（不含路径/SQL 等内部细节），可安全透出。
     */
    @ExceptionHandler(com.example.interview.ai.AiGateTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleAiGateTimeout(com.example.interview.ai.AiGateTimeoutException ex) {
        log.warn("AI 并发闸门排队超时：{}", ex.getMessage());
        return Result.error(503, ex.getMessage());
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
