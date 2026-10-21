package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 腾讯招聘数据源解析测试（v1.80.0）
 *
 * <p>打的是**真实响应结构**（从线上接口抓下来的字段名），不打真实网络——
 * 与项目其他 OpenAPI 源测试保持同一做法：网络不可控，但解析契约必须锁死。
 */
class TencentCareersJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TencentCareersJobProvider provider() {
        return new TencentCareersJobProvider(MAPPER);
    }

    /** 取自线上真实响应（字段名与层级完全一致） */
    private static final String REAL_RESPONSE = """
            {"Code":200,"Data":{"Count":2287,"Posts":[
              {"Id":0,"PostId":"2079604867550330880","RecruitPostId":121374,
               "RecruitPostName":"3A动作冒险游戏-战斗主策划",
               "CountryName":"中国","LocationName":"上海","BGName":"IEG",
               "ComCode":"","ComName":"","ProductName":"星遇","CategoryName":"产品",
               "Responsibility":"1.负责战斗系统设计；\\n2.主导技能体系迭代；",
               "LastUpdateTime":"2026年10月20日",
               "PostURL":"http://careers.tencent.com/jobdesc.html?postId=2079604867550330880",
               "SourceID":1,"IsCollect":false,"IsValid":true,
               "RequireWorkYearsName":"五年以上工作经验"},
              {"Id":0,"PostId":"2079604867550330999","RecruitPostName":"后端开发实习生",
               "LocationName":"深圳","BGName":"CSIG","CategoryName":"技术",
               "Responsibility":"负责服务开发","PostURL":"http://x/1","IsValid":true,
               "RequireWorkYearsName":"不限"},
              {"Id":0,"PostId":"2079604867550330888","RecruitPostName":"已下线岗位",
               "LocationName":"北京","IsValid":false}
            ]}}
            """;

    @Test
    @DisplayName("解析真实响应：字段映射正确，无效岗位被跳过")
    void parsesRealResponse() throws Exception {
        JsonNode root = MAPPER.readTree(REAL_RESPONSE);
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(root);

        // 第三条 IsValid=false 必须被丢弃
        assertThat(jobs).hasSize(2);

        JobPlatformAdapter.JobDto first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("tx-2079604867550330880");
        assertThat(first.title()).isEqualTo("3A动作冒险游戏-战斗主策划");
        assertThat(first.companyName()).isEqualTo("腾讯");
        assertThat(first.location()).isEqualTo("上海");
        assertThat(first.jobType()).isEqualTo("产品");
        assertThat(first.experience()).isEqualTo("五年以上工作经验");
        assertThat(first.applyUrl()).contains("careers.tencent.com");
        assertThat(first.description()).contains("战斗系统");
        assertThat(first.tags()).isEqualTo("IEG");
    }

    @Test
    @DisplayName("不编造：薪资 / 学历 / 截止日期 一律留空")
    void doesNotFabricateMissingFields() throws Exception {
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(MAPPER.readTree(REAL_RESPONSE));
        JobPlatformAdapter.JobDto first = jobs.get(0);

        assertThat(first.salary()).isNull();
        assertThat(first.degree()).isNull();
        assertThat(first.deadline()).isNull();
        assertThat(first.industry()).isNull();
    }

    @Test
    @DisplayName("招聘类型按标题推断：只认明确写出的词")
    void infersRecruitTypeFromTitleOnly() {
        assertThat(TencentCareersJobProvider.recruitTypeOf("后端开发实习生")).isEqualTo("INTERN");
        assertThat(TencentCareersJobProvider.recruitTypeOf("2027校园招聘-算法")).isEqualTo("AUTUMN");
        assertThat(TencentCareersJobProvider.recruitTypeOf("校招专项-产品")).isEqualTo("AUTUMN");
        // 没有明确词的一律社招，不猜
        assertThat(TencentCareersJobProvider.recruitTypeOf("高级后端开发")).isEqualTo("SOCIAL");
        assertThat(TencentCareersJobProvider.recruitTypeOf(null)).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("结构异常时返回空列表，不抛异常（单源失败不影响其他源）")
    void toleratesMalformedStructure() throws Exception {
        assertThat(provider().parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"Data\":{}}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"Data\":{\"Posts\":\"oops\"}}"))).isEmpty();
        assertThat(provider().parse(null)).isEmpty();
    }

    @Test
    @DisplayName("是国内源：不进「海外远程」分栏")
    void isDomesticSource() {
        assertThat(provider().overseas()).isFalse();
        assertThat(provider().platform()).isEqualTo("腾讯招聘");
    }

    @Test
    @DisplayName("单轮配额显著大于海外源，保证国内占多数")
    void quotaIsLargerThanOverseasSources() {
        // 海外单源配额是 AbstractOpenApiJobProvider.MAX_ITEMS_PER_REFRESH（15）
        assertThat(provider().maxItemsPerRefresh()).isGreaterThan(15 * 5);
    }

    // ─────────────────── P1-6：分页轮转（此前 pageIndex 写死为 1） ───────────────────

    /**
     * 记录请求 URL 并返回固定响应的子类。
     *
     * <p>只覆写 {@code fetchRaw}（唯一触网的地方），因此轮转逻辑、URL 拼接、页数推导
     * 全部按真实代码执行，只是不走网络——CI 不依赖上游可用性。
     */
    private static final class RecordingProvider extends TencentCareersJobProvider {
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

    /** 造一个「总数 count、当页 1 条岗位」的响应 */
    private static String pageBody(long count) {
        return "{\"Code\":200,\"Data\":{\"Count\":" + count + ",\"Posts\":["
                + "{\"PostId\":\"p1\",\"RecruitPostName\":\"后端开发\",\"IsValid\":true}]}}";
    }

    @Test
    @DisplayName("P1-6：页数上限由 Data.Count 动态推导（2282 → 12 页；缺省回退 15）")
    void maxPageDerivedFromCount() {
        assertThat(TencentCareersJobProvider.maxPageOf(2282)).isEqualTo(12);
        assertThat(TencentCareersJobProvider.maxPageOf(2400)).isEqualTo(12);
        assertThat(TencentCareersJobProvider.maxPageOf(2001)).isEqualTo(11);
        assertThat(TencentCareersJobProvider.maxPageOf(1)).isEqualTo(1);
        // count 缺失/非法：回退到保守上限，而不是停留在 1 页
        assertThat(TencentCareersJobProvider.maxPageOf(0)).isEqualTo(15);
        assertThat(TencentCareersJobProvider.maxPageOf(-5)).isEqualTo(15);
    }

    @Test
    @DisplayName("P1-6：请求 URL 携带 pageIndex 与 pageSize（此前 pageIndex 永远=1）")
    void buildUrlCarriesPageIndex() {
        assertThat(TencentCareersJobProvider.buildUrl(3))
                .contains("pageIndex=3")
                .contains("pageSize=200");
        assertThat(TencentCareersJobProvider.buildUrl(1)).contains("pageIndex=1");
    }

    @Test
    @DisplayName("P1-6：默认每轮 1 页，页码 1→2→3 轮转并在末页回绕到 1")
    void rotatesPageIndexAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(pageBody(600)); // 600/200 = 3 页

        for (int round = 0; round < 4; round++) {
            provider.fetch();
        }

        assertThat(provider.urls).hasSize(4);
        assertThat(provider.urls.get(0)).contains("pageIndex=1");
        assertThat(provider.urls.get(1)).contains("pageIndex=2");
        assertThat(provider.urls.get(2)).contains("pageIndex=3");
        assertThat(provider.urls.get(3)).as("末页之后回绕到第 1 页").contains("pageIndex=1");
    }

    @Test
    @DisplayName("P1-6：pages-per-refresh=N 时一轮连抓 N 页，游标整体推进 N")
    void fetchesMultiplePagesPerRefresh() {
        RecordingProvider provider = new RecordingProvider(pageBody(1000)); // 5 页
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "pagesPerRefresh", 3);

        provider.fetch(); // 1,2,3
        provider.fetch(); // 4,5 然后回绕 1

        assertThat(provider.urls).hasSize(6);
        assertThat(provider.urls.get(0)).contains("pageIndex=1");
        assertThat(provider.urls.get(1)).contains("pageIndex=2");
        assertThat(provider.urls.get(2)).contains("pageIndex=3");
        assertThat(provider.urls.get(3)).contains("pageIndex=4");
        assertThat(provider.urls.get(4)).contains("pageIndex=5");
        assertThat(provider.urls.get(5)).contains("pageIndex=1");
    }

    @Test
    @DisplayName("P1-6：响应里没有 Count 时沿用保守上限，不因一次异常响应退化成只抓 1 页")
    void missingCountKeepsFallbackCeiling() {
        RecordingProvider provider = new RecordingProvider("{\"Data\":{\"Posts\":[]}}");

        provider.fetch();

        assertThat(provider.maxPage()).isEqualTo(15);
    }
}
