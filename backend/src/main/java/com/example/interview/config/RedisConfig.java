package com.example.interview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 缓存配置（容错实现，保证应用启动不依赖 Redis）
 *
 * <p>背景：生产环境通过环境变量 REDIS_URL 注入 Upstash 端点。若该端点失效（免费层停用/域名过期），
 * Redis 自动装配在创建 LettuceConnectionFactory bean 时会对主机做 DNS 解析并抛出
 * {@link java.net.UnknownHostException}，导致整个 Spring 上下文无法启动（表现为后端永远冷启动/崩溃重启）。
 *
 * <p>因此这里不再依赖 Spring 自动装配，改为显式构造一个指向 127.0.0.1 的回退连接工厂：
 * 本机地址必然可解析，启动绝不会因 Redis 失败；Redis 实际不可用时连接发生在首次调用，
 * 由各服务层的 try/catch 降级兜底（Redis 仅用于 AI 响应缓存，不影响登录/鉴权）。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        // 使用本机地址构造工厂：保证启动时主机可解析，连接延迟到首次使用
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.setShutdownTimeout(1000L);
        factory.afterPropertiesSet();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}