package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时源的 {@code pages-per-refresh} 配置键守卫（v1.87.0）
 *
 * <p><b>为什么需要这个测试</b>：腾讯 / 亚马逊的 {@code @Value} 长期写成
 * {@code app.jobs.*}，而 application.yml 的配置在 {@code app.job-agent.*} 下 ——
 * 键名不匹配，配置与同名环境变量<b>从未生效</b>，运行时永远取默认值 1。
 * 启动正常、日志干净、测试全绿，属于本项目最警惕的「静默失效」。
 *
 * <p>本测试把「代码里的 @Value 键」与「application.yml 的键」直接对撞：
 * 任何一边改名而另一边没跟上，CI 立即变红。
 */
class JobPagesConfigTest {

    private static final List<Class<?>> PROVIDERS = List.of(
            TencentCareersJobProvider.class,
            AmazonChinaJobProvider.class,
            MeituanJobProvider.class,
            NeteaseJobProvider.class
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
}
