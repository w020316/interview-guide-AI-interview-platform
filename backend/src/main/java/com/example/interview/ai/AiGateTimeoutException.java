package com.example.interview.ai;

/**
 * AI 并发闸门排队超时/被中断异常（v1.34.1，P3-4 异常语义统一）
 *
 * <p><b>为什么需要这个类型</b>：闸门排队超时本质是「AI 服务暂时不可用」的可重试业务故障，
 * 但此前直接抛裸 {@link IllegalStateException}，被全局异常处理器归入「非业务异常」分支，
 * 对用户返回 500「服务器内部错误」——与降级链全失败时的 503「AI 服务暂时不可用」语义不一致，
 * 用户无法判断是自己操作有误还是服务繁忙。
 *
 * <p><b>为什么继承 IllegalStateException 而不是 BusinessException</b>：
 * 闸门超时**不应立即重试**（重试只会加剧排队），而 {@code AgentService.callWithRetry}
 * 正是靠「是否为 IllegalStateException」来决定跳过重试。继承 IllegalStateException
 * 可保持这一控制流不变，同时由全局异常处理器按本类型优先匹配、映射为 503。
 *
 * <p>语义对照：402 = 额度耗尽、429 = 上游限流、503 = 无可用通道/排队超时。
 */
public class AiGateTimeoutException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AiGateTimeoutException(String message) {
        super(message);
    }

    public AiGateTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
