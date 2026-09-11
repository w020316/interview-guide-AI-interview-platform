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

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}