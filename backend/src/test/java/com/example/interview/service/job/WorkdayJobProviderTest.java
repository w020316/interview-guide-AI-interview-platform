package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workday 外企官方站解析测试（v1.88.0）
 *
 * <p>覆盖三件最容易被上游改动打坏的事：China facet 自动发现、
 * 「多地点」岗位的地点归一、详情失败时不让整源失败。
 */
class WorkdayJobProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FACETS_WITH_CHINA = """
            {"total":2000,"jobPostings":[],"facets":[
              {"facetParameter":"jobFamilyGroup","descriptor":"Job Category",
               "values":[{"descriptor":"Engineering","id":"fam-1","count":1720}]},
              {"facetParameter":"locationMainGroup","values":[
                {"facetParameter":"locationHierarchy2","descriptor":"Location Type",
                 "values":[{"descriptor":"Office","id":"lt-1","count":2547}]},
                {"facetParameter":"locationHierarchy1","descriptor":"Locations","values":[
                  {"descriptor":"China","id":"china-id","count":245},
                  {"descriptor":"United States","id":"us-id","count":1395}]}
              ]}
            ]}
            """;

    private static final String LIST_TWO_CHINA_JOBS = """
            {"total":245,"jobPostings":[
              {"title":"Performance Engineering Intern, Deep Learning and HPC - 2027",
               "externalPath":"/job/China-Shanghai/Performance-Engineering-Intern_JR2028299",
               "locationsText":"China, Shanghai","bulletFields":["JR2028299"]},
              {"title":"Architecture Energy Modeling Engineer",
               "externalPath":"/job/China-Shanghai/Architecture-Energy-Modeling-Engineer_JR2028478",
               "locationsText":"3 Locations","bulletFields":["JR2028478"]}
            ]}
            """;

    private static String detailBody(String url) {
        boolean intern = url.contains("JR2028299");
        String title = intern
                ? "Performance Engineering Intern, Deep Learning and HPC - 2027"
                : "Architecture Energy Modeling Engineer";
        String desc = intern
                ? "<p>We are seeking an intern to work on deep learning performance.</p>"
                : "<p>Model architecture energy efficiency.</p>";
        String reqId = intern ? "JR2028299" : "JR2028478";
        // 详情地点按岗位区分：真实上游里列表的「3 Locations」由详情收敛为具体城市，
        // 桩若统一返回上海，就把「详情地点优先于列表摘要」这条语义测成了假通过。
        String location = intern ? "China, Shanghai" : "Shenzhen, China";
        return "{\"jobPostingInfo\":{\"title\":\"" + title + "\",\"jobDescription\":\"" + desc
                + "\",\"location\":\"" + location + "\","
                + "\"externalUrl\":\"https://acme.wd5.myworkdayjobs.com/AcmeSite/job/China-Shanghai/x_" + reqId + "\","
                + "\"jobReqId\":\"" + reqId + "\"}}";
    }

    /** 只覆写触网方法：facet 发现 / 分页 / 详情降级全部走真实代码 */
    private static final class RecordingProvider extends AbstractWorkdayJobProvider {
        private final List<String> listBodies = new ArrayList<>();
        private final List<String> detailUrls = new ArrayList<>();
        private final String facetsBody;
        private final String listBody;
        private final boolean failDetail;
        private boolean discovered;

        RecordingProvider(String facetsBody, String listBody, boolean failDetail) {
            super(MAPPER);
            this.facetsBody = facetsBody;
            this.listBody = listBody;
            this.failDetail = failDetail;
        }

        @Override
        protected String fetchRawPost(String url, String jsonBody, String referer) {
            listBodies.add(jsonBody);
            if (!discovered) {
                discovered = true;
                return facetsBody;
            }
            return listBody;
        }

        @Override
        protected String fetchRaw(String url) {
            detailUrls.add(url);
            if (failDetail) {
                throw new IllegalStateException("详情接口抖动");
            }
            return detailBody(url);
        }

        @Override
        public String platform() {
            return "测试外企";
        }

        @Override
        protected String workdayHost() {
            return "acme.wd5.myworkdayjobs.com";
        }

        @Override
        protected String tenant() {
            return "acme";
        }

        @Override
        protected String site() {
            return "AcmeSite";
        }

        @Override
        protected String companyName() {
            return "NVIDIA";
        }

        @Override
        protected String externalIdPrefix() {
            return "nv";
        }
    }

    @Test
    @DisplayName("首次请求发现 China facet，之后所有列表请求都带上它")
    void discoversChinaFacetAndAppliesIt() {
        RecordingProvider provider = new RecordingProvider(FACETS_WITH_CHINA, LIST_TWO_CHINA_JOBS, false);

        provider.fetch();

        assertThat(provider.listBodies).hasSize(2);
        assertThat(provider.listBodies.get(0)).contains("\"searchText\":\"China\"");
        assertThat(provider.listBodies.get(1))
                .contains("\"locationHierarchy1\":[\"china-id\"]")
                .contains("\"searchText\":\"\"");
    }

    @Test
    @DisplayName("解析英伟达真实结构：字段映射正确，多地点岗位地点归一为城市")
    void parsesChinaJobs() {
        RecordingProvider provider = new RecordingProvider(FACETS_WITH_CHINA, LIST_TWO_CHINA_JOBS, false);

        List<JobPlatformAdapter.JobDto> jobs = provider.fetch();

        assertThat(jobs).hasSize(2);

        JobPlatformAdapter.JobDto intern = jobs.get(0);
        assertThat(intern.externalId()).isEqualTo("nv-JR2028299");
        assertThat(intern.title()).contains("Performance Engineering Intern");
        assertThat(intern.companyName()).isEqualTo("NVIDIA");
        assertThat(intern.jobType()).isEqualTo("技术");
        assertThat(intern.location()).isEqualTo("上海");
        assertThat(intern.recruitType()).isEqualTo("INTERN");
        assertThat(intern.applyUrl()).contains("x_JR2028299");
        assertThat(intern.description()).contains("deep learning performance");
        // 上游不公开的一律留空
        assertThat(intern.salary()).isNull();
        assertThat(intern.degree()).isNull();
        assertThat(intern.experience()).isNull();
        assertThat(intern.deadline()).isNull();
        assertThat(intern.industry()).isNull();

        JobPlatformAdapter.JobDto engineer = jobs.get(1);
        assertThat(engineer.externalId()).isEqualTo("nv-JR2028478");
        // 列表只有「3 Locations」，详情收敛为具体城市 → 详情优先
        assertThat(engineer.location()).isEqualTo("深圳");
        assertThat(engineer.recruitType()).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("找不到 China facet 时退回 searchText=China，并过滤掉非中国岗位")
    void fallsBackToSearchTextWhenNoChinaFacet() {
        String noChina = """
                {"total":2000,"jobPostings":[],"facets":[
                  {"facetParameter":"jobFamilyGroup","descriptor":"Job Category",
                   "values":[{"descriptor":"Engineering","id":"fam-1","count":1720}]}
                ]}
                """;
        String mixed = """
                {"total":900,"jobPostings":[
                  {"title":"Senior Software Engineer","externalPath":"/job/US-CA/Senior-Engineer_JR1",
                   "locationsText":"US, CA, Santa Clara","bulletFields":["JR1"]},
                  {"title":"Hardware Engineer","externalPath":"/job/Shenzhen-China/Hardware-Engineer_JR2",
                   "locationsText":"Shenzhen, China","bulletFields":["JR2"]}
                ]}
                """;
        RecordingProvider provider = new RecordingProvider(noChina, mixed, false);

        List<JobPlatformAdapter.JobDto> jobs = provider.fetch();

        assertThat(provider.listBodies.get(1)).contains("\"appliedFacets\":{}").contains("\"searchText\":\"China\"");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("nv-JR2");
        // 思科式「城市, China」也能归一
        assertThat(jobs.get(0).location()).isEqualTo("深圳");
    }

    @Test
    @DisplayName("单条详情失败时仍入库列表字段，不让整源失败")
    void detailFailureDoesNotFailSource() {
        RecordingProvider provider = new RecordingProvider(FACETS_WITH_CHINA, LIST_TWO_CHINA_JOBS, true);

        List<JobPlatformAdapter.JobDto> jobs = provider.fetch();

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).title()).contains("Performance Engineering Intern");
        assertThat(jobs.get(0).description()).isNull();
        assertThat(jobs.get(0).applyUrl()).contains("acme.wd5.myworkdayjobs.com/AcmeSite/job/");
    }

    @Test
    @DisplayName("按 total 推导页数，偏移量 0→20→40 轮转")
    void rotatesOffsetsAcrossRefreshes() {
        RecordingProvider provider = new RecordingProvider(FACETS_WITH_CHINA, LIST_TWO_CHINA_JOBS, false);

        provider.fetch();
        provider.fetch();
        provider.fetch();

        assertThat(provider.listBodies).hasSize(4);
        assertThat(provider.listBodies.get(1)).contains("\"offset\":0");
        assertThat(provider.listBodies.get(2)).contains("\"offset\":20");
        assertThat(provider.listBodies.get(3)).contains("\"offset\":40");
    }

    // ─────────────────────────── 纯函数 ───────────────────────────

    @Test
    @DisplayName("facet 发现：国家级优先；无国家级时收集城市级 id")
    void findsCountryThenCityFacets() throws Exception {
        AbstractWorkdayJobProvider.ChinaFacet country =
                AbstractWorkdayJobProvider.findChinaFacet(MAPPER.readTree(FACETS_WITH_CHINA));
        assertThat(country).isNotNull();
        assertThat(country.parameter()).isEqualTo("locationHierarchy1");
        assertThat(country.ids()).containsExactly("china-id");

        JsonNode ciscoLike = MAPPER.readTree("""
                {"facets":[{"facetParameter":"locationMainGroup","values":[
                  {"facetParameter":"locations","descriptor":"Locations","values":[
                    {"descriptor":"Beijing, China","id":"bj"},
                    {"descriptor":"Hangzhou, China","id":"hz"},
                    {"descriptor":"US, Remote","id":"us"}]}]}]}
                """);
        AbstractWorkdayJobProvider.ChinaFacet cities =
                AbstractWorkdayJobProvider.findChinaFacet(ciscoLike);
        assertThat(cities).isNotNull();
        assertThat(cities.parameter()).isEqualTo("locations");
        assertThat(cities.ids()).containsExactly("bj", "hz");

        assertThat(AbstractWorkdayJobProvider.findChinaFacet(MAPPER.readTree("{\"facets\":[]}"))).isNull();
        assertThat(AbstractWorkdayJobProvider.findChinaFacet(null)).isNull();
    }

    @Test
    @DisplayName("地点归一：两种 China 写法、多地点、未知城市")
    void normalizesLocations() {
        assertThat(AbstractWorkdayJobProvider.locationOf("China, Shanghai", null)).isEqualTo("上海");
        assertThat(AbstractWorkdayJobProvider.locationOf("Shenzhen, China", null)).isEqualTo("深圳");
        assertThat(AbstractWorkdayJobProvider.locationOf("3 Locations", null)).isEqualTo("中国");
        assertThat(AbstractWorkdayJobProvider.locationOf("China", null)).isEqualTo("中国");
        assertThat(AbstractWorkdayJobProvider.locationOf("China, Zhuhai", null)).isEqualTo("珠海");
        assertThat(AbstractWorkdayJobProvider.locationOf("US, CA, Santa Clara", null))
                .isEqualTo("US, CA, Santa Clara");
        assertThat(AbstractWorkdayJobProvider.locationOf(null, "北京")).isEqualTo("北京");
    }

    @Test
    @DisplayName("招聘类型：Intern 用词边界匹配，Internal 不算实习")
    void infersRecruitType() {
        assertThat(AbstractWorkdayJobProvider.recruitTypeOf("Performance Engineering Intern - 2027"))
                .isEqualTo("INTERN");
        assertThat(AbstractWorkdayJobProvider.recruitTypeOf("Internal Audit Manager"))
                .isEqualTo("SOCIAL");
        assertThat(AbstractWorkdayJobProvider.recruitTypeOf(
                "NVIDIA 2027 New College Graduate: Software Engineering")).isEqualTo("AUTUMN");
        assertThat(AbstractWorkdayJobProvider.recruitTypeOf("Senior Software Engineer"))
                .isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("职位类型只做词面映射，无法等价的留空")
    void mapsJobTypeByTitle() {
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Software Engineer")).isEqualTo("技术");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Product Designer")).isEqualTo("设计");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Product Manager")).isEqualTo("产品");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Sales Manager")).isEqualTo("销售");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Marketing Specialist")).isEqualTo("市场");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Program Manager")).isEqualTo("运营");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Human Resources Manager")).isEqualTo("职能");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Administrative Assistant")).isEqualTo("职能");
        assertThat(AbstractWorkdayJobProvider.jobTypeOf("Some Unknown Role")).isNull();
    }

    @Test
    @DisplayName("是国内源：不进海外分栏；单轮配额 20")
    void isDomesticSource() {
        NvidiaJobProvider nvidia = new NvidiaJobProvider(MAPPER);
        CiscoJobProvider cisco = new CiscoJobProvider(MAPPER);
        assertThat(nvidia.overseas()).isFalse();
        assertThat(nvidia.platform()).isEqualTo("英伟达招聘");
        assertThat(cisco.platform()).isEqualTo("思科招聘");
        assertThat(nvidia.maxItemsPerRefresh()).isEqualTo(20);
    }
}
