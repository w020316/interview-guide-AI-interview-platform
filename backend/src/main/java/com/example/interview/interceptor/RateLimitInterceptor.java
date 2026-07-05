package com.example.interview.interceptor;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 级别请求限流拦截器（Bucket4j 内存模式）
 * - 每个 IP 每分钟最多 10 次请求
 * - 超限返回 429 Too Many Requests
 * - 使用 Caffeine 风格的过期清理，防止内存泄漏
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** IP → Bucket 映射，附带最后访问时间用于过期清理 */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Bucket 最后访问时间，用于过期清理 */
    private final ConcurrentHashMap<String, Long> lastAccess = new ConcurrentHashMap<>();

    /** 清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /** Bucket 过期时间（30 分钟无访问则清理） */
    private static final long BUCKET_EXPIRE_MS = 30 * 60 * 1000L;

    private Bucket resolveBucket(String ip) {
        cleanupIfNeeded();
        lastAccess.put(ip, System.currentTimeMillis());
        return buckets.computeIfAbsent(ip, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(10)
                                .refillGreedy(10, Duration.ofMinutes(1))
                                .build())
                        .build()
        );
    }

    /** 定期清理过期的 Bucket，防止内存泄漏 */
    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;
        lastAccess.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > BUCKET_EXPIRE_MS) {
                buckets.remove(entry.getKey());
                return true;
            }
            return false;
        });
        log.debug("限流桶清理完成，当前桶数量：{}", buckets.size());
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        String ip = resolveClientIp(request);
        Bucket bucket = resolveBucket(ip);

        if (bucket.tryConsume(1)) {
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return true;
        }

        log.warn("IP {} 请求频率超限", ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":429,\"message\":\"请求过于频繁，请 1 分钟后重试\",\"data\":null}"
        );
        return false;
    }

    /**
     * 解析客户端 IP
     * 仅在直连 IP 为受信代理时才读 X-Forwarded-For，防止 IP 伪造
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        // 仅当直连是内网/代理时才信任 X-Forwarded-For
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remoteAddr)) {
            return forwarded.split(",")[0].trim();
        }
        return remoteAddr;
    }

    /** 判断是否为受信代理（精确校验内网地址，避免 172.* 误判） */
    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.equals("127.0.0.1")
                || ip.startsWith("::1")) {
            return true;
        }
        // 精确校验 172.16.0.0/12
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
