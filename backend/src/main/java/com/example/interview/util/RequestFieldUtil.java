package com.example.interview.util;

import java.util.Map;

/**
 * 请求体字段的安全取值（第六轮 P1-03，v1.47.0）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>多个控制器此前对 JSON 请求体做无条件强转，例如
 * {@code String jobDesc = (String) req.get("jobDescription");}。
 * 当客户端传入**数字 / 布尔 / 数组 / 对象**时，该强转抛 {@code ClassCastException}
 * （它是 {@code RuntimeException}，落入 {@code GlobalExceptionHandler.handleRuntime}）
 * → 返回 <b>HTTP 500「服务器内部错误」</b>。
 *
 * <p>后果有两层：① 用户把自己的输入错误看成了「平台坏了」；
 * ② 客户端手误/爬虫会持续污染 5xx 告警，掩盖真实的服务端故障。
 * 同项目已把 {@code MethodArgumentTypeMismatchException} 映射为 400，本类与之语义对齐。
 *
 * <h2>与 {@code nullableText} 的区别</h2>
 *
 * <p>{@code nullableText}（各控制器已有）用 {@code String.valueOf} 把任意类型都转成字符串——
 * 传 {@code 12345} 会静默变成 {@code "12345"} 并返回 200，无法满足「类型错误 → 400」的契约。
 * 因此这里必须区分「缺失/null」与「类型错误」两种情形，由调用方决定如何响应。
 */
public final class RequestFieldUtil {

    private RequestFieldUtil() {}

    /**
     * 字段取值结果。
     *
     * @param value     文本值；缺失/null 时为 {@code null}
     * @param typeError 是否传了非字符串类型（数字/布尔/数组/对象）
     */
    public record TextField(String value, boolean typeError) {
        public boolean hasTypeError() {
            return typeError;
        }
    }

    /**
     * 读取「期望为字符串」的字段，区分「缺失/null」与「类型错误」。
     *
     * <p>用法：先判 {@link TextField#hasTypeError()} 返回 400，再取
     * {@link TextField#value()} 做空值校验。
     */
    public static TextField text(Map<String, ?> src, String key) {
        Object v = src.get(key);
        if (v == null) {
            return new TextField(null, false);
        }
        if (v instanceof String s) {
            return new TextField(s, false);
        }
        return new TextField(null, true);
    }

    /** 类型错误时面向用户的提示文案（指明字段与期望类型） */
    public static String typeError(String key) {
        return key + " 类型错误，期望字符串";
    }
}
