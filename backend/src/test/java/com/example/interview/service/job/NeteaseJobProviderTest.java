package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网易招聘数据源解析测试（v1.87.0）
 *
 * <p>使用线上真实响应结构，不打真实网络。
 */
class NeteaseJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NeteaseJobProvider provider() {
        return new NeteaseJobProvider(MAPPER);
    }

    /** 取自线上真实响应（2026-10-29 探测） */
    private static final String REAL_RESPONSE = """
            {"code":200,"msg":null,"data":{"pages":55,"total":2729,"lastPage":false,"list":[
              {"id":74692,"name":"游戏营销实习生-玩家社群运营","workType":"1",
               "firstPostTypeName":"市场","recruitNum":1,
               "requirement":"1、全日制本科及以上学历；2、资深游戏玩家；",
               "description":"1、负责核心社群日常管理；2、策划线上活动；",
               "reqEducationName":"本科","reqWorkYearsName":"不限",
               "firstDepName":"雷火事业群","workPlaceNameList":["杭州市"],
               "updateTime":1793269454000,"productName":"网易游戏（雷火）"},
              {"id":79325,"name":"商业化数值策划（大话西游手游）","workType":"0",
               "firstPostTypeName":"游戏策划","recruitNum":1,
               "requirement":"1. 本科及以上，3 年以上游戏策划经验；",
               "description":"1. 促销活动设计；2. 数据迭代；",
               "reqEducationName":"其他","reqWorkYearsName":"3-5年",
               "firstDepName":"大话事业部","workPlaceNameList":["广州市","杭州市"],
               "updateTime":1793267486000,"productName":"网易游戏（互娱）"},
              {"id":80001,"name":"高级游戏美术商务专员","workType":"2",
               "firstPostTypeName":"商务","reqEducationName":"不限","reqWorkYearsName":"5-10年",
               "firstDepName":"美术中心","workPlaceNameList":["上海市"],
               "productName":"网易游戏（互娱）"},
              {"name":"缺少 id 的脏数据","workType":"0"}
            ]},"subCode":null,"traceId":"x"}
            """;

    @Test
    @DisplayName("解析真实响应：公司取 productName，学历/年限如实透传，缺 id 丢弃")
    void parsesRealResponse() throws Exception {
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(MAPPER.readTree(REAL_RESPONSE));

        assertThat(jobs).hasSize(3);

        JobPlatformAdapter.JobDto intern = jobs.get(0);
        assertThat(intern.externalId()).isEqualTo("ne-74692");
        assertThat(intern.title()).isEqualTo("游戏营销实习生-玩家社群运营");
        assertThat(intern.companyName()).isEqualTo("网易游戏（雷火）");
        assertThat(intern.location()).isEqualTo("杭州市");
        assertThat(intern.degree()).isEqualTo("本科");
        assertThat(intern.experience()).isEqualTo("不限");
        assertThat(intern.recruitType()).isEqualTo("INTERN");
        assertThat(intern.applyUrl()).contains("hr.163.com").contains("74692");
        assertThat(intern.description()).contains("社群");
        assertThat(intern.requirements()).contains("全日制本科");
        assertThat(intern.tags()).isEqualTo("市场,雷火事业群");

        JobPlatformAdapter.JobDto social = jobs.get(1);
        assertThat(social.location()).isEqualTo("广州市,杭州市");
        assertThat(social.degree()).isEqualTo("其他");
        assertThat(social.experience()).isEqualTo("3-5年");
        assertThat(social.recruitType()).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("不编造：薪资 / 截止日期 / 行业 / 职位类型 一律留空")
    void doesNotFabricateMissingFields() throws Exception {
        JobPlatformAdapter.JobDto first = provider()
                .parse(MAPPER.readTree(REAL_RESPONSE)).get(0);

        assertThat(first.salary()).isNull();
        assertThat(first.deadline()).isNull();
        assertThat(first.industry()).isNull();
        assertThat(first.jobType()).isNull();
    }

    @Test
    @DisplayName("招聘类型：workType=1 是实习；派遣(2) 与社招(0) 按标题兜底")
    void infersRecruitType() {
        assertThat(NeteaseJobProvider.recruitTypeOf("1", "任意标题")).isEqualTo("INTERN");
        assertThat(NeteaseJobProvider.recruitTypeOf("0", "后端开发实习生")).isEqualTo("INTERN");
        assertThat(NeteaseJobProvider.recruitTypeOf("0", "2027校园招聘-算法")).isEqualTo("AUTUMN");
        assertThat(NeteaseJobProvider.recruitTypeOf("2", "高级游戏美术商务专员")).isEqualTo("SOCIAL");
        assertThat(NeteaseJobProvider.recruitTypeOf("0", "引擎开发专家")).isEqualTo("SOCIAL");
        assertThat(NeteaseJobProvider.recruitTypeOf(null, null)).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("productName 缺失时公司名兜底为「网易」，不产生 null 公司")
    void fallsBackToNeteaseWhenProductMissing() throws Exception {
        String body = "{\"data\":{\"list\":[{\"id\":1,\"name\":\"后端开发\",\"workType\":\"0\"}]}}";
        JobPlatformAdapter.JobDto dto = provider().parse(MAPPER.readTree(body)).get(0);
        assertThat(dto.companyName()).isEqualTo("网易");
    }

    @Test
    @DisplayName("结构异常时返回空列表，不抛异常")
    void toleratesMalformedStructure() throws Exception {
        assertThat(provider().parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"data\":{}}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"data\":{\"list\":\"oops\"}}"))).isEmpty();
        assertThat(provider().parse((JsonNode) null)).isEmpty();
    }

    @Test
    @DisplayName("是国内源：不进「海外远程」分栏；单轮配额 50")
    void isDomesticSourceWithPageQuota() {
        NeteaseJobProvider provider = provider();
        assertThat(provider.overseas()).isFalse();
        assertThat(provider.platform()).isEqualTo("网易招聘");
        assertThat(provider.maxItemsPerRefresh()).isEqualTo(50);
    }

    // ─────────────────────────── 分页轮转 ───────────────────────────

    private static final class RecordingProvider extends NeteaseJobProvider {
        private final List<Integer> pages = new java.util.ArrayList<>();
        private final String body;

        RecordingProvider(String body) {
            super(MAPPER);
            this.body = body;
        }

        @Override
        protected String fetchRawPost(String url, String jsonBody, String referer) {
            pages.add(Integer.parseInt(jsonBody.replaceAll(".*\"currentPage\":(\\d+).*", "$1")));
            return body;
        }
    }

    private static String pageBody(long pages) {
        return "{\"code\":200,\"data\":{\"pages\":" + pages + ",\"total\":100,"
                + "\"list\":[{\"id\":1,\"name\":\"后端开发\",\"workType\":\"0\"}]}}";
    }

    @Test
    @DisplayName("页数即 data.pages；缺省回退 10")
    void maxPageDerivedFromPages() {
        assertThat(NeteaseJobProvider.maxPageOf(55)).isEqualTo(55);
        assertThat(NeteaseJobProvider.maxPageOf(1)).isEqualTo(1);
        assertThat(NeteaseJobProvider.maxPageOf(0)).isEqualTo(10);
        assertThat(NeteaseJobProvider.maxPageOf(-3)).isEqualTo(10);
    }

    @Test
    @DisplayName("请求体携带 currentPage 与 pageSize=50")
    void buildBodyCarriesPageNo() {
        assertThat(NeteaseJobProvider.buildBody(2))
                .contains("\"currentPage\":2")
                .contains("\"pageSize\":50");
    }

    @Test
    @DisplayName("默认每轮 1 页，页码在末页回绕")
    void rotatesPageIndexAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(pageBody(3));

        for (int round = 0; round < 4; round++) {
            provider.fetch();
        }

        assertThat(provider.pages).containsExactly(1, 2, 3, 1);
    }

    @Test
    @DisplayName("pages-per-refresh=N 时一轮连抓 N 页")
    void fetchesMultiplePagesPerRefresh() {
        RecordingProvider provider = new RecordingProvider(pageBody(5));
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "pagesPerRefresh", 3);

        provider.fetch();
        provider.fetch();

        assertThat(provider.pages).containsExactly(1, 2, 3, 4, 5, 1);
    }

    @Test
    @DisplayName("响应里没有 pages 时沿用保守上限")
    void missingPagesKeepsFallbackCeiling() {
        RecordingProvider provider = new RecordingProvider("{\"data\":{\"list\":[]}}");

        provider.fetch();

        assertThat(provider.maxPage()).isEqualTo(10);
    }
}
