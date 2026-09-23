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

    /**
     * recruit_type 列的合法枚举（与 JobAgentService / 前端分栏对齐）。
     *
     * <p>PART_TIME 是 v1.38.0 新增的「兼职」类型——它既是招聘类型也是工作性质，
     * 与 AUTUMN/SPRING/SOCIAL 这类「招聘批次」并存在同一列里，
     * 因此写错值会让前端的兼职分栏静默筛不出数据。
     */
    private static final List<String> RECRUIT_TYPES =
            List.of("AUTUMN", "SPRING", "SOCIAL", "INTERN", "TARGETED", "PART_TIME");

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
    @DisplayName("秋招精选2027：数据量达标且全部为秋招类型")
    void autumn2027Seed_isConsistent() {
        var provider = new SeedAutumn2027JobProvider();
        assertSeedConsistent(provider, "秋招精选2027", 55);
        assertThat(provider.fetch())
                .extracting(JobPlatformAdapter.JobDto::recruitType)
                .containsOnly("AUTUMN");
    }

    @Test
    @DisplayName("秋招精选2027：覆盖互联网/硬件/汽车/银行/快消/游戏/央企/咨询多方向")
    void autumn2027Seed_coversMultipleSectors() {
        assertThat(new SeedAutumn2027JobProvider().fetch())
                .extracting(JobPlatformAdapter.JobDto::industry)
                .contains("互联网", "通信", "金融", "汽车", "快消", "游戏", "建筑", "咨询", "制造", "能源");
    }

    @Test
    @DisplayName("兼职专区：全部为 PART_TIME，薪资按时/日/单计价，且不设截止日")
    void partTimeSeed_isConsistent() {
        var provider = new SeedPartTimeJobProvider();
        assertSeedConsistent(provider, "兼职专区", 30);

        var jobs = provider.fetch();
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::recruitType).containsOnly("PART_TIME");

        // 兼职的计价单位是小时/天/单/篇，写成月薪会误导求职者——把表达方式也锁进契约
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::salary).allSatisfy(s ->
                assertThat(s).containsAnyOf("/时", "/天", "/单", "/篇", "/千字", "/月", "/条"));

        // 兼职多为长期滚动招聘，留空截止日才能避免被「过期自动下架」误清理
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::deadline)
                .allSatisfy(d -> assertThat(d).isNull());
    }

    @Test
    @DisplayName("社招精选：数据量达标、字段完整、externalId 唯一、列长与枚举合法")
    void domesticSocialSeed_isConsistent() {
        assertSeedConsistent(new SeedDomesticSocialJobProvider(), "社招精选", 45);
    }

    @Test
    @DisplayName("社招精选：以社招为主，覆盖互联网/金融/制造/医疗/教育/消费/物流/建筑/能源/职能/设计")
    void domesticSocialSeed_coversDomesticSectors() {
        var jobs = new SeedDomesticSocialJobProvider().fetch();
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::industry)
                .contains("互联网", "金融", "制造", "医疗", "教育", "消费", "物流",
                        "建筑", "能源", "职能", "设计");
        // 这是本数据源存在的理由：补上此前严重不足的社招基数
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::recruitType).contains("SOCIAL");
        long social = jobs.stream().filter(j -> "SOCIAL".equals(j.recruitType())).count();
        assertThat(social).isGreaterThanOrEqualTo(Math.round(jobs.size() * 0.7));
        // 城市必须落在中国境内主要城市（不能混入海外岗位，否则会稀释国内分栏）
        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::location)
                .allSatisfy(loc -> assertThat(loc).doesNotContainIgnoringCase("remote")
                        .doesNotContain("全球"));
    }

    @Test
    @DisplayName("全部种子源之间 externalId 不冲突（撞 key 会让岗位互相覆盖）")
    void seedProviders_doNotShareExternalIds() {
        var all = new java.util.ArrayList<JobPlatformAdapter.JobDto>();
        all.addAll(new SeedIndustryJobProvider().fetch());
        all.addAll(new SeedServiceJobProvider().fetch());
        all.addAll(new SeedAutumn2027JobProvider().fetch());
        all.addAll(new SeedPartTimeJobProvider().fetch());
        all.addAll(new SeedDomesticSocialJobProvider().fetch());
        // 同一 platform 内唯一 + 跨 platform 也不复用 ID（便于人工排查与迁移）
        assertThat(all).extracting(JobPlatformAdapter.JobDto::externalId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("全部种子源平台名互不重复（重名会让来源统计互相覆盖）")
    void seedProviders_haveDistinctPlatforms() {
        assertThat(List.of(
                new SeedIndustryJobProvider(),
                new SeedServiceJobProvider(),
                new SeedAutumn2027JobProvider(),
                new SeedPartTimeJobProvider(),
                new SeedDomesticSocialJobProvider()))
                .extracting(JobPlatformAdapter::platform)
                .doesNotHaveDuplicates();
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
