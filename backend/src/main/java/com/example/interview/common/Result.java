package com.example.interview.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一响应结构
 */
public record Result<T>(
        int code,
        String message,
        T data,
        @JsonProperty("timestamp") long timestamp
) {
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "success", null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    /**
     * 带结构化附加信息的错误响应。
     *
     * <p>用于「错误原因需要机器可读」的场景 —— 例如岗位链接抓取失败时，
     * 前端要据 {@code reason} 区分「需要登录 / 动态渲染 / 反爬拦截」，
     * 从而给出不同的下一步动作提示，而不是让用户对着一句笼统报错反复试。
     * 常规业务错误请继续用 {@link #error(int, String)}。
     */
    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null, System.currentTimeMillis());
    }
}
