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
 * <p><b>P2-14（2026-10-30 质量循环）：DB 是事实，Redis/内存是缓存。</b>
 * 生产 profile 显式排除 Redis（见 {@code application-prod.yml}），原实现等于「封禁只存内存」，
 * Render 每次自动部署重启即全部解禁。现在：
 * <ol>
 *   <li>{@code users.banned} 是持久化事实（{@link #ban}/{@link #unban} 原子 UPDATE）；</li>
 *   <li>启动时先读 DB（缺列的老库按空集合降级，不阻断启动），
 *       再把 Redis 里 DB 没有的 id 回填进 DB（兼容升级前的存量封禁）；</li>
 *   <li>内存集合只用于加速 {@link #isBanned} 判定。</li>
 * </ol>
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

    /**
     * 用户仓储（P2-14）。
     * required=false 只为兼容 {@link UserBanRegistryTest} 里的纯内存实例化；
     * 生产上下文里该 bean 必然存在。
     */
    @Autowired(required = false)
    private com.example.interview.repository.UserRepository userRepository;

    /** 启动时从 DB + Redis 恢复封禁名单（应用重启后封禁不再静默失效） */
    @PostConstruct
    void loadFromRedis() {
        Set<Long> fromDb = loadFromDb();
        // 再叠加 Redis（兼容升级前只有 Redis 没有 DB 列的存量数据），并把差异回填到 DB
        Set<Long> fromRedis = loadFromRedisSet();
        bannedIds.addAll(fromDb);
        for (Long id : fromRedis) {
            bannedIds.add(id);
            if (!fromDb.contains(id)) {
                persistBanned(id, true);
            }
        }
        log.info("已恢复封禁名单：DB {} 个 + Redis {} 个 → 内存 {} 个",
                fromDb.size(), fromRedis.size(), bannedIds.size());
    }

    /** DB 读取；缺列/连接异常按空集合降级（不阻断启动） */
    private Set<Long> loadFromDb() {
        if (userRepository == null) {
            return Set.of();
        }
        try {
            return new java.util.HashSet<>(userRepository.findBannedIds());
        } catch (Exception e) {
            log.warn("封禁名单从数据库恢复失败（可能尚未执行 banned 列 DDL）：{}", e.getMessage());
            return Set.of();
        }
    }

    /** Redis 读取；不可用/异常按空集合降级 */
    private Set<Long> loadFromRedisSet() {
        if (redisTemplate == null) {
            return Set.of();
        }
        try {
            Set<Object> stored = redisTemplate.opsForSet().members(REDIS_KEY);
            Set<Long> ids = new java.util.HashSet<>();
            if (stored != null) {
                for (Object o : stored) {
                    Long id = parseId(o);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("封禁名单从 Redis 恢复失败（以数据库为准）：{}", e.getMessage());
            return Set.of();
        }
    }

    /** 禁用用户（幂等：DB 持久化 + 内存 + Redis 写穿透） */
    public void ban(Long userId) {
        if (userId == null) {
            return;
        }
        bannedIds.add(userId);
        persistBanned(userId, true);
        syncRedis(true, userId);
    }

    /** 解禁用户（幂等：DB 持久化 + 内存 + Redis 写穿透） */
    public void unban(Long userId) {
        if (userId == null) {
            return;
        }
        bannedIds.remove(userId);
        persistBanned(userId, false);
        syncRedis(false, userId);
    }

    /** DB 写穿透；失败仅告警（内存已生效，但不能假装持久化成功） */
    private void persistBanned(Long userId, boolean banned) {
        if (userRepository == null) {
            return;
        }
        try {
            userRepository.updateBanned(userId, banned);
        } catch (Exception e) {
            log.warn("封禁状态写数据库失败（仅本实例内存生效，重启后会丢失）：{}", e.getMessage());
        }
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
