package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application-prod.yml} 的 CORS 白名单「配置锁」（F-13 补充）。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>{@link SecurityConfigCorsTest} 用 {@code ReflectionTestUtils} 硬注入来源，
 * 验证的是「prod profile 下 localhost 会被剔除」这段<b>代码逻辑</b>，
 * 它<b>不读</b> {@code application-prod.yml}。于是存在一个空洞：
 * 如果将来有人在 yml 里把 {@code http://localhost:5173} 加回生产默认白名单，
 * 那段单测照样全绿——因为代码逻辑没变，变的是配置。
 *
 * <p>本测试直接解析真实配置文件（不启动 Spring 上下文，秒级完成），
 * 让「配置漂移」本身变红。
 *
 * <h2>它锁住的两件事</h2>
 *
 * <ol>
 *   <li>prod 默认白名单里不得出现任何本地来源（localhost / 127.0.0.1 / [::1] / 0.0.0.0）；</li>
 *   <li>必须保留 {@code ${CORS_ALLOWED_ORIGINS:...}} 这个环境变量占位，
 *       否则运维换域名时无法覆盖，会重演「换域名即全站 403」的运维陷阱。</li>
 * </ol>
 */
@DisplayName("application-prod.yml CORS 白名单配置锁（F-13）")
class ProdCorsConfigLockTest {

    /** surefire 的工作目录是模块根（backend/），因此用相对路径即可 */
    private static final Path PROD_YML =
            Path.of("src", "main", "resources", "application-prod.yml");

    @Test
    @DisplayName("prod 默认白名单不含任何本地来源，且保留 pages.dev 与 CORS_ALLOWED_ORIGINS 占位")
    void prodDefaultOriginsAreFreeOfLocalSources() throws Exception {
        String origins = prodAllowedOrigins();

        assertThat(origins)
                .as("生产白名单不得包含开发者本机来源：%s", origins)
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("[::1]")
                .doesNotContain("0.0.0.0");

        assertThat(origins)
                .as("生产默认白名单应保留 Cloudflare Pages 通配来源")
                .contains("pages.dev");

        assertThat(origins)
                .as("必须保留 CORS_ALLOWED_ORIGINS 占位，否则换域名无法通过环境变量覆盖")
                .contains("cors_allowed_origins");
    }

    @Test
    @DisplayName("prod 必须显式覆盖 app.cors.allowed-origins（不能只靠 base 配置的含 localhost 默认值）")
    void prodMustOverrideAllowedOrigins() throws Exception {
        Object raw = nested(loadProdYaml(), "app", "cors", "allowed-origins");

        assertThat(raw)
                .as("application-prod.yml 缺少 app.cors.allowed-origins 覆盖时，"
                        + "生产会继承 application.yml 里含 localhost 的默认值")
                .isNotNull();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private static String prodAllowedOrigins() throws Exception {
        Object raw = nested(loadProdYaml(), "app", "cors", "allowed-origins");
        assertThat(raw).isNotNull();
        return String.valueOf(raw).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadProdYaml() throws Exception {
        assertThat(Files.exists(PROD_YML))
                .as("找不到 %s（当前工作目录：%s）", PROD_YML, Path.of("").toAbsolutePath())
                .isTrue();
        try (InputStream in = Files.newInputStream(PROD_YML)) {
            Object loaded = new Yaml().load(in);
            assertThat(loaded).isInstanceOf(Map.class);
            return (Map<String, Object>) loaded;
        }
    }

    /** 按 key 链取嵌套 YAML 节点；任一层缺失返回 null */
    private static Object nested(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }
}
