package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「已退休数据源下架开关」的配置契约测试（v1.89.0，QA P1-2）。
 *
 * <p><b>为什么需要这个类</b>：{@code RetiredJobSourceCleaner} 的行为用例**全部**显式调用
 * {@code setRetiredCleanupEnabled(...)}，因此「默认值被改成 false」这种变异一条都不会红
 * （QA 已实证：13 条用例全绿）。而默认值恰恰是线上唯一的生效路径——一旦为 false，
 * 启动时只会打一行 INFO，存量英文岗位继续以「国内岗位」身份出现在国内分栏，
 * 这正是本项目最怕的那类「看起来一切正常」的静默失效。
 *
 * <p>两条断言各堵一个口子：
 * <ol>
 *   <li><b>默认值</b>：直接咬住 {@code private boolean retiredCleanupEnabled = true;} 的变异；</li>
 *   <li><b>yml 键名绑定</b>：yml 里把 {@code retired-cleanup-enabled} 拼错（少个 d、多个 s）时，
 *       字段会安静地保持默认值、应用照常启动，只有「用环境变量关掉开关」这条运维路径失效——
 *       而这条路径是恢复流程的第 1 步，失效的后果是恢复永远不生效。
 *       这里解析真实 classpath 上的 application.yml 断言键存在、且默认值为 true。</li>
 * </ol>
 *
 * <p>用 Spring Boot 已自带的 SnakeYAML（{@code org.yaml.snakeyaml}）解析，不引入新依赖。
 */
@DisplayName("配置契约：已退休数据源下架开关")
class RetiredCleanupConfigContractTest {

    /** 与 JobAgentProperties 的 @ConfigurationProperties 前缀 + 字段名严格对应 */
    private static final String YML_PATH_JOB_AGENT = "job-agent";
    private static final String YML_KEY = "retired-cleanup-enabled";

    @Test
    @DisplayName("默认值必须为 true：默认关闭等于线上静默不清理（行为用例拦不住这个变异）")
    void defaultIsEnabled() {
        assertThat(new JobAgentProperties().isRetiredCleanupEnabled())
                .as("默认必须开启；改成 false 会让存量岗位清理静默失效")
                .isTrue();
    }

    @Test
    @DisplayName("application.yml 里确实存在 app.job-agent.retired-cleanup-enabled 且默认值为 true")
    void ymlKeyExistsWithTrueDefault() {
        Map<String, Object> root = loadApplicationYml();
        Object app = root.get("app");
        assertThat(app).as("application.yml 必须有 app 段").isInstanceOf(Map.class);

        Object jobAgent = asMap(app).get(YML_PATH_JOB_AGENT);
        assertThat(jobAgent)
                .as("application.yml 必须有 app.job-agent 段（键名与 @ConfigurationProperties 前缀一致）")
                .isInstanceOf(Map.class);

        Object value = asMap(jobAgent).get(YML_KEY);
        // 键名拼错时这里为 null —— 这正是要拦的情形（拼错不会报错，只会静默不生效）
        assertThat(value)
                .as("app.job-agent.%s 缺失：拼错键名不会有任何报错，只会让环境变量开关静默失效",
                        YML_KEY)
                .isNotNull();

        // 允许两种写法：字面量 true，或 ${ENV_NAME:true}（本项目统一用后者，便于运维覆盖）
        assertThat(String.valueOf(value))
                .as("默认值必须为 true（当前写法：%s）", value)
                .matches("true|\\$\\{[A-Za-z0-9_]+:true}");
    }

    @Test
    @DisplayName("环境变量覆盖名与文档一致：JOB_RETIRED_CLEANUP_ENABLED")
    void ymlEnvVarNameIsDocumented() {
        Object value = asMap(asMap(loadApplicationYml().get("app")).get(YML_PATH_JOB_AGENT)).get(YML_KEY);

        // 运维文档（类注释 / SQL 脚本 / changelog）里都写的是这个名字，改了必须同步改文档
        assertThat(String.valueOf(value)).contains("JOB_RETIRED_CLEANUP_ENABLED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * 解析 classpath 上的 application.yml。
     *
     * <p>直接读文件而不是走 {@code @SpringBootTest} 的 Environment：本类只验证
     * 「键名与默认值」这一件事，起一个完整上下文既慢又会引入无关依赖（DB/AI key 等）。
     */
    private static Map<String, Object> loadApplicationYml() {
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertThat(resource.exists()).as("classpath 上必须有 application.yml").isTrue();
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> parsed = new Yaml().load(in);
            assertThat(parsed).as("application.yml 解析结果不能为空").isNotNull();
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("解析 application.yml 失败：" + e.getMessage(), e);
        }
    }
}
