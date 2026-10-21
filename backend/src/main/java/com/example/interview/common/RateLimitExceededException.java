package com.example.interview.common;

/**
 * 触发限流（HTTP <b>429 Too Many Requests</b>）。
 *
 * <h2>为什么需要单独一个类型</h2>
 *
 * <p>项目里原有 8 处限流分支写成 {@code return Result.error(429, "...")}，
 * 这种写法只改 body 的 {@code code}，HTTP 状态码仍是 200。后果：
 *
 * <ul>
 *   <li>按状态码分流的网关、监控、重试中间件都看不见「限流发生过」；</li>
 *   <li>前端响应拦截器在 2xx 分支把 {@code body.code} 丢掉，只 reject 一个裸
 *       {@code Error(message)}，调用方无法分辨「限流」与「普通业务失败」，
 *       {@code docs/api-error-contract.md} §5 要求的「限流提示 + 禁止自动重试 + 倒计时」
 *       无从触发。</li>
 * </ul>
 *
 * <p>而逐处手写 {@code response.setStatus(429)} 是同一口径散落 N 处——新增一个限流点时
 * 必然漏掉，这正是本项目反复出现的「清单跟不上演进」缺陷模式。
 * 因此改为<b>抛出本异常</b>：HTTP 状态码由
 * {@link GlobalExceptionHandler#handleRateLimitExceeded} 这一处统一决定，
 * 业务代码只表达「我被限流了」这个事实与面向用户的文案。
 *
 * <h2>为什么 extends RuntimeException</h2>
 *
 * <p>与 {@link ConflictException} 同样的理由：{@code @ExceptionHandler} 按<b>最具体</b>
 * 类型匹配，继承 {@code RuntimeException} 后可单独映射为 429，不会污染
 * {@code IllegalStateException}/{@code RuntimeException} 的 500 兜底分支。
 *
 * <p>用法：{@code if (!limiter.allow(key)) throw new RateLimitExceededException("操作过于频繁，请稍后再试");}
 * —— 文案是面向用户的、不含内部细节，原样透出。
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
