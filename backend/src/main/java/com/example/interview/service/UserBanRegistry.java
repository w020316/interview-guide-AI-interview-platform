package com.example.interview.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户禁用注册表（v1.31.4 管理后台）
 *
 * <p>v1.33.0（P2-03）：封禁名单持久化到 Redis Set（key: interview-guide:banned-users），
 * 启动时加载回进程内集合——此前封禁仅存进程内，Render 每次自动部署重启即全部解禁，
 * 且被封禁用户的 JWT（24h）无服务端吊销，重启后可立即恢复访问。
 *
 * <p>可用性约定：Redis 仅作持久化介质，读写失败均降级为进程内行为（记 WARN），不影响登录/鉴权；
 * Redis 不可用时封禁退化为「重启失效」的旧行为。单实例部署下启动加载 + 写穿透即满足一致性；
 * 多实例部署需改用每请求查 Redis 或订阅失效通知（当前部署形态为单实例）。
 *
 * <p>并发安全：ConcurrentHashMap 保证可见性；禁用/解禁即时生效。
 */
@Component
public class UserBanRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserBanRegistry.class);

    /** Redis 持久化 key（Set<Long>） */
    private static final String REDIS_KEY = "interview-guide:banned-users";

    private final ConcurrentHashMap.KeySetView<Long, Boolean> bannedIds = ConcurrentHashMap.newKeySet();

    /** Redis 不可用（含本地无 Redis 配置）时为 null，退化为纯进程内实现 */
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /** 启动时从 Redis 恢复封禁名单（应用重启后封禁不再静默失效） */
    @PostConstruct
    void loadFromRedis() {
        if (redisTemplate == null) {
            log.info("Redis 未启用，用户封禁名单仅存进程内（重启后失效）");
            return;
        }
        try {
            Set<Object> stored = redisTemplate.opsForSet().members(REDIS_KEY);
            if (stored != null) {
                for (Object o : stored) {
                    Long id = parseId(o);
                    if (id != null) {
                        bannedIds.add(id);
                    }
                }
            }
            log.info("已从 Redis 恢复封禁名单：{} 个用户", bannedIds.size());
        } catch (Exception e) {
            log.warn("封禁名单从 Redis 恢复失败（将继续使用进程内名单，重启后失效）：{}", e.getMessage());
        }
    }

    /** 禁用用户（幂等，写穿透 Redis） */
    public void ban(Long userId) {
        if (userId == null) {
            return;
        }
        bannedIds.add(userId);
        syncRedis(true, userId);
    }

    /** 解禁用户（幂等，写穿透 Redis） */
    public void unban(Long userId) {
        if (userId == null) {
            return;
        }
        bannedIds.remove(userId);
        syncRedis(false, userId);
    }

    /** 是否已禁用 */
    public boolean isBanned(Long userId) {
        return userId != null && bannedIds.contains(userId);
    }

    /** 当前禁用用户 ID 集合（只读快照） */
    public Set<Long> bannedSnapshot() {
        return Set.copyOf(bannedIds);
    }

    /** Redis 写穿透；失败仅告警（进程内已生效，Redis 恢复后下次 ban/unban 再同步） */
    private void syncRedis(boolean add, Long userId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            if (add) {
                redisTemplate.opsForSet().add(REDIS_KEY, userId);
            } else {
                redisTemplate.opsForSet().remove(REDIS_KEY, userId);
            }
        } catch (Exception e) {
            log.warn("封禁状态写 Redis 失败（仅本实例内存生效）：{}", e.getMessage());
        }
    }

    private static Long parseId(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
