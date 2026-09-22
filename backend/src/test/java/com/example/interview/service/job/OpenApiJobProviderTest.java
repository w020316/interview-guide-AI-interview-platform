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

    // ── 开关 ──

    @Test
    @DisplayName("公开数据源受 app.job-agent.open-api-enabled 开关控制")
    void publicProviders_respectSwitch() {
        assertThat(new RemoteOkJobProvider(mapper, properties).isEnabled()).isTrue();
        assertThat(new RemotiveJobProvider(mapper, properties).isEnabled()).isTrue();
        assertThat(new ArbeitnowJobProvider(mapper, properties).isEnabled()).isTrue();

        JobAgentProperties off = new JobAgentProperties();
        off.setOpenApiEnabled(false);
        assertThat(new RemoteOkJobProvider(mapper, off).isEnabled()).isFalse();
        assertThat(new RemotiveJobProvider(mapper, off).isEnabled()).isFalse();
        assertThat(new ArbeitnowJobProvider(mapper, off).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("各公开数据源的展示名与 endpoint 稳定（用于按来源筛选与后台视图）")
    void publicProviders_platformAndEndpointStable() {
        assertThat(new RemoteOkJobProvider(mapper, properties).platform()).isEqualTo("RemoteOK 全球远程");
        assertThat(new RemotiveJobProvider(mapper, properties).platform()).isEqualTo("Remotive 全球远程");
        assertThat(new ArbeitnowJobProvider(mapper, properties).platform()).isEqualTo("Arbeitnow 欧洲");
        // 展示名有 50 字符列上限
        for (JobPlatformAdapter a : List.of(
                new RemoteOkJobProvider(mapper, properties),
                new RemotiveJobProvider(mapper, properties),
                new ArbeitnowJobProvider(mapper, properties))) {
            assertThat(a.platform().length()).isLessThanOrEqualTo(50);
        }
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
