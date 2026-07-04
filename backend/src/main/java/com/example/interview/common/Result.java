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

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null, System.currentTimeMillis());
    }
}
