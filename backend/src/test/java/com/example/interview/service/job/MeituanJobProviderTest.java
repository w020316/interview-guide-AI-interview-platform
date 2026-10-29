package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 美团招聘数据源解析测试（v1.87.0）
 *
 * <p>使用线上真实响应结构（字段名与层级一致），不打真实网络——
 * 与项目其他 OpenAPI 源测试同一做法：网络不可控，解析契约必须锁死。
 */
class MeituanJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MeituanJobProvider provider() {
        return new MeituanJobProvider(MAPPER);
    }

    /** 取自线上真实响应（2026-10-29 探测） */
    private static final String REAL_RESPONSE = """
            {"data":{"list":[
              {"jobUnionId":"4697320043","name":"AI全栈工程师","jobType":"3","jobSource":"1",
               "jobFamily":"技术类","jobFamilyGroup":"软件","workYear":null,
               "jobDuty":"岗位职责\\n1. 参与 AI 应用的全栈开发；\\n2. 负责 RAG 管线搭建；",
               "jobRequirement":"岗位要求\\n1. 本科及以上学历；\\n2. 掌握 Python / Java；",
               "firstPostTime":null,"refreshTime":1786960999000,"expiredTime":null,
               "cityList":[{"code":null,"name":"北京市"},{"code":null,"name":"上海市"},
                           {"code":null,"name":"成都市"},{"code":null,"name":"深圳市"}]},
              {"jobUnionId":"4697280304","name":"后端开发实习生","jobType":"1","jobSource":"2",
               "jobFamily":"运营类","jobFamilyGroup":"运营","workYear":null,
               "jobRequirement":"在校生优先","firstPostTime":null,"refreshTime":1786960997000,
               "cityList":[{"code":null,"name":"北京市"}]},
              {"name":"缺少 id 的脏数据","jobFamily":"技术类"}
            ],"page":{"pageNo":1,"pageSize":50,"totalPage":66,"totalCount":3282},"traceId":null},
             "status":1,"message":"成功"}
            """;

    @Test
    @DisplayName("解析真实响应：字段映射正确，缺 id 的脏数据被丢弃")
    void parsesRealResponse() throws Exception {
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(MAPPER.readTree(REAL_RESPONSE));

        assertThat(jobs).hasSize(2);

        JobPlatformAdapter.JobDto first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("mt-4697320043");
        assertThat(first.title()).isEqualTo("AI全栈工程师");
        assertThat(first.companyName()).isEqualTo("美团");
        assertThat(first.jobType()).isEqualTo("技术");
        assertThat(first.location()).isEqualTo("北京市、上海市、成都市");
        assertThat(first.applyUrl()).contains("zhaopin.meituan.com").contains("4697320043");
        assertThat(first.description()).contains("RAG");
        assertThat(first.requirements()).contains("本科及以上");
        assertThat(first.tags()).isEqualTo("技术类,软件");
    }

    @Test
    @DisplayName("不编造：薪资 / 学历 / 截止日期 / 行业 一律留空；workYear 为 null 如实透传")
    void doesNotFabricateMissingFields() throws Exception {
        JobPlatformAdapter.JobDto first = provider()
                .parse(MAPPER.readTree(REAL_RESPONSE)).get(0);

        assertThat(first.salary()).isNull();
        assertThat(first.degree()).isNull();
        assertThat(first.deadline()).isNull();
        assertThat(first.industry()).isNull();
        assertThat(first.experience()).isNull();
    }

    @Test
    @DisplayName("岗位类别只做词面等价映射，不等价的留空交给分类服务")
    void mapsOnlyEquivalentJobFamilies() {
        assertThat(MeituanJobProvider.jobTypeOf("技术类")).isEqualTo("技术");
        assertThat(MeituanJobProvider.jobTypeOf("产品类")).isEqualTo("产品");
        assertThat(MeituanJobProvider.jobTypeOf("运营类")).isEqualTo("运营");
        assertThat(MeituanJobProvider.jobTypeOf("职能类")).isEqualTo("职能");
        assertThat(MeituanJobProvider.jobTypeOf("金融类")).isEqualTo("金融");
        assertThat(MeituanJobProvider.jobTypeOf("市场营销类")).isEqualTo("市场");

        // 与任一系统类别都不等价 → null，不硬塞
        assertThat(MeituanJobProvider.jobTypeOf("零售类")).isNull();
        assertThat(MeituanJobProvider.jobTypeOf("商业分析类")).isNull();
        assertThat(MeituanJobProvider.jobTypeOf("销售、客服与支持类")).isNull();
        assertThat(MeituanJobProvider.jobTypeOf(null)).isNull();
        assertThat(MeituanJobProvider.jobTypeOf("  ")).isNull();
    }

    @Test
    @DisplayName("招聘类型按标题推断：只认明确写出的词")
    void infersRecruitTypeFromTitleOnly() {
        assertThat(MeituanJobProvider.recruitTypeOf("后端开发实习生")).isEqualTo("INTERN");
        assertThat(MeituanJobProvider.recruitTypeOf("2027校园招聘-算法")).isEqualTo("AUTUMN");
        assertThat(MeituanJobProvider.recruitTypeOf("高级后端开发")).isEqualTo("SOCIAL");
        assertThat(MeituanJobProvider.recruitTypeOf(null)).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("城市名：最多 3 个、去重，空数组返回 null")
    void joinsUpToThreeCityNames() throws Exception {
        JsonNode cities = MAPPER.readTree("""
                [{"name":"北京市"},{"name":"北京市"},{"name":"上海市"},{"name":"成都市"}]
                """);
        assertThat(MeituanJobProvider.cityNames(cities)).isEqualTo("北京市、上海市、成都市");
        assertThat(MeituanJobProvider.cityNames(MAPPER.readTree("[]"))).isNull();
        assertThat(MeituanJobProvider.cityNames(null)).isNull();
    }

    @Test
    @DisplayName("结构异常时返回空列表，不抛异常（单源失败不影响其他源）")
    void toleratesMalformedStructure() throws Exception {
        assertThat(provider().parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"data\":{}}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"data\":{\"list\":\"oops\"}}"))).isEmpty();
        assertThat(provider().parse(null)).isEmpty();
    }

    @Test
    @DisplayName("是国内源：不进「海外远程」分栏；单轮配额 50")
    void isDomesticSourceWithPageQuota() {
        MeituanJobProvider provider = provider();
        assertThat(provider.overseas()).isFalse();
        assertThat(provider.platform()).isEqualTo("美团招聘");
        assertThat(provider.maxItemsPerRefresh()).isEqualTo(50);
    }

    // ─────────────────────────── 分页轮转 ───────────────────────────

    /** 只覆写触网方法，轮转 / URL 拼接 / 页数推导全部走真实代码 */
    private static final class RecordingProvider extends MeituanJobProvider {
        private final List<Integer> pages = new java.util.ArrayList<>();
        private final String body;

        RecordingProvider(String body) {
            super(MAPPER);
            this.body = body;
        }

        @Override
        protected String fetchRawPost(String url, String jsonBody, String referer) {
            pages.add(Integer.parseInt(jsonBody.replaceAll(".*\"pageNo\":(\\d+).*", "$1")));
            return body;
        }
    }

    private static String pageBody(long totalCount) {
        return "{\"data\":{\"list\":[{\"jobUnionId\":\"p1\",\"name\":\"后端开发\","
                + "\"jobFamily\":\"技术类\"}],"
                + "\"page\":{\"totalCount\":" + totalCount + "}},\"status\":1}";
    }

    @Test
    @DisplayName("页数由 totalCount 推导（3282 → 66 页；缺省回退 10）")
    void maxPageDerivedFromTotalCount() {
        assertThat(MeituanJobProvider.maxPageOf(3282)).isEqualTo(66);
        assertThat(MeituanJobProvider.maxPageOf(50)).isEqualTo(1);
        assertThat(MeituanJobProvider.maxPageOf(51)).isEqualTo(2);
        assertThat(MeituanJobProvider.maxPageOf(0)).isEqualTo(10);
        assertThat(MeituanJobProvider.maxPageOf(-5)).isEqualTo(10);
    }

    @Test
    @DisplayName("请求体携带 pageNo 与 pageSize=50")
    void buildBodyCarriesPageNo() {
        assertThat(MeituanJobProvider.buildBody(3))
                .contains("\"pageNo\":3")
                .contains("\"pageSize\":50")
                .contains("\"jobShareType\":\"1\"");
    }

    @Test
    @DisplayName("默认每轮 1 页，页码 1→2→3 轮转并在末页回绕到 1")
    void rotatesPageIndexAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(pageBody(150)); // 3 页

        for (int round = 0; round < 4; round++) {
            provider.fetch();
        }

        assertThat(provider.pages).containsExactly(1, 2, 3, 1);
    }

    @Test
    @DisplayName("pages-per-refresh=N 时一轮连抓 N 页，游标整体推进 N")
    void fetchesMultiplePagesPerRefresh() {
        RecordingProvider provider = new RecordingProvider(pageBody(250)); // 5 页
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "pagesPerRefresh", 3);

        provider.fetch(); // 1,2,3
        provider.fetch(); // 4,5,1

        assertThat(provider.pages).containsExactly(1, 2, 3, 4, 5, 1);
    }

    @Test
    @DisplayName("响应里没有 totalCount 时沿用保守上限，不退化成只抓 1 页")
    void missingTotalCountKeepsFallbackCeiling() {
        RecordingProvider provider = new RecordingProvider("{\"data\":{\"list\":[]}}");

        provider.fetch();

        assertThat(provider.maxPage()).isEqualTo(10);
    }
}
