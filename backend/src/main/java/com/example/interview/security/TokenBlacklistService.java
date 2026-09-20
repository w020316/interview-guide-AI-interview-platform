package com.example.interview.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 黑名单（P1，2026-09-20）：登出即吊销
 *
 * <p>背景：JWT 无状态，登出此前仅由前端清除 token，被盗 token 在最长 24h 有效期内
 * 无法吊销（阶段一批次3 P1 发现，原 P3 backlog）。
 *
 * <p>方案 B（用户确认）：进程内黑名单——零额外依赖、零 Redis 命令配额消耗。
 * <ul>
 *   <li>条目为 jti → token 过期时间戳；token 过期后黑名单条目即失去意义，惰性清理；</li>
 *   <li>生产为 Render 免费层<b>单实例</b>，进程内语义完全够用。若未来横向扩容，
 *       需升级为 Redis 共享黑名单（届时注意 Upstash 免费层 1 万命令/天配额，
 *       不宜每请求同步查库，应本地缓存 + 定时同步）；</li>
 *   <li>容量护栏：惰性清理后仍超 {@link #MAX_ENTRIES} 时按过期时间淘汰最早条目，
 *       保证常驻内存有界（512MB 容器）。登出为用户低频操作，正常远达不到上限。</li>
 * </ul>
 */
@Component
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** 黑名单条目上限（防御异常灌入；登出频率极低，正常远达不到） */
    private static final int MAX_ENTRIES = 50_000;

    /** jti → token 过期时间（epoch 毫秒） */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    /**
     * 吊销 token：将 jti 加入黑名单，条目存活至 token 自然过期
     *
     * @param jti         token 唯一标识（旧版无 jti 的 token 传 null，本方法直接忽略）
     * @param expiresAtMs token 过期时间（epoch 毫秒）
     */
    public void revoke(String jti, long expiresAtMs) {
        if (jti == null || jti.isBlank() || expiresAtMs <= System.currentTimeMillis()) {
            return; // 已过期的 token 无需吊销
        }
        revoked.put(jti, expiresAtMs);
        if (revoked.size() > MAX_ENTRIES) {
            sweep();
            if (revoked.size() > MAX_ENTRIES) {
                evictOldest();
            }
        }
    }

    /**
     * 该 jti 是否已被吊销（null 安全；条目过期时顺带清理并放行——token 本身也已无法通过校验）
     */
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        Long expiresAt = revoked.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            revoked.remove(jti, expiresAt);
            return false;
        }
        return true;
    }

    /** 清理已过期条目（其对应 token 已无法通过签名/过期校验） */
    private void sweep() {
        long now = System.currentTimeMillis();
        revoked.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /** 容量护栏：淘汰最早过期的条目（它们本就临近自然失效） */
    private void evictOldest() {
        int excess = revoked.size() - MAX_ENTRIES;
        log.warn("JWT 黑名单超过 {} 条，容量护栏淘汰最早过期的 {} 条", MAX_ENTRIES, excess);
        revoked.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(excess)
                .forEach(e -> revoked.remove(e.getKey(), e.getValue()));
    }

    /** 当前黑名单条数（监控与测试用） */
    public int size() {
        return revoked.size();
    }
}
