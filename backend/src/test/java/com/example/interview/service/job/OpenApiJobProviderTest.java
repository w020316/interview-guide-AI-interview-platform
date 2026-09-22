package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 免费公开招聘数据源解析测试（v1.37.0）
 *
 * <p>只测 {@code parse(JsonNode)}（纯函数），**不发真实网络请求**——
 * 上游接口的可用性由运行时日志与监控负责，单测的价值在于锁定字段映射规则：
 * 上游改字段名、加包装层、返回脏数据时能在 CI 拦下来，而不是等线上岗位变少才发现。
 *
 * <p>测试类与实现同包，因此可直接调用 {@code protected} 的 parse 与静态工具方法。
 */
@DisplayName("公开招聘数据源解析测试")
class OpenApiJobProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobAgentProperties properties = new JobAgentProperties();

    // ── RemoteOK ──

    @Test
    @DisplayName("RemoteOK: 解析岗位数组，跳过首元素 legal 声明，薪资/远程地点/HTML 清洗正确")
    void remoteOk_parsesJobsAndSkipsLegalNotice() throws Exception {
        String json = """
                [
                  {"legal":"RemoteOK API terms of service"},
                  {"id":"123","slug":"acme-senior-java","position":"Senior Java Engineer",
                   "company":"Acme","location":"Worldwide","tags":["java","spring"],
                   "salary_min":120000,"salary_max":180000,"date":"2026-09-01T10:00:00Z",
                   "url":"https://remoteok.com/remote-jobs/acme-senior-java",
                   "description":"<p>Build <b>things</b> at scale</p>"}
                ]
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new RemoteOkJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.externalId()).isEqualTo("rok-123");
        assertThat(job.title()).isEqualTo("Senior Java Engineer");
        assertThat(job.companyName()).isEqualTo("Acme");
        assertThat(job.industry()).isEqualTo("互联网");
        // Worldwide 归一化，避免地点筛选被英文值污染
        assertThat(job.location()).isEqualTo("全球远程");
        assertThat(job.salary()).isEqualTo("$120k - $180k / 年");
        assertThat(job.recruitType()).isEqualTo("SOCIAL");
        assertThat(job.tags()).isEqualTo("java,spring");
        assertThat(job.description()).isEqualTo("Build things at scale");
        // 发帖日 + 60 天为截止日（上游不提供 deadline，用于临期排序与自动下架）
        assertThat(job.deadline()).isEqualTo(LocalDate.of(2026, 9, 1).plusDays(60));
    }

    @Test
    @DisplayName("RemoteOK: 缺 position/company 的脏数据被丢弃；无 url 时用 slug 拼申请地址")
    void remoteOk_dropsDirtyAndFallsBackToSlug() throws Exception {
        String json = """
                [
                  {"id":"1","position":"No Company","tags":[]},
                  {"id":"2","company":"No Title"},
                  {"id":"3","slug":"fallback-slug","position":"Dev","company":"Co","tags":[]}
                ]
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new RemoteOkJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("rok-3");
        assertThat(jobs.get(0).applyUrl()).isEqualTo("https://remoteok.com/remote-jobs/fallback-slug");
        assertThat(jobs.get(0).salary()).isNull();
    }

    // ── Remotive ──

    @Test
    @DisplayName("Remotive: 解析 jobs 包装层，category 映射为中文行业与职位类型")
    void remotive_parsesWrappedJobs() throws Exception {
        String json = """
                {"job-count":1,"jobs":[
                  {"id":9001,"title":"Marketing Manager","company_name":"Globex",
                   "category":"Marketing","tags":["seo"],
                   "job_type":"full_time","publication_date":"2026-09-10T08:00:00",
                   "candidate_required_location":"Worldwide","salary":"$90k",
                   "url":"https://remotive.com/jobs/9001","description":"<div>Own growth</div>"}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new RemotiveJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.externalId()).isEqualTo("rmt-9001");
        assertThat(job.industry()).isEqualTo("传媒");
        assertThat(job.jobType()).isEqualTo("市场");
        assertThat(job.location()).isEqualTo("全球远程");
        // job_type 合并进标签，便于前端展示与筛选
        assertThat(job.tags()).isEqualTo("seo,full_time");
        assertThat(job.applyUrl()).isEqualTo("https://remotive.com/jobs/9001");
        assertThat(job.description()).isEqualTo("Own growth");
    }

    @Test
    @DisplayName("Remotive: 非 Worldwide 的地点原样保留；缺 jobs 数组时返回空列表")
    void remotive_handlesRegionAndMissingArray() throws Exception {
        String json = """
                {"jobs":[{"id":1,"title":"Dev","company_name":"Co","category":"Software Development",
                  "candidate_required_location":"Europe","url":"https://x/y"}]}
                """;
        var jobs = new RemotiveJobProvider(mapper, properties).parse(mapper.readTree(json));
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).location()).isEqualTo("Europe");
        assertThat(jobs.get(0).industry()).isEqualTo("互联网");

        assertThat(new RemotiveJobProvider(mapper, properties).parse(mapper.readTree("{}"))).isEmpty();
    }

    // ── Arbeitnow ──

    @Test
    @DisplayName("Arbeitnow: 解析 data 包装层，remote 标记并入地点与标签，时间戳转日期")
    void arbeitnow_parsesRemoteFlagAndTimestamp() throws Exception {
        String json = """
                {"data":[
                  {"slug":"berlin-dev","title":"Backend Developer","company_name":"Initech",
                   "description":"<p>Node &amp; Go</p>","remote":true,
                   "url":"https://www.arbeitnow.com/view/berlin-dev",
                   "tags":["node"],"job_types":["full-time"],"location":"Berlin",
                   "created_at":1780000000}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new ArbeitnowJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.externalId()).isEqualTo("arb-berlin-dev");
        assertThat(job.location()).isEqualTo("Berlin（可远程）");
        assertThat(job.tags()).isEqualTo("node,full-time,远程");
        assertThat(job.description()).isEqualTo("Node & Go");
        assertThat(job.deadline()).isNotNull();
    }

    @Test
    @DisplayName("Arbeitnow: remote=false 不加远程标注；created_at 缺失时截止日为 null")
    void arbeitnow_handlesNonRemoteAndMissingTimestamp() throws Exception {
        String json = """
                {"data":[{"slug":"x","title":"Warehouse Helper","company_name":"Co","remote":false,
                  "url":"https://www.arbeitnow.com/view/x","tags":[],"job_types":[],"location":"Munich"}]}
                """;
        var jobs = new ArbeitnowJobProvider(mapper, properties).parse(mapper.readTree(json));
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).location()).isEqualTo("Munich");
        assertThat(jobs.get(0).deadline()).isNull();
        assertThat(jobs.get(0).tags()).isNull();
    }

    // ── Jobicy ──

    @Test
    @DisplayName("Jobicy: 解析 jobs，结构化行业/用工类型/职级与年薪区间映射正确")
    void jobicy_parsesStructuredFields() throws Exception {
        String json = """
                {"jobCount":1,"jobs":[
                  {"id":555,"url":"https://jobicy.com/jobs/555","jobSlug":"acme-data",
                   "jobTitle":"Senior Data Engineer","companyName":"Acme",
                   "jobIndustry":["Engineering"],"jobType":["full-time"],
                   "jobGeo":"Anywhere","jobLevel":"Senior",
                   "jobDescription":"<p>Build pipelines</p>",
                   "pubDate":"2026-09-05T00:00:00",
                   "annualSalaryMin":"120000","annualSalaryMax":"170000","salaryCurrency":"USD"}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new JobicyJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.externalId()).isEqualTo("jby-555");
        assertThat(job.title()).isEqualTo("Senior Data Engineer");
        assertThat(job.companyName()).isEqualTo("Acme");
        assertThat(job.industry()).isEqualTo("互联网");
        assertThat(job.jobType()).isEqualTo("技术");
        assertThat(job.location()).isEqualTo("全球远程");
        assertThat(job.salary()).isEqualTo("$120000 - $170000 / 年");
        assertThat(job.tags()).isEqualTo("full-time,Senior");
        assertThat(job.description()).isEqualTo("Build pipelines");
        assertThat(job.deadline()).isEqualTo(LocalDate.of(2026, 9, 5).plusDays(60));
    }

    @Test
    @DisplayName("Jobicy: 缺 title/company 丢弃；id 缺失时用 jobSlug；非 Anywhere 地点标注远程")
    void jobicy_fallbacksAndDirtyData() throws Exception {
        String json = """
                {"jobs":[
                  {"id":1,"companyName":"NoTitle"},
                  {"id":2,"jobTitle":"NoCompany"},
                  {"jobSlug":"slug-only","jobTitle":"Dev","companyName":"Co","jobGeo":"Germany","jobType":[]}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new JobicyJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("jby-slug-only");
        assertThat(jobs.get(0).location()).isEqualTo("Germany（远程）");
        assertThat(jobs.get(0).salary()).isNull();
        assertThat(jobs.get(0).tags()).isNull();
    }

    // ── Himalayas ──

    @Test
    @DisplayName("Himalayas: guid 末段作短 ID，expiryDate 优先作截止日")
    void himalayas_parsesGuidAndExpiry() throws Exception {
        String json = """
                {"jobs":[
                  {"title":"Staff Backend Engineer","companyName":"Initech",
                   "excerpt":"Build systems","description":"<p>Go and Kubernetes</p>",
                   "pubDate":1780000000,"expiryDate":1789000000,
                   "applicationLink":"https://himalayas.app/companies/initech/jobs/staff-backend",
                   "guid":"https://himalayas.app/companies/initech/jobs/staff-backend",
                   "locationRestrictions":["Worldwide"],"categories":["Engineering"],
                   "seniority":["Staff"],"employmentType":"Full Time",
                   "minSalary":150000,"maxSalary":200000,"currency":"USD"}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new HimalayasJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        // guid 是完整 URL，直接入库会超 128 字符列上限，因此取末段并拼公司名
        assertThat(job.externalId()).isEqualTo("hml-Initech-staff-backend");
        assertThat(job.location()).isEqualTo("全球远程");
        assertThat(job.salary()).isEqualTo("$150000 - $200000 / 年");
        assertThat(job.tags()).isEqualTo("Full Time,Staff,Engineering");
        assertThat(job.description()).isEqualTo("Go and Kubernetes");
        assertThat(job.jobType()).isEqualTo("技术");
        // 唯一提供到期时间的海外源：不能再按发帖日推算
        assertThat(job.deadline()).isEqualTo(AbstractOpenApiJobProvider.fromEpochSecond(1789000000));
    }

    @Test
    @DisplayName("Himalayas: 无 expiryDate 时按发帖日 +60 天；缺链接时用标题兜底作 ID")
    void himalayas_fallbacks() throws Exception {
        String json = """
                {"jobs":[
                  {"title":"Designer","companyName":"Co","pubDate":1780000000,"categories":["Design"]}
                ]}
                """;

        List<JobPlatformAdapter.JobDto> jobs =
                new HimalayasJobProvider(mapper, properties).parse(mapper.readTree(json));

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("hml-Co-Designer");
        assertThat(jobs.get(0).jobType()).isEqualTo("设计");
        assertThat(jobs.get(0).deadline())
                .isEqualTo(AbstractOpenApiJobProvider.fromEpochSecond(1780000000).plusDays(60));
    }

    // ── 开关 ──

    /** 全部公开 API 数据源（新增源时只改这一处，其余断言自动覆盖） */
    private List<JobPlatformAdapter> allPublicProviders(JobAgentProperties props) {
        return List.of(
                new RemoteOkJobProvider(mapper, props),
                new RemotiveJobProvider(mapper, props),
                new ArbeitnowJobProvider(mapper, props),
                new JobicyJobProvider(mapper, props),
                new HimalayasJobProvider(mapper, props));
    }

    @Test
    @DisplayName("公开数据源受 app.job-agent.open-api-enabled 开关控制")
    void publicProviders_respectSwitch() {
        JobAgentProperties off = new JobAgentProperties();
        off.setOpenApiEnabled(false);

        for (JobPlatformAdapter a : allPublicProviders(properties)) {
            assertThat(a.isEnabled()).as("%s 默认应启用", a.platform()).isTrue();
        }
        for (JobPlatformAdapter a : allPublicProviders(off)) {
            assertThat(a.isEnabled()).as("%s 关开关后应停用", a.platform()).isFalse();
        }
    }

    @Test
    @DisplayName("公开数据源统一归为海外源，并按 6 小时节流（不按小时打扰上游）")
    void publicProviders_areOverseasAndThrottled() {
        for (JobPlatformAdapter a : allPublicProviders(properties)) {
            assertThat(a.overseas()).as("%s 应归入海外远程分栏", a.platform()).isTrue();
            assertThat(a.minRefreshIntervalMs()).as("%s 应有刷新冷却", a.platform())
                    .isEqualTo(6 * 3600 * 1000L);
        }
        // 内置种子数据源每轮都刷新（幂等 upsert，开销极低），不参与节流
        assertThat(new SeedAutumn2027JobProvider().overseas()).isFalse();
        assertThat(new SeedAutumn2027JobProvider().minRefreshIntervalMs()).isZero();
        assertThat(new SeedPartTimeJobProvider().overseas()).isFalse();
        assertThat(new SeedPartTimeJobProvider().minRefreshIntervalMs()).isZero();
    }

    @Test
    @DisplayName("各公开数据源展示名稳定且不超列长上限（按来源筛选与后台视图依赖它）")
    void publicProviders_platformStable() {
        assertThat(new RemoteOkJobProvider(mapper, properties).platform()).isEqualTo("RemoteOK 全球远程");
        assertThat(new RemotiveJobProvider(mapper, properties).platform()).isEqualTo("Remotive 全球远程");
        assertThat(new ArbeitnowJobProvider(mapper, properties).platform()).isEqualTo("Arbeitnow 欧洲");
        assertThat(new JobicyJobProvider(mapper, properties).platform()).isEqualTo("Jobicy 全球远程");
        assertThat(new HimalayasJobProvider(mapper, properties).platform()).isEqualTo("Himalayas 全球远程");

        // platform 列上限 50；同时不能重名（重名会让按来源分组的统计互相覆盖）
        for (JobPlatformAdapter a : allPublicProviders(properties)) {
            assertThat(a.platform().length()).isLessThanOrEqualTo(50);
        }
    }

    @Test
    @DisplayName("公开数据源平台名互不重复（重名会让数据源统计互相覆盖）")
    void publicProviders_haveDistinctPlatforms() {
        assertThat(allPublicProviders(properties))
                .extracting(JobPlatformAdapter::platform)
                .doesNotHaveDuplicates();
    }

    // ── 基类工具 ──

    @Test
    @DisplayName("plainText: 去 script/style 与标签、还原实体、压缩空白、按上限截断")
    void plainText_cleansHtml() {
        assertThat(AbstractOpenApiJobProvider.plainText(
                "<script>evil()</script><style>a{}</style><p>Hello&nbsp;<b>World</b></p>", 100))
                .isEqualTo("Hello World");
        assertThat(AbstractOpenApiJobProvider.plainText("&lt;tag&gt; &amp; &quot;q&quot;", 100))
                .isEqualTo("<tag> & \"q\"");
        assertThat(AbstractOpenApiJobProvider.plainText("   ", 100)).isNull();
        assertThat(AbstractOpenApiJobProvider.plainText(null, 100)).isNull();
        assertThat(AbstractOpenApiJobProvider.plainText("<p>abcdef</p>", 3)).isEqualTo("abc");
    }

    @Test
    @DisplayName("clip: 按列长度上限截断，null 安全")
    void clip_truncatesAtLimit() {
        assertThat(AbstractOpenApiJobProvider.clip("abc", 5)).isEqualTo("abc");
        assertThat(AbstractOpenApiJobProvider.clip("abcdef", 3)).isEqualTo("abc");
        assertThat(AbstractOpenApiJobProvider.clip(null, 3)).isNull();
    }

    @Test
    @DisplayName("parseDatePrefix: 兼容纯日期与 ISO 时间戳；非法值返回 null")
    void parseDatePrefix_handlesBothFormats() {
        assertThat(AbstractOpenApiJobProvider.parseDatePrefix("2026-09-01")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(AbstractOpenApiJobProvider.parseDatePrefix("2026-09-01T10:20:30Z"))
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(AbstractOpenApiJobProvider.parseDatePrefix("not-a-date")).isNull();
        assertThat(AbstractOpenApiJobProvider.parseDatePrefix(null)).isNull();
        assertThat(AbstractOpenApiJobProvider.parseDatePrefix("  ")).isNull();
    }

    @Test
    @DisplayName("fromEpochSecond / deadlineFrom: 0 或 null 均安全降级")
    void timeHelpers_areNullSafe() {
        assertThat(AbstractOpenApiJobProvider.fromEpochSecond(0)).isNull();
        assertThat(AbstractOpenApiJobProvider.fromEpochSecond(-1)).isNull();
        assertThat(AbstractOpenApiJobProvider.fromEpochSecond(1780000000)).isNotNull();

        assertThat(AbstractOpenApiJobProvider.deadlineFrom(null, 60)).isNull();
        assertThat(AbstractOpenApiJobProvider.deadlineFrom(LocalDate.of(2026, 1, 1), 30))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("text / joinArray: 取首个非空字段；数组字段拼为逗号串；缺失返回 null")
    void fieldHelpers_pickFirstNonBlank() throws Exception {
        var node = mapper.readTree("""
                {"a":"","b":null,"c":"hit","arr":["x","y"],"empty":[]}
                """);
        assertThat(AbstractOpenApiJobProvider.text(node, "a", "b", "c")).isEqualTo("hit");
        assertThat(AbstractOpenApiJobProvider.text(node, "a", "b")).isNull();
        assertThat(AbstractOpenApiJobProvider.joinArray(node, "arr")).isEqualTo("x,y");
        // 空数组视为无标签，返回 null 而不是空串（避免写入无意义的空值）
        assertThat(AbstractOpenApiJobProvider.joinArray(node, "empty")).isNull();
        assertThat(AbstractOpenApiJobProvider.joinArray(node, "missing")).isNull();
    }
}
