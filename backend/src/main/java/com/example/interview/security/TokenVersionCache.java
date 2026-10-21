package com.example.interview.security;

import com.example.interview.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 令牌版本查询与本地缓存（P1-2，2026-10-21）。
 *
 * <h2>为什么不能每个请求都查库</h2>
 *
 * <p>{@link JwtAuthFilter} 是<b>全站每个已认证请求的必经路径</b>。每次都
 * {@code SELECT token_version FROM users WHERE id = ?} 会给 PostgreSQL（免费层连接数很小）
 * 增加 QPS 与连接占用 —— 而这条查询的答案是"几乎不变"的（只有登出/改密/封禁才变）。
 * 因此缓存 {@value #TTL_MS} 毫秒。
 *
 * <h2>为什么单独一个 Bean，而不是把 Map 放在过滤器里</h2>
 *
 * <p>因为<b>失效方</b>（登出、改密、封禁）在控制器与 service 层，而<b>读取方</b>在过滤器里。
 * 若把缓存放在过滤器上，就得把 {@code JwtAuthFilter} 注入控制器和 service —— 让业务层
 * 依赖一个 Servlet 过滤器，既别扭又会在 {@code @WebMvcTest} 里引出额外的 mock。
 * 抽成独立的 {@code @Component} 后，双方都只依赖一个小而明确的接口。
 *
 * <h2>多实例局限（必须知道）</h2>
 *
 * <p>缓存是<b>进程内</b>的。Render 当前是单实例，主动失效即时生效；若将来横向扩容，
 * {@link #invalidate(Long)} 只清本地实例，其它实例最坏延迟 = {@link #TTL_MS}。
 * 也就是「登出后最多 ~60 秒内，别的实例可能仍认这个 token」。
 * 真要跨实例即时失效，得把版本号放进共享存储（Redis）——而 prod 明确不依赖 Redis
 * （见 application-prod.yml 的排除说明），故本轮接受这个上限。
 */
@Component
public class TokenVersionCache {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionCache.class);

    /** 缓存有效期：60 秒。取值权衡：登出后最长 60s 内旧 token 仍可能被本实例接受 */
    static final long TTL_MS = 60_000L;

    /**
     * {@code users.token_version} 读取。
     *
     * <p>{@code required = false}：{@code @WebMvcTest} 切片不加载 repository，
     * 缺失时 {@link #currentVersion(Long)} 返回 {@code null}（= 无法校验 → 调用方跳过校验），
     * 否则全部控制器切片测试会因为缺 Bean 而集体启动失败。
     */
    @Autowired(required = false)
    private UserRepository userRepository;

    /** userId → 版本号缓存（带过期时间） */
    private final ConcurrentHashMap<Long, CachedVersion> cache = new ConcurrentHashMap<>();

    /** 供测试注入时钟，避免真的 sleep 60 秒 */
    private LongSupplier clock = System::currentTimeMillis;

    /**
     * 取用户的当前令牌版本。
     *
     * @return 版本号；{@code null} 表示<b>无法确定</b>（切片测试无 repository / 用户不存在 /
     *         查询异常）—— 调用方应跳过版本校验而不是拒绝请求
     */
    public Integer currentVersion(Long userId) {
        if (userId == null) {
            return null;
        }
        long now = clock.getAsLong();
        CachedVersion cached = cache.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.value();
        }
        if (userRepository == null) {
            return null;
        }
        try {
            Integer version = userRepository.findById(userId)
                    .map(com.example.interview.entity.UserEntity::getTokenVersion)
                    .orElse(null);
            if (version == null) {
                // 用户不存在（或历史数据缺列值）：不缓存，避免把「暂时查不到」固化 60 秒
                log.debug("无法读取用户令牌版本（用户不存在或版本为空）：userId={}", userId);
                return null;
            }
            cache.put(userId, new CachedVersion(version, now + TTL_MS));
            return version;
        } catch (Exception e) {
            // 数据库抖动不应把全站打成 401：跳过校验（fail-open）并留日志
            log.warn("读取令牌版本失败，本次跳过版本校验：userId={} 原因={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 主动失效缓存（登出 / 改密 / 封禁后调用）。
     *
     * <p>不做这一下，用户登出后最坏还要等 {@link #TTL_MS} 才会被本实例拒绝。
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        cache.remove(userId);
    }

    /** 仅供测试观察缓存规模 */
    int cachedSize() {
        return cache.size();
    }

    /** 仅供测试替换时钟 */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** 缓存条目 */
    private record CachedVersion(int value, long expiresAt) {
    }
}
