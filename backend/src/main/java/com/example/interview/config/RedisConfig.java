package com.example.interview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;

/**
 * Redis 缓存配置（容错实现，保证应用启动不依赖 Redis）
 *
 * <p>背景：生产环境通过环境变量 REDIS_URL 注入 Upstash 端点。若该端点失效（免费层停用/域名过期），
 * Redis 自动装配在创建 LettuceConnectionFactory bean 时会对主机做 DNS 解析并抛出
 * {@link java.net.UnknownHostException}，导致整个 Spring 上下文无法启动（表现为后端永远冷启动/崩溃重启）。
 *
 * <p>因此这里不依赖 Spring 自动装配，而是显式解析 spring.data.redis.url（REDIS_URL）：
 * - 支持 redis:// 与 rediss://（Upstash TLS）协议，含内嵌用户名密码
 * - 解析失败或未配置时回退 127.0.0.1（本机地址必然可解析，启动绝不因 Redis 失败）
 * - Redis 实际不可用时连接发生在首次调用，由各服务层 try/catch 降级兜底
 *   （Redis 仅用于 AI 响应缓存，不影响登录/鉴权）
 *
 * <p>v1.23.1 修复：此前工厂硬编码 127.0.0.1，导致生产 REDIS_URL 从未生效、AI 缓存 100% 未命中。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /** 与 spring.data.redis.url 同源（application.yml 中由 REDIS_URL 注入） */
    @Value("${spring.data.redis.url:}")
    private String redisUrl;

    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        LettuceConnectionFactory factory;
        try {
            factory = buildFactory(redisUrl);
            factory.setShutdownTimeout(1000L);
            factory.afterPropertiesSet();
        } catch (Exception e) {
            // v1.25.1 修复：bean 初始化失败不能拖垮整个应用（此前 REDIS_URL 生效时
            // 不可变 client config 触发 IllegalStateException → 崩溃循环）
            log.error("Redis 工厂初始化失败（{}），回退本机 Redis（服务层降级兜底）", e.getMessage());
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379));
            factory.setShutdownTimeout(1000L);
            factory.afterPropertiesSet();
        }

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 解析 Redis URL 构造连接工厂；任何解析异常都安全回退到本机地址（保证启动成功）
     *
     * <p>v1.25.1 修复：改用「单参构造 + 公共 setter」。
     * 此前通过 {@code new LettuceConnectionFactory(cfg, LettuceClientConfiguration.builder()...build())}
     * 传入不可变配置，而 factory 的所有公共 setter（setShutdownTimeout 等）内部都要求
     * MutableLettuceClientConfiguration（SD Redis 3.3.6 中为包私有类，公共 API 无法构造），
     * 导致 REDIS_URL 生效时启动即抛 IllegalStateException → 崩溃循环。
     * 单参构造的 factory 内部自建 mutable 配置，setUseSsl/setTimeout 安全可用。
     */
    private LettuceConnectionFactory buildFactory(String url) {
        try {
            if (url != null && !url.isBlank() && url.startsWith("redis")) {
                URI uri = new URI(url.trim());
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("Redis URL 缺少 host");
                }
                boolean ssl = "rediss".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() == -1 ? (ssl ? 6380 : 6379) : uri.getPort();

                RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isBlank()) {
                    int sep = userInfo.indexOf(':');
                    if (sep >= 0) {
                        cfg.setUsername(userInfo.substring(0, sep));
                        cfg.setPassword(userInfo.substring(sep + 1));
                    } else {
                        cfg.setUsername(userInfo);
                    }
                }

                LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
                factory.setUseSsl(ssl);
                factory.setTimeout(5000L);
                log.info("Redis 缓存连接目标：{}:{} (ssl={})", host, port, ssl);
                return factory;
            }
        } catch (Exception e) {
            log.warn("REDIS_URL 解析失败（{}），回退本机 Redis：{}", e.getMessage(), url);
        }
        log.info("Redis 未配置或解析失败，回退本机 127.0.0.1:6379（服务层会降级兜底）");
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379));
    }
}
