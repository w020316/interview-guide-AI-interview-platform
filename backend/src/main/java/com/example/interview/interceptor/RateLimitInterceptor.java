package com.example.interview.interceptor;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 级别请求限流拦截器（Bucket4j 内存模式）
 * - 每个 IP 每分钟最多 10 次请求
 * - 超限返回 429 Too Many Requests
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** IP → Bucket 映射，ConcurrentHashMap 保证线程安全 */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** 每分钟 10 次，令牌桶策略 */
    private Bucket resolveBucket(String ip) {
        return buckets.computeIfAbsent(ip, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(10)
                                .refillGreedy(10, Duration.ofMinutes(1))
                                .build())
                        .build()
        );
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        String ip = resolveClientIp(request);
        Bucket bucket = resolveBucket(ip);

        if (bucket.tryConsume(1)) {
            // 在响应头中暴露剩余令牌数，便于前端/客户端感知
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":429,\"message\":\"请求过于频繁，请 1 分钟后重试\",\"data\":null}"
        );
        return false;
    }

    /** 优先取代理头，兼容 Nginx/Cloudflare 反向代理 */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
