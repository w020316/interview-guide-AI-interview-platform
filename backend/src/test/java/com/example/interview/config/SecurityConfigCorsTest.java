package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityConfig} CORS 配置测试（F-13）。
 *
 * <p>直接构造 {@link SecurityConfig} 并调用 {@code buildCorsConfiguration()}：
 * CORS 的取值是纯配置推导，用 MockMvc 走一遍预检只会增加噪声，
 * 而 {@code Access-Control-Allow-Origin} 响应头无法区分
 * {@code allowCredentials(true/false)} 与「prod 是否真剔除了 localhost」这两个关键点。
 */
@DisplayName("SecurityConfig CORS 收紧（F-13）")
class SecurityConfigCorsTest {

    /** 覆盖生产与本地两类来源，模拟 CORS_ALLOWED_ORIGINS 被误填的情况 */
    private static final String MIXED_ORIGINS =
            "https://*.pages.dev, http://localhost:5173, http://127.0.0.1:5173, http://localhost:*";

    private SecurityConfig securityConfig(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        SecurityConfig config = new SecurityConfig(environment);
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", MIXED_ORIGINS);
        ReflectionTestUtils.setField(config, "trustedProxyPrefixes", "");
        return config;
    }

    @Nested
    @DisplayName("prod profile")
    class ProdProfile {

        @Test
        @DisplayName("localhost 系列来源必须全部从白名单移除（即使配置里写了）")
        void localhostOriginsAreRemoved() {
            CorsConfiguration config = securityConfig("prod").buildCorsConfiguration();

            assertThat(config.getAllowedOriginPatterns())
                    .as("prod 白名单只应保留真正的生产来源")
                    .containsExactly("https://*.pages.dev");
            assertThat(config.getAllowedOriginPatterns())
                    .noneMatch(origin -> origin.contains("localhost") || origin.contains("127.0.0.1"));
        }

        @Test
        @DisplayName("allowCredentials 必须为 false（鉴权走 Bearer，不依赖 Cookie）")
        void credentialsAreNotAllowed() {
            CorsConfiguration config = securityConfig("prod").buildCorsConfiguration();

            assertThat(config.getAllowCredentials()).isFalse();
        }
    }

    @Nested
    @DisplayName("dev / 默认 profile")
    class DevProfile {

        @Test
        @DisplayName("local profile 保留 localhost 便利，但同样不允许携带凭证")
        void localProfileKeepsLocalhostWithoutCredentials() {
            CorsConfiguration config = securityConfig("local").buildCorsConfiguration();

            assertThat(config.getAllowedOriginPatterns())
                    .contains("http://localhost:5173", "http://127.0.0.1:5173");
            assertThat(config.getAllowCredentials()).isFalse();
        }

        @Test
        @DisplayName("未激活任何 profile 时默认保留 localhost（本地直接 run 的场景）")
        void defaultProfileKeepsLocalhost() {
            CorsConfiguration config = securityConfig().buildCorsConfiguration();

            assertThat(config.getAllowedOriginPatterns()).contains("http://localhost:5173");
            assertThat(config.getAllowCredentials()).isFalse();
        }
    }

    @Nested
    @DisplayName("其它 CORS 安全设定未被本次改动破坏")
    class PreservedSettings {

        @Test
        @DisplayName("Authorization 头仍在允许列表（Bearer 鉴权依赖它）")
        void authorizationHeaderStillAllowed() {
            CorsConfiguration config = securityConfig("prod").buildCorsConfiguration();

            assertThat(config.getAllowedHeaders()).contains("Authorization", "Content-Type");
            assertThat(config.getExposedHeaders()).contains("X-Rate-Limit-Remaining");
            assertThat(config.getAllowedMethods())
                    .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
        }

        @Test
        @DisplayName("白名单为空时不会退化成允许全部来源")
        void blankOriginsNeverBecomeWildcard() {
            SecurityConfig config = securityConfig("prod");
            ReflectionTestUtils.setField(config, "corsAllowedOrigins", "  ,  ");

            CorsConfiguration built = config.buildCorsConfiguration();

            assertThat(built.getAllowedOriginPatterns()).isEmpty();
        }
    }
}
