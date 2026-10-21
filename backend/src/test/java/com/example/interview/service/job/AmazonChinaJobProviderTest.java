package com.example.interview.service.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 亚马逊中国数据源解析测试（v1.81.0）
 *
 * <p>用从线上接口抓下来的**真实响应结构**（字段名与层级一致），不打真实网络。
 */
class AmazonChinaJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AmazonChinaJobProvider provider() {
        return new AmazonChinaJobProvider(MAPPER);
    }

    private static final String REAL_RESPONSE = """
            {"hits":241,"jobs":[
              {"id_icims":"10583501",
               "title":"Security Engineer II, AWS Vulnerability Management",
               "company_name":"Amazon.com Services LLC",
               "location":"CN, 31, Shanghai","city":"Shanghai","country_code":"CHN",
               "job_category":"Systems, Quality, & Security Engineering",
               "job_family":"Systems Security Engineering",
               "posted_date":"October 19, 2026",
               "job_path":"/en/jobs/10583501/security-engineer-ii",
               "description":"At Amazon, Security is our highest priority.",
               "basic_qualifications":"- 3+ years of programming",
               "preferred_qualifications":"- threat modeling experience",
               "job_schedule_type":"full-time"},
              {"id_icims":"10583502","title":"Data Engineer","company_name":"Amazon (Shanghai) Co.",
               "location":"CN, 31, Shanghai","job_path":"/en/jobs/10583502/data-engineer"},
              {"title":"没有 id 的脏数据","location":"CN, 11, Beijing"}
            ]}
            """;

    @Test
    @DisplayName("解析真实响应：字段映射正确，缺 id 的条目被丢弃")
    void parsesRealResponse() throws Exception {
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(MAPPER.readTree(REAL_RESPONSE));

        // 第三条没有 id_icims，无法幂等 upsert，必须丢弃
        assertThat(jobs).hasSize(2);

        JobPlatformAdapter.JobDto first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("amz-10583501");
        assertThat(first.title()).contains("Security Engineer");
        assertThat(first.companyName()).isEqualTo("Amazon.com Services LLC");
        // 地点从 "CN, 31, Shanghai" 清洗为城市名
        assertThat(first.location()).isEqualTo("Shanghai");
        assertThat(first.applyUrl()).isEqualTo("https://www.amazon.jobs/en/jobs/10583501/security-engineer-ii");
        assertThat(first.description()).contains("highest priority");
        assertThat(first.requirements()).contains("3+ years").contains("threat modeling");
        assertThat(first.tags()).contains("Systems");
        assertThat(first.recruitType()).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("不编造：薪资 / 学历 / 截止日期 / 中文行业与岗位类型 一律留空")
    void doesNotFabricateMissingFields() throws Exception {
        JobPlatformAdapter.JobDto first =
                provider().parse(MAPPER.readTree(REAL_RESPONSE)).get(0);

        assertThat(first.salary()).isNull();
        assertThat(first.degree()).isNull();
        assertThat(first.deadline()).isNull();
        assertThat(first.experience()).isNull();
        // 英文 job_category 与系统中文 jobType 不同源，不硬映射
        assertThat(first.jobType()).isNull();
        assertThat(first.industry()).isNull();
    }

    @Test
    @DisplayName("地点清洗：多级逗号取最后一段，空值安全")
    void cleansLocation() {
        assertThat(AmazonChinaJobProvider.cleanLocation("CN, 31, Shanghai")).isEqualTo("Shanghai");
        assertThat(AmazonChinaJobProvider.cleanLocation("CN, 11, Beijing")).isEqualTo("Beijing");
        assertThat(AmazonChinaJobProvider.cleanLocation("Shanghai")).isEqualTo("Shanghai");
        assertThat(AmazonChinaJobProvider.cleanLocation(null)).isNull();
        assertThat(AmazonChinaJobProvider.cleanLocation("  ")).isNull();
        assertThat(AmazonChinaJobProvider.cleanLocation("CN, 31, ")).isNull();
    }

    @Test
    @DisplayName("只有一段要求时不拼出多余分隔符")
    void joinsQualificationsSafely() throws Exception {
        JobPlatformAdapter.JobDto second =
                provider().parse(MAPPER.readTree(REAL_RESPONSE)).get(1);
        // 第二条既无 description 也无 qualifications
        assertThat(second.requirements()).isNull();
    }

    @Test
    @DisplayName("结构异常时返回空列表，不抛异常")
    void toleratesMalformedStructure() throws Exception {
        assertThat(provider().parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"jobs\":\"oops\"}"))).isEmpty();
        assertThat(provider().parse(null)).isEmpty();
    }

    @Test
    @DisplayName("是国内源：岗位地点在中国，不进「海外远程」分栏")
    void isDomesticSource() {
        assertThat(provider().overseas()).isFalse();
        assertThat(provider().platform()).isEqualTo("亚马逊中国");
    }

    // ─────────────────── P1-6：偏移轮转（此前没有 offset，只取前 50 条） ───────────────────

    /** 记录 URL 并返回固定响应（只覆写唯一触网的方法，其余走真实代码） */
    private static final class RecordingProvider extends AmazonChinaJobProvider {
        private final List<String> urls = new java.util.ArrayList<>();
        private final String body;

        RecordingProvider(String body) {
            super(MAPPER);
            this.body = body;
        }

        @Override
        protected String fetchRaw(String url) {
            urls.add(url);
            return body;
        }
    }

    private static String pageBody(long hits) {
        return "{\"hits\":" + hits + ",\"jobs\":[{\"id_icims\":\"1\",\"title\":\"Engineer\"}]}";
    }

    @Test
    @DisplayName("P1-6：页数上限由 hits 推导（236 → 5 页；缺省回退 10）")
    void maxPageDerivedFromHits() {
        assertThat(AmazonChinaJobProvider.maxPageOf(236)).isEqualTo(5);
        assertThat(AmazonChinaJobProvider.maxPageOf(250)).isEqualTo(5);
        assertThat(AmazonChinaJobProvider.maxPageOf(251)).isEqualTo(6);
        assertThat(AmazonChinaJobProvider.maxPageOf(1)).isEqualTo(1);
        assertThat(AmazonChinaJobProvider.maxPageOf(0)).isEqualTo(10);
    }

    @Test
    @DisplayName("P1-6：URL 携带条目偏移（步长 50），而不是页码")
    void buildUrlCarriesOffset() {
        assertThat(AmazonChinaJobProvider.buildUrl(0)).contains("offset=0").contains("result_limit=50");
        assertThat(AmazonChinaJobProvider.buildUrl(100)).contains("offset=100");
    }

    @Test
    @DisplayName("P1-6：偏移 0→50→100→150→200 轮转，末页后回绕到 0")
    void rotatesOffsetAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(pageBody(236)); // 5 页

        for (int round = 0; round < 6; round++) {
            provider.fetch();
        }

        assertThat(provider.urls).hasSize(6);
        assertThat(provider.urls.get(0)).contains("offset=0");
        assertThat(provider.urls.get(1)).contains("offset=50");
        assertThat(provider.urls.get(2)).contains("offset=100");
        assertThat(provider.urls.get(3)).contains("offset=150");
        assertThat(provider.urls.get(4)).contains("offset=200");
        assertThat(provider.urls.get(5)).as("末页之后回绕到 offset=0").contains("offset=0");
    }

    @Test
    @DisplayName("P1-6：pages-per-refresh=N 时一轮连抓 N 个偏移")
    void fetchesMultiplePagesPerRefresh() {
        RecordingProvider provider = new RecordingProvider(pageBody(236)); // 5 页
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "pagesPerRefresh", 2);

        provider.fetch(); // 0, 50
        provider.fetch(); // 100, 150

        assertThat(provider.urls).hasSize(4);
        assertThat(provider.urls.get(0)).contains("offset=0");
        assertThat(provider.urls.get(1)).contains("offset=50");
        assertThat(provider.urls.get(2)).contains("offset=100");
        assertThat(provider.urls.get(3)).contains("offset=150");
    }

    @Test
    @DisplayName("P1-6：响应没有 hits 时沿用保守上限")
    void missingHitsKeepsFallbackCeiling() {
        RecordingProvider provider = new RecordingProvider("{\"jobs\":[]}");

        provider.fetch();

        assertThat(provider.maxPage()).isEqualTo(10);
    }
}
