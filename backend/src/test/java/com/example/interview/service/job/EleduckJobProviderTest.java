package com.example.interview.service.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 电鸭社区数据源解析测试（v1.88.0）
 *
 * <p>使用线上真实响应结构（2026-10-29 探测），不打真实网络。
 */
class EleduckJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EleduckJobProvider provider() {
        return new EleduckJobProvider(MAPPER);
    }

    /** 取自线上真实响应（字段与层级一致，正文为节选） */
    private static final String REAL_RESPONSE = """
            {"posts":[
              {"id":"QZfL4z","title":"【全国远程/贵阳】AI Agent 开发工程师（3K起+期权/分红·初创直招）",
               "summary":"初创技术团队，专注大模型应用开发，正在打造自己的 AI Agent 产品（非外包）。",
               "closed":false,"deleted":false,"hide":false,
               "tags":[
                 {"name":"混合办公","tag_group":{"code":"job_type"}},
                 {"name":"企业直招","tag_group":{"code":"hire_type"}},
                 {"name":"AI工程师","tag_group":{"code":"skill_type"}},
                 {"name":"贵阳","tag_group":{"code":"city"}},
                 {"name":"AI/人工智能","tag_group":{"code":"field"}}
               ]},
              {"id":"Closed01","title":"某公司招聘资深后端 15-25K","summary":"已关闭",
               "closed":true,"deleted":false,"hide":false,"tags":[]},
              {"id":"Seeker01","title":"求职：找一份远程前端工作","summary":"个人简历见附件",
               "closed":false,"deleted":false,"hide":false,"tags":[]},
              {"id":"Cd56ab","title":"某某科技招聘产品经理","summary":"负责 B 端产品",
               "closed":false,"deleted":false,"hide":false,
               "tags":[{"name":"其他","tag_group":{"code":"city"}},
                       {"name":"北京","tag_group":{"code":"city"}}]},
              {"id":null,"title":"缺少 id 的脏数据","summary":"x",
               "closed":false,"deleted":false,"hide":false,"tags":[]}
            ],
            "pager":{"total_pages":410,"current_page":1,"total_count":10237,"limit_value":25}}
            """;

    @Test
    @DisplayName("解析真实响应：关闭/求职/缺 id 的帖子被丢弃")
    void parsesRealResponse() throws Exception {
        List<JobPlatformAdapter.JobDto> jobs = provider().parse(MAPPER.readTree(REAL_RESPONSE));

        assertThat(jobs).hasSize(2);

        JobPlatformAdapter.JobDto first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("ed-QZfL4z");
        assertThat(first.title()).contains("AI Agent 开发工程师");
        assertThat(first.jobType()).isEqualTo("技术");
        assertThat(first.location()).isEqualTo("贵阳");
        assertThat(first.salary()).isEqualTo("3K起");
        assertThat(first.applyUrl()).isEqualTo("https://eleduck.com/posts/QZfL4z");
        assertThat(first.description()).contains("大模型应用开发");
        assertThat(first.tags()).contains("混合办公").contains("AI工程师").contains("贵阳");
        assertThat(first.companyName()).isEqualTo("见岗位详情");
    }

    @Test
    @DisplayName("不编造：学历/经验/截止/行业留空；城市标签为「其他」时不猜地点")
    void doesNotFabricateMissingFields() throws Exception {
        JobPlatformAdapter.JobDto second = provider()
                .parse(MAPPER.readTree(REAL_RESPONSE)).get(1);

        assertThat(second.externalId()).isEqualTo("ed-Cd56ab");
        assertThat(second.companyName()).isEqualTo("某某科技");
        assertThat(second.jobType()).isEqualTo("产品");
        assertThat(second.location()).isEqualTo("北京");
        assertThat(second.salary()).isNull();
        assertThat(second.degree()).isNull();
        assertThat(second.experience()).isNull();
        assertThat(second.deadline()).isNull();
        assertThat(second.industry()).isNull();
    }

    @Test
    @DisplayName("薪资只提取标题里明确写出的区间或下限")
    void extractsOnlyExplicitSalary() {
        assertThat(EleduckJobProvider.salaryOf("资深后端工程师 15-25K")).isEqualTo("15-25K");
        // 两端各带 K 的写法同样支持
        assertThat(EleduckJobProvider.salaryOf("前端开发 15K-25K")).isEqualTo("15-25K");
        // 真实标题里出现过 en dash（U+2013），只认 ASCII 连字符会漏
        assertThat(EleduckJobProvider.salaryOf("高级前端工程师 23K\u201332K")).isEqualTo("23-32K");
        assertThat(EleduckJobProvider.salaryOf("AI Agent 开发工程师（3K起+期权）")).isEqualTo("3K起");
        assertThat(EleduckJobProvider.salaryOf("薪资面议，有期权")).isNull();
        // 右端强制要求 K：年份区间 / 经验区间不能被当成薪资
        assertThat(EleduckJobProvider.salaryOf("2027-2028 届校招后端")).isNull();
        assertThat(EleduckJobProvider.salaryOf("3-5 年经验后端工程师")).isNull();
        assertThat(EleduckJobProvider.salaryOf(null)).isNull();
    }

    @Test
    @DisplayName("城市标签：「其他」视为未知，多城市去重拼接")
    void readsCityFromStructuredTags() throws Exception {
        assertThat(EleduckJobProvider.cityOf(MAPPER.readTree("""
                [{"name":"其他","tag_group":{"code":"city"}}]
                """))).isNull();
        assertThat(EleduckJobProvider.cityOf(MAPPER.readTree("""
                [{"name":"上海","tag_group":{"code":"city"}},
                 {"name":"上海","tag_group":{"code":"city"}},
                 {"name":"远程","tag_group":{"code":"city"}}]
                """))).isEqualTo("上海、远程");
        assertThat(EleduckJobProvider.cityOf(null)).isNull();
    }

    @Test
    @DisplayName("招聘类型只认明确写出的词")
    void infersRecruitTypeFromTitleOnly() {
        assertThat(EleduckJobProvider.recruitTypeOf("前端开发实习生")).isEqualTo("INTERN");
        assertThat(EleduckJobProvider.recruitTypeOf("2027 校园招聘")).isEqualTo("AUTUMN");
        assertThat(EleduckJobProvider.recruitTypeOf("资深 Rust 工程师")).isEqualTo("SOCIAL");
        // 词边界：Internal / International 不是实习
        assertThat(EleduckJobProvider.recruitTypeOf("Internal Tools Engineer")).isEqualTo("SOCIAL");
        assertThat(EleduckJobProvider.recruitTypeOf("International Sales Manager")).isEqualTo("SOCIAL");
        assertThat(EleduckJobProvider.recruitTypeOf("Backend Intern")).isEqualTo("INTERN");
    }

    @Test
    @DisplayName("非岗位帖不入库：诈骗警示 / 外包接单需求被丢弃，真实岗位保留")
    void filtersOutNonJobPosts() {
        // 平台自己的反诈警示帖：标题带「远程/全职」，仅靠求职词判据会漏
        assertThat(EleduckJobProvider.looksLikeHiring(
                "【用户举报，涉海外诈骗套路，公开警示⚠️】全程远程办公（可居家）｜全职｜外贸｜贸易"))
                .isFalse();
        assertThat(EleduckJobProvider.looksLikeHiring("避雷这家公司，拖欠工资")).isFalse();
        // 外包接单需求（预算 XX 元）不是雇佣岗位
        assertThat(EleduckJobProvider.looksLikeHiring("预算2000元 武侠RPG武学系统")).isFalse();
        assertThat(EleduckJobProvider.looksLikeHiring("预算 6-8万 官网定制")).isFalse();
        // 真实岗位不能被误杀
        assertThat(EleduckJobProvider.looksLikeHiring("资深后端工程师 15-25K")).isTrue();
        assertThat(EleduckJobProvider.looksLikeHiring("某某科技招聘产品经理")).isTrue();
        // 「品牌曝光」是市场岗的正常用词，不能当警示帖过滤
        assertThat(EleduckJobProvider.looksLikeHiring("品牌曝光专员（新媒体方向）")).isTrue();
        assertThat(EleduckJobProvider.looksLikeHiring(null)).isFalse();
    }

    @Test
    @DisplayName("职位类型识别不出时留空，不兜底猜「技术」")
    void leavesUnknownJobTypeBlank() {
        assertThat(EleduckJobProvider.jobTypeOf("资深后端工程师")).isEqualTo("技术");
        assertThat(EleduckJobProvider.jobTypeOf("产品经理")).isEqualTo("产品");
        // 咖啡师 / 店员这类岗位不属于任何既定枚举，兜底成「技术」等于编造
        assertThat(EleduckJobProvider.jobTypeOf("咖啡师")).isNull();
        assertThat(EleduckJobProvider.jobTypeOf("门店店员")).isNull();
    }

    @Test
    @DisplayName("是国内源：不进海外分栏；单轮配额 25")
    void isDomesticSource() {
        EleduckJobProvider provider = provider();
        assertThat(provider.overseas()).isFalse();
        assertThat(provider.platform()).isEqualTo("电鸭社区");
        assertThat(provider.maxItemsPerRefresh()).isEqualTo(25);
    }

    @Test
    @DisplayName("结构异常时返回空列表，不抛异常")
    void toleratesMalformedStructure() throws Exception {
        assertThat(provider().parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(provider().parse(MAPPER.readTree("{\"posts\":\"oops\"}"))).isEmpty();
        assertThat(provider().parse(null)).isEmpty();
    }

    // ─────────────────────────── 详情与分页 ───────────────────────────

    private static String detail(String id) {
        return "{\"post\":{\"id\":\"" + id + "\",\"content\":"
                + "\"<p>岗位职责：负责远程协作产品的前后端开发。</p><p>要求：三年以上经验。</p>\"}}";
    }

    /** 只覆写触网方法，分页 / 详情补正文走真实代码 */
    private static final class RecordingProvider extends EleduckJobProvider {
        private final List<Integer> pages = new ArrayList<>();
        private final String listBody;
        private final boolean failDetail;

        RecordingProvider(String listBody, boolean failDetail) {
            super(MAPPER);
            this.listBody = listBody;
            this.failDetail = failDetail;
        }

        @Override
        protected String fetchRaw(String url) {
            if (url.contains("category=5")) {
                pages.add(Integer.parseInt(url.replaceAll(".*page=(\\d+).*", "$1")));
                return listBody;
            }
            if (failDetail) {
                throw new IllegalStateException("详情接口抖动");
            }
            return detail(url.substring(url.lastIndexOf('/') + 1));
        }
    }

    private static String listBody(long totalPages) {
        return "{\"posts\":[{\"id\":\"p1\",\"title\":\"远程前端工程师 20-30K\","
                + "\"summary\":\"列表摘要\",\"closed\":false,\"deleted\":false,\"hide\":false,"
                + "\"tags\":[{\"name\":\"远程\",\"tag_group\":{\"code\":\"city\"}}]}],"
                + "\"pager\":{\"total_pages\":" + totalPages + "}}";
    }

    @Test
    @DisplayName("fetch 用详情正文覆盖列表摘要")
    void enrichReplacesSummaryWithDetailContent() {
        RecordingProvider provider = new RecordingProvider(listBody(410), false);

        List<JobPlatformAdapter.JobDto> jobs = provider.fetch();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).description()).contains("岗位职责").contains("三年以上经验");
        assertThat(jobs.get(0).salary()).isEqualTo("20-30K");
    }

    @Test
    @DisplayName("单条详情失败时退回列表摘要，不丢岗位")
    void detailFailureFallsBackToSummary() {
        RecordingProvider provider = new RecordingProvider(listBody(410), true);

        List<JobPlatformAdapter.JobDto> jobs = provider.fetch();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).description()).isEqualTo("列表摘要");
    }

    @Test
    @DisplayName("页数由 pager.total_pages 推导（410 → 410；缺失回退 10）")
    void maxPageDerivedFromPager() {
        assertThat(EleduckJobProvider.maxPageOf(410)).isEqualTo(410);
        assertThat(EleduckJobProvider.maxPageOf(1)).isEqualTo(1);
        assertThat(EleduckJobProvider.maxPageOf(0)).isEqualTo(10);
        assertThat(EleduckJobProvider.maxPageOf(-1)).isEqualTo(10);
    }

    @Test
    @DisplayName("默认每轮 1 页，页码 1→2→3 轮转并在末页回绕")
    void rotatesPagesAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(listBody(3), false);

        for (int round = 0; round < 4; round++) {
            provider.fetch();
        }

        assertThat(provider.pages).containsExactly(1, 2, 3, 1);
    }
}
