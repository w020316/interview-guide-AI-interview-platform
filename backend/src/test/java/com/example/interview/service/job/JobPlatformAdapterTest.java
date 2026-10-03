package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("招聘数据适配器与内置数据源测试")
class JobPlatformAdapterTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private JobAgentProperties properties = new JobAgentProperties();

    @InjectMocks
    private HttpJobPlatformAdapter adapter;

    private final SeedCampusJobProvider campusProvider = new SeedCampusJobProvider();
    private final SeedMultiChannelJobProvider multiProvider = new SeedMultiChannelJobProvider();
    private final SeedLiveHotJobsProvider liveProvider = new SeedLiveHotJobsProvider();

    /**
     * 种子数据集基准日（v1.48.0，第六轮 B3）。
     *
     * <p>内置种子数据是**硬编码**的 2026–2027 招聘季日期，与运行时钟无关。用它作为
     * 「截止日期有效性」的比较基准，避免断言随 wall-clock 每天腐烂（详见 {@link #assertSeedIntegrity}）。
     * 数据集刷新时同步更新本常量即可。
     */
    private static final LocalDate SEED_EPOCH = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("parseJobs: TARGETED 定向/专项类映射")
    void parseJobs_targetedMapping() {
        String body = """
                {"data":[
                  {"id":"t1","title":"定向选调生","company":"某省委组织部","recruitType":"定向选调"},
                  {"id":"t2","title":"青苗计划","company":"某国企","recruitType":"专项招聘"},
                  {"id":"t3","title":"TARGETED 透传","company":"某公司","recruitType":"TARGETED"}
                ]}
                """;
        List<JobDto> jobs = adapter.parseJobs("测试平台", body);
        assertThat(jobs).hasSize(3);
        assertThat(jobs.get(0).recruitType()).isEqualTo("TARGETED");
        assertThat(jobs.get(1).recruitType()).isEqualTo("TARGETED");
        assertThat(jobs.get(2).recruitType()).isEqualTo("TARGETED");
    }

    @Test
    @DisplayName("parseJobs: 字段宽容映射 + 日期解析 + 缺关键字段丢弃")
    void parseJobs_lenientMappingAndDirtyData() {
        String body = """
                {"data":[
                  {"jobId":"a1","jobName":"后端开发","companyName":"某公司","city":"深圳",
                   "salaryDesc":"20k-35k","education":"本科及以上","workExp":"1-3 年",
                   "jobNature":"社招","closeTime":"2026-12-31","link":"https://x.com/1"},
                  {"title":"缺公司名"},{"jobId":"a2","company":"缺标题"},
                  {"id":"a3","title":"中文日期","company":"某公司","closeTime":"2026年12月31日"}
                ]}
                """;
        List<JobDto> jobs = adapter.parseJobs("测试平台", body);
        assertThat(jobs).hasSize(2);
        JobDto first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("a1");
        assertThat(first.title()).isEqualTo("后端开发");
        assertThat(first.location()).isEqualTo("深圳");
        assertThat(first.recruitType()).isEqualTo("SOCIAL");
        assertThat(first.deadline()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(jobs.get(1).deadline()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("isEnabled: 无任何 endpoint 配置时为 false，配置通用渠道后为 true")
    void isEnabled_channelsAndPlatforms() {
        assertThat(adapter.isEnabled()).isFalse();

        JobAgentProperties.ChannelConfig channel = new JobAgentProperties.ChannelConfig();
        channel.setName("聚合服务A");
        channel.setEndpoint("https://example.com/jobs");
        properties.getChannels().add(channel);

        assertThat(adapter.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("秋招种子数据：externalId 唯一、招聘类型合法、截止日期不早于数据集基准日")
    void campusSeed_dataIntegrity() {
        assertSeedIntegrity(campusProvider.fetch());
    }

    @Test
    @DisplayName("多频道种子数据：externalId 唯一、类型覆盖 SPRING/SOCIAL/INTERN/TARGETED、截止日期不早于数据集基准日")
    void multiSeed_dataIntegrityAndTypeCoverage() {
        List<JobDto> jobs = multiProvider.fetch();
        assertSeedIntegrity(jobs);
        Set<String> types = new HashSet<>(jobs.stream().map(JobDto::recruitType).toList());
        assertThat(types).containsExactlyInAnyOrder("SPRING", "SOCIAL", "INTERN", "TARGETED");
        // 行业覆盖广度：不少于 8 个不同行业
        long industries = jobs.stream().map(JobDto::industry).distinct().count();
        assertThat(industries).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("热招速递种子数据：externalId 唯一、广州Java实习非空、无海外单城市岗位")
    void liveSeed_dataIntegrityAndGzJavaCoverage() {
        List<JobDto> jobs = liveProvider.fetch();
        assertSeedIntegrity(jobs);
        assertThat(jobs).hasSizeGreaterThanOrEqualTo(20);
        // 广州 + Java/后端 + 实习 组合必须非空（此前零覆盖，重点补齐目标）
        long gzJavaIntern = jobs.stream().filter(j ->
                j.location().contains("广州") && (j.title().contains("Java") || j.title().contains("后端"))
                        && "INTERN".equals(j.recruitType())).count();
        assertThat(gzJavaIntern).as("广州Java/后端实习组合应有数据").isGreaterThanOrEqualTo(3);
        // 不得出现海外单城市岗位（如新加坡）
        assertThat(jobs).noneMatch(j -> j.location().contains("新加坡"));
    }

    private void assertSeedIntegrity(List<JobDto> jobs) {
        Set<String> ids = new HashSet<>();
        for (JobDto j : jobs) {
            assertThat(ids.add(j.externalId())).as("externalId 重复：%s", j.externalId()).isTrue();
            assertThat(j.title()).isNotBlank();
            assertThat(j.companyName()).isNotBlank();
            assertThat(j.recruitType()).isIn("AUTUMN", "SPRING", "SOCIAL", "INTERN", "TARGETED");
            if (j.deadline() != null) {
                // v1.48.0（第六轮 B3）：把「截止日期不得早于**今天**」改为「不得早于种子数据集基准日」。
                //
                // 为什么必须改：种子数据是**硬编码**的招聘季日期（如 2026-09-30、2026-10-15…），
                // 而原断言用 LocalDate.now() 做比较——过了 2026-09-30 之后每天都必红
                // （2026-10-03 实测 campusSeed_dataIntegrity 失败），把真实回归淹没在噪声里。
                //
                // 为什么这样改**仍然能捕获真问题**：断言的是「截止日期不早于数据集自身基准日」，
                // 即一条种子记录不得携带早于数据集编写时点的过去日期——这正是原断言要防的
                // 「复制粘贴残留旧日期 / 手误写成往年」这类脏数据；若有人把 deadline 写成
                // 2020-01-01、2025-09-30 等明显过期值，本断言仍会失败。
                // 同时补一条上界，防止误写成远期垃圾值（如 2099 年）。
                assertThat(j.deadline())
                        .as("种子截止日期早于数据集基准日 %s：%s", SEED_EPOCH, j.deadline())
                        .isAfterOrEqualTo(SEED_EPOCH);
                assertThat(j.deadline())
                        .as("种子截止日期超出合理招聘季范围（基准日 + 3 年）：%s", j.deadline())
                        .isBeforeOrEqualTo(SEED_EPOCH.plusYears(3));
            }
        }
    }
}
