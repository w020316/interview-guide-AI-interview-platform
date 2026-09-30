package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link V2exJobsJobProvider} 单元测试（v1.45.0）
 *
 * <p>这是招聘广场**第一个国内实时源**：此前国内 327 条全部来自硬编码种子。
 * 测试重点不是「能不能解析」，而是几条容易出错、且会直接影响用户看到的语义：
 * 求职帖必须被过滤、公司名抽不到时不能编造、招聘类型必须落在平台既有枚举内。
 */
class V2exJobsJobProviderTest {

    private ObjectMapper mapper;
    private JobAgentProperties properties;
    private V2exJobsJobProvider provider;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        properties = new JobAgentProperties();
        provider = new V2exJobsJobProvider(mapper, properties);
    }

    private List<JobPlatformAdapter.JobDto> parse(String json) throws Exception {
        return provider.parse(mapper.readTree(json));
    }

    @Test
    @DisplayName("招聘帖解析为国内岗位，字段落到既有枚举内")
    void hiringPost_parsedAsDomesticJob() throws Exception {
        String json = """
                [{
                  "id": 900001,
                  "title": "[北京] 某某科技 招聘 高级 Java 后端 20k-40k",
                  "url": "https://www.v2ex.com/t/900001",
                  "content": "<p>负责后端开发</p>",
                  "content_rendered": "<p>负责后端开发</p>",
                  "created": 1790000000
                }]
                """;

        List<JobPlatformAdapter.JobDto> jobs = parse(json);

        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        assertThat(job.externalId()).isEqualTo("v2ex-900001");
        assertThat(job.title()).contains("高级 Java 后端");
        assertThat(job.companyName()).isEqualTo("某某科技");
        assertThat(job.location()).isEqualTo("北京");
        assertThat(job.salary()).isEqualTo("20k-40k");
        assertThat(job.jobType()).isEqualTo("技术");
        assertThat(job.recruitType()).isEqualTo("SOCIAL");
        assertThat(job.applyUrl()).isEqualTo("https://www.v2ex.com/t/900001");
        assertThat(job.description()).contains("负责后端开发");
        assertThat(job.deadline()).isNotNull();
    }

    @Test
    @DisplayName("求职帖被过滤：酷工作节点里混有求职帖，不能当成岗位")
    void seekerPost_filtered() throws Exception {
        String json = """
                [{"id":1,"title":"[求职] 三年 Java 求内推 北京","url":"https://www.v2ex.com/t/1","created":1790000000},
                 {"id":2,"title":"找工作，前端，可远程","url":"https://www.v2ex.com/t/2","created":1790000000},
                 {"id":3,"title":"[上海] 招聘 前端工程师","url":"https://www.v2ex.com/t/3","created":1790000000}]
                """;

        List<JobPlatformAdapter.JobDto> jobs = parse(json);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("v2ex-3");
    }

    @Test
    @DisplayName("公司名抽不到时回退为「见岗位详情」，不编造")
    void companyFallback_notFabricated() throws Exception {
        String json = """
                [{"id":7,"title":"招聘 资深 Rust 工程师（远程）","url":"https://www.v2ex.com/t/7","created":1790000000}]
                """;

        var job = parse(json).get(0);

        assertThat(job.companyName()).isEqualTo("见岗位详情");
        assertThat(job.location()).isEqualTo("远程");
    }

    @Test
    @DisplayName("实习/校招识别为对应招聘类型（必须落在平台既有枚举内）")
    void recruitType_matchesExistingEnum() throws Exception {
        String json = """
                [{"id":11,"title":"[深圳] 招聘 后端开发实习生","url":"https://www.v2ex.com/t/11","created":1790000000},
                 {"id":12,"title":"[杭州] 2027 校招 算法工程师","url":"https://www.v2ex.com/t/12","created":1790000000}]
                """;

        List<JobPlatformAdapter.JobDto> jobs = parse(json);

        assertThat(jobs).extracting(JobPlatformAdapter.JobDto::recruitType)
                .containsExactly("INTERN", "AUTUMN");
        // 与 RecruitType 枚举对齐，前端筛选才不会落空（fromCode 无法识别时返回 null）
        for (var j : jobs) {
            assertThat(RecruitType.fromCode(j.recruitType())).isNotNull();
        }
    }

    @Test
    @DisplayName("缺 id/title 的条目被跳过；非数组根节点返回空列表")
    void malformedEntries_skipped() throws Exception {
        String json = """
                [{"title":"招聘 前端","url":"https://www.v2ex.com/t/1"},
                 {"id":2,"url":"https://www.v2ex.com/t/2"},
                 {"id":3,"title":"[广州] 招聘 测试工程师","url":"https://www.v2ex.com/t/3","created":1790000000}]
                """;

        assertThat(parse(json)).hasSize(1);
        assertThat(parse("{}")).isEmpty();
        assertThat(parse("null")).isEmpty();
    }

    @Test
    @DisplayName("是「国内」源：overseas()=false，进国内分栏")
    void isDomesticSource() {
        assertThat(provider.overseas()).isFalse();
        assertThat(provider.platform()).isEqualTo("V2EX 酷工作");
    }

    @Test
    @DisplayName("配额大于海外源的 25：国内岗位稀缺，不该再被小配额卡住")
    void quotaLargerThanOverseasDefault() {
        assertThat(provider.maxItemsPerRefresh()).isGreaterThan(25);
    }
}
