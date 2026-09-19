package com.example.interview.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 配置测试（不建立真实 Redis 连接——LettuceConnectionFactory 为惰性连接，
 * afterPropertiesSet 仅初始化配置不发起网络请求）
 *
 * <p>覆盖：REDIS_URL 各协议解析（redis/rediss、默认端口、内嵌账号密码、无账号、缺 host 回退、
 * 非法 URL 回退、未配置回退）、日志打码、模板序列化器配置。
 */
@DisplayName("Redis 配置测试")
class RedisConfigTest {

    private final RedisConfig config = new RedisConfig();

    @BeforeEach
    void resetUrl() {
        ReflectionTestUtils.setField(config, "redisUrl", "");
    }

    /** 反射调用私有 buildFactory 并返回构造的连接工厂（不连接） */
    private LettuceConnectionFactory buildFactory(String url) {
        return (LettuceConnectionFactory) ReflectionTestUtils.invokeMethod(config, "buildFactory", url);
    }

    @Test
    @DisplayName("buildFactory: redis:// 标准 URL 解析 host/port")
    void buildFactory_redisUrl() {
        LettuceConnectionFactory f = buildFactory("redis://myredis.example.com:6381");
        assertThat(f.getHostName()).isEqualTo("myredis.example.com");
        assertThat(f.getPort()).isEqualTo(6381);
        assertThat(f.isUseSsl()).isFalse();
    }

    @Test
    @DisplayName("buildFactory: rediss:// 启用 SSL，缺省端口 6380，内嵌账号密码")
    void buildFactory_redissUrlWithCredentials() {
        LettuceConnectionFactory f = buildFactory("rediss://default:secret123@upstash.io");
        assertThat(f.getHostName()).isEqualTo("upstash.io");
        assertThat(f.getPort()).isEqualTo(6380);
        assertThat(f.isUseSsl()).isTrue();

        // 独立配置对象上的凭据（LettuceConnectionFactory 不暴露 getter，经 standalone configuration 校验）
        var cfg = (org.springframework.data.redis.connection.RedisStandaloneConfiguration)
                ReflectionTestUtils.getField(f, "configuration");
        assertThat(cfg.getUsername()).isEqualTo("default");
        assertThat(cfg.getPassword()).isEqualTo(
                org.springframework.data.redis.connection.RedisPassword.of("secret123"));
    }

    @Test
    @DisplayName("buildFactory: redis:// 仅用户名（无冒号分隔）只设 username")
    void buildFactory_userInfoWithoutPassword() {
        LettuceConnectionFactory f = buildFactory("redis://onlyuser@host.example.com:6379");
        assertThat(f.getHostName()).isEqualTo("host.example.com");

        var cfg = (org.springframework.data.redis.connection.RedisStandaloneConfiguration)
                ReflectionTestUtils.getField(f, "configuration");
        assertThat(cfg.getUsername()).isEqualTo("onlyuser");
        // 无密码场景：get() 抛 NoSuchElementException（RedisPassword.EMPTY 内部语义）
        org.assertj.core.api.Assertions.assertThatThrownBy(cfg.getPassword()::get)
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("buildFactory: 缺 host 的 URL 抛错被捕获，回退本机")
    void buildFactory_missingHost_fallbackLocal() {
        LettuceConnectionFactory f = buildFactory("redis://");
        assertThat(f.getHostName()).isEqualTo("127.0.0.1");
        assertThat(f.getPort()).isEqualTo(6379);
    }

    @Test
    @DisplayName("buildFactory: 非法 URL（URI 解析失败）回退本机")
    void buildFactory_invalidUrl_fallbackLocal() {
        LettuceConnectionFactory f = buildFactory("redis://bad url with spaces:6379");
        assertThat(f.getHostName()).isEqualTo("127.0.0.1");
        assertThat(f.getPort()).isEqualTo(6379);
    }

    @Test
    @DisplayName("buildFactory: 非 redis 前缀与空串/null 未配置路径均回退本机")
    void buildFactory_unconfigured_fallbackLocal() {
        assertThat(buildFactory("http://not-redis.example.com").getHostName()).isEqualTo("127.0.0.1");
        assertThat(buildFactory("   ").getHostName()).isEqualTo("127.0.0.1");
        assertThat(buildFactory(null).getHostName()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("redisTemplate: 未配置 URL 时返回完整配置的模板（key/值序列化器类型正确）")
    void redisTemplate_unconfigured_buildsTemplate() {
        RedisTemplate<String, Object> template = config.redisTemplate();

        assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        // value 序列化器为 JSON（GenericJackson2JsonRedisSerializer）
        assertThat(template.getValueSerializer()).isInstanceOf(
                org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer.class);
        assertThat(template.getHashValueSerializer()).isInstanceOf(
                org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer.class);
        assertThat(template.getConnectionFactory()).isInstanceOf(LettuceConnectionFactory.class);
    }

    @Test
    @DisplayName("redisTemplate: 合法 REDIS_URL 时模板连接目标为该主机（v1.23.1 修复回归）")
    void redisTemplate_validUrl_usesConfiguredHost() {
        ReflectionTestUtils.setField(config, "redisUrl", "redis://prod-redis.example.com:6390");
        RedisTemplate<String, Object> template = config.redisTemplate();

        LettuceConnectionFactory factory = (LettuceConnectionFactory) template.getConnectionFactory();
        assertThat(factory.getHostName()).isEqualTo("prod-redis.example.com");
        assertThat(factory.getPort()).isEqualTo(6390);
    }

    @Test
    @DisplayName("redisTemplate: REDIS_URL 解析失败时回退本机（不拖垮启动）")
    void redisTemplate_invalidUrl_fallsBackToLocal() {
        ReflectionTestUtils.setField(config, "redisUrl", "redis://");
        RedisTemplate<String, Object> template = config.redisTemplate();

        LettuceConnectionFactory factory = (LettuceConnectionFactory) template.getConnectionFactory();
        assertThat(factory.getHostName()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("maskCredentials: 打码 URL 用户信息段，无凭据时原样")
    void maskCredentials() {
        assertThat((String) ReflectionTestUtils.invokeMethod(config, "maskCredentials",
                "rediss://default:secret@host")).isEqualTo("rediss://***@host");
        assertThat((String) ReflectionTestUtils.invokeMethod(config, "maskCredentials",
                "redis://host:6379")).isEqualTo("redis://host:6379");
        assertThat((Object) ReflectionTestUtils.invokeMethod(config, "maskCredentials", (Object) null)).isNull();
    }
}
