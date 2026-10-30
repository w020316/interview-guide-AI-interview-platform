package com.example.interview.common;

/**
 * 面向用户的业务异常（v1.31.4 B-11）
 *
 * <p>用于承载"本身是用户可控原因/可稳定展示给用户"的提示文案（如 AI 空响应、可重试的临时故障），
 * 由 {@link GlobalExceptionHandler} 将 {@code message} 原样返回前端。
 *
 * <p>{@code extends IllegalStateException} 以兼容既有服务层单测的 {@code isInstanceOf(IllegalStateException.class)}
 * 断言，同时允许全局异常处理器按更具体的类型分派，避免非业务 {@code IllegalStateException} 的
 * 内部细节（路径/SQL/类名）被透出。
 */
public class BusinessException extends IllegalStateException {

    /** 默认 HTTP 状态码：503「服务暂不可用」（AI 全链失败、并发闸门超时等可重试故障） */
    public static final int DEFAULT_STATUS = 503;

    /**
     * 该异常应映射到的 HTTP 状态码（P2-09，2026-10-30）。
     *
     * <p>此前所有 {@code BusinessException} 一律映射 503，导致「故事库已满」（用户正常操作
     * 触发的资源冲突）、「标题不能为空」（参数错误）也被报成「服务暂不可用」，把用户错误
     * 归因成平台故障。现在由抛出方显式声明语义：资源冲突 409、参数错误 400，
     * 未声明时保持历史契约 503。
     */
    private final int status;

    public BusinessException(String message) {
        this(DEFAULT_STATUS, message);
    }

    public BusinessException(String message, Throwable cause) {
        this(DEFAULT_STATUS, message, cause);
    }

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public BusinessException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}