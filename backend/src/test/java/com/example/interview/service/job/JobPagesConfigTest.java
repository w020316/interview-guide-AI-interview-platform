package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时源的 {@code pages-per-refresh} 配置键守卫（v1.87.0，v1.88.0 加防漏扫描）
 *
 * <p><b>为什么需要这个测试</b>：腾讯 / 亚马逊的 {@code @Value} 长期写成
 * {@code app.jobs.*}，而 application.yml 的配置在 {@code app.job-agent.*} 下 ——
 * 键名不匹配，配置与同名环境变量<b>从未生效</b>，运行时永远取默认值 1。
 * 启动正常、日志干净、测试全绿，属于本项目最警惕的「静默失效」。
 *
 * <p>本测试把「代码里的 @Value 键」与「application.yml 的键」直接对撞：
 * 任何一边改名而另一边没跟上，CI 立即变红。
 *
 * <p><b>v1.88.0 补的漏洞</b>：{@link #PROVIDERS} 是手工清单，新增源如果忘了往这里加，
 * 守卫对它就完全不生效——门禁本身会静默留空档。因此加了
 * {@link #everyProviderWithPagesFieldMustBeGuarded()}：直接扫描源码目录，
 * 凡是带 {@code @Value} 的 {@code pagesPerRefresh} 字段却没进清单的类，一律判红。
 */
class JobPagesConfigTest {

    /** 源码目录（测试运行时 cwd = backend/） */
    private static final Path SOURCE_DIR = Paths.get("src/main/java/com/example/interview/service/job");

    /**
     * 带 {@code @Value} 注解的 {@code pagesPerRefresh} 字段。
     * 注意不能只匹配字符串 {@code pagesPerRefresh}：{@code AbstractWorkdayJobProvider}
     * 里有一个同名<b>方法</b>，它不是配置字段，不应被要求进清单。
     */
    private static final Pattern GUARDED_FIELD = Pattern.compile(
            "@(?:org\\.springframework\\.beans\\.factory\\.annotation\\.)?Value\\s*\\([^)]*\\)"
                    + "\\s*(?:private|protected|public)?\\s*int\\s+pagesPerRefresh\\b");

    private static final List<Class<?>> PROVIDERS = List.of(
            TencentCareersJobProvider.class,
            AmazonChinaJobProvider.class,
            MeituanJobProvider.class,
            NeteaseJobProvider.class,
            NvidiaJobProvider.class,
            CiscoJobProvider.class,
            EleduckJobProvider.class
    );

    @Test
    @DisplayName("每个实时源的 pages-per-refresh @Value 键都必须能在 application.yml 中解析")
    void pagesPerRefreshKeysResolveFromYaml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml 必须可加载").isNotEmpty();
        sources.forEach(env.getPropertySources()::addLast);

        for (Class<?> provider : PROVIDERS) {
            Field field = provider.getDeclaredField("pagesPerRefresh");
            Value value = field.getAnnotation(Value.class);
            assertThat(value).as(provider.getSimpleName() + ".pagesPerRefresh 缺少 @Value").isNotNull();

            String expression = value.value();
            String key = expression.replace("${", "").split("[:}]")[0].trim();
            assertThat(key).as(provider.getSimpleName() + " 的配置键").startsWith("app.job-agent.");
            assertThat(env.getProperty(key))
                    .as(provider.getSimpleName() + " 读取的键 " + key + " 在 application.yml 中不存在")
                    .isNotNull();
        }
    }

    /**
     * 防漏：新增实时源必须同时加进 {@link #PROVIDERS}，否则上面那个测试对它形同虚设。
     *
     * <p>扫描源码而不是靠人工记忆——「门禁的清单本身漏了一项」是比配置键写错更隐蔽的失效。
     */
    @Test
    @DisplayName("凡带 pagesPerRefresh 配置字段的 Provider，都必须纳入本守卫清单")
    void everyProviderWithPagesFieldMustBeGuarded() throws Exception {
        assertThat(Files.isDirectory(SOURCE_DIR))
                .as("源码目录不存在，测试运行目录可能不是 backend/：" + SOURCE_DIR.toAbsolutePath())
                .isTrue();

        List<String> unguarded = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCE_DIR)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                String simpleName = name.substring(0, name.length() - ".java".length());
                if (!GUARDED_FIELD.matcher(Files.readString(file)).find()) {
                    continue;
                }
                boolean guarded = PROVIDERS.stream()
                        .anyMatch(c -> c.getSimpleName().equals(simpleName));
                if (!guarded) {
                    unguarded.add(simpleName);
                }
            }
        }

        assertThat(unguarded)
                .as("这些 Provider 有 pagesPerRefresh 配置字段，但没加进 JobPagesConfigTest.PROVIDERS，"
                        + "配置键写错也不会被发现")
                .isEmpty();
    }
}
