package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新增内置种子数据源的一致性测试（v1.37.0）
 *
 * <p>行业精选 / 服务精选是纯数据类，逻辑极少，但**数据本身容易写错**：
 * externalId 重复会让幂等 upsert 互相覆盖（岗位凭空消失）、字段超长会在写库时
 * 触发 DataIntegrityViolation 让整批 upsert 回滚、recruitType 写成非法值会让
 * 招聘广场的 Tab 角标与筛选静默对不上。这些都不是编译期能发现的问题，
 * 因此用测试把「数据契约」固化下来。
 */
@DisplayName("内置种子数据源一致性测试")
class SeedJobProviderTest {

    /** recruit_type 列的合法枚举（与 JobAgentService / 前端 Tab 对齐） */
    private static final List<String> RECRUIT_TYPES =
            List.of("AUTUMN", "SPRING", "SOCIAL", "INTERN", "TARGETED");

    @Test
    @DisplayName("行业精选：数据量达标、字段完整、externalId 唯一、列长与枚举合法")
    void industrySeed_isConsistent() {
        assertSeedConsistent(new SeedIndustryJobProvider(), "行业精选", 45);
    }

    @Test
    @DisplayName("服务精选：数据量达标，且覆盖金融/政务/法律/教育等此前的空位行业")
    void serviceSeed_isConsistent() {
        var provider = new SeedServiceJobProvider();
        assertSeedConsistent(provider, "服务精选", 38);

        var jobs = provider.fetch();
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::industry)
                .contains("金融", "政府", "法律", "教育", "传媒", "人力资源");
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::recruitType)
                .contains("TARGETED", "SOCIAL", "AUTUMN", "INTERN");
    }

    @Test
    @DisplayName("行业精选：覆盖实体产业方向（制造/能源/建筑/医疗/物流/农业）")
    void industrySeed_coversNonInternetSectors() {
        var jobs = new SeedIndustryJobProvider().fetch();
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::industry)
                .contains("制造", "能源", "建筑", "医疗", "物流", "农业", "零售", "交通");
        // 此前广场里几乎只有互联网大厂，实习岗也稀缺
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::recruitType).contains("INTERN");
    }

    @Test
    @DisplayName("两个种子源之间 externalId 不冲突（跨源撞 key 会互相覆盖）")
    void seedProviders_doNotShareExternalIds() {
        var industry = new SeedIndustryJobProvider().fetch();
        var service = new SeedServiceJobProvider().fetch();
        var all = new java.util.ArrayList<JobPlatformAdapter.JobDto>(industry);
        all.addAll(service);
        // 同一 platform 内唯一 + 跨 platform 也不复用 ID（便于人工排查与迁移）
        assertThat(all).extracting(JobPlatformAdapter.JobDto::externalId).doesNotHaveDuplicates();
    }

    /** 逐条校验数据契约 */
    private static void assertSeedConsistent(JobPlatformAdapter adapter, String platform, int minCount) {
        var jobs = adapter.fetch();

        assertThat(adapter.platform()).isEqualTo(platform);
        assertThat(jobs.size()).isGreaterThanOrEqualTo(minCount);
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::externalId).doesNotHaveDuplicates();

        for (var job : jobs) {
            String id = job.externalId();
            // external_id 列上限 128
            assertThat(id).startsWith("seed-");
            assertThat(id.length()).isLessThanOrEqualTo(128);
            // title / company_name 列上限 200 且非空（NOT NULL 约束）
            assertThat(job.title()).isNotBlank();
            assertThat(job.title().length()).isLessThanOrEqualTo(200);
            assertThat(job.companyName()).isNotBlank();
            assertThat(job.companyName().length()).isLessThanOrEqualTo(200);
            // 申请入口必须是绝对 http(s) 地址（apply_url 列上限 500）。
            //
            // 这里刻意不强制 https：少量官方渠道（如 job.people.cn）目前仅提供 http 入口，
            // 而该链接是作为外链导航打开的，不受混合内容限制，改成 https 反而会 404。
            // 强制 https 会让测试逼着数据去讨好断言，方向反了。
            assertThat(job.applyUrl()).matches("^https?://.+");
            assertThat(job.applyUrl().length()).isLessThanOrEqualTo(500);
            assertThat(job.recruitType()).isIn(RECRUIT_TYPES);
            // 可选字段的列长上限
            assertThat(len(job.location())).isLessThanOrEqualTo(100);
            assertThat(len(job.salary())).isLessThanOrEqualTo(100);
            assertThat(len(job.tags())).isLessThanOrEqualTo(500);
            // 描述/要求即使缺失也不应为空白串（空白串会在前端渲染出空段落）
            if (job.description() != null) {
                assertThat(job.description()).isNotBlank();
            }
            if (job.requirements() != null) {
                assertThat(job.requirements()).isNotBlank();
            }
        }
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
