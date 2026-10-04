package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 科技公司官方板数据源测试：Ashby / Greenhouse JSON → JobDto 的解析。
 *
 * <p>样本取自 2026-10-04 对线上端点的**真实响应结构**（字段名与嵌套层级照抄），
 * 只做了条数与内容裁剪。任一端点改字段名时，这些用例会立刻变红。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("科技公司官方板数据源解析")
class TechBoardJobProviderTest {

    @Mock
    private JobAgentProperties properties;

    private final TechBoardJobProvider provider =
            new TechBoardJobProvider(new ObjectMapper(), properties);

    private static final String ASHBY_JSON = """
            {
              "apiVersion": "1",
              "jobs": [
                {
                  "id": "d3bc1ced-3ce4-4086-a050-555055dbb1ff",
                  "title": "Senior / Staff Fullstack Engineer",
                  "department": "Product",
                  "team": "Engineering",
                  "employmentType": "FullTime",
                  "location": "Europe",
                  "publishedAt": "2021-04-27T20:13:45.158+00:00",
                  "isListed": true,
                  "isRemote": true,
                  "workplaceType": "Remote",
                  "jobUrl": "https://jobs.ashbyhq.com/linear/d3bc1ced-3ce4-4086-a050-555055dbb1ff",
                  "applyUrl": "https://jobs.ashbyhq.com/linear/d3bc1ced-3ce4-4086-a050-555055dbb1ff/application",
                  "descriptionPlain": "We are looking for a fullstack engineer to build Linear."
                },
                {
                  "id": "hidden-draft-1",
                  "title": "Unlisted Draft Role",
                  "department": "Engineering",
                  "location": "Nowhere",
                  "publishedAt": "2026-09-01T00:00:00.000+00:00",
                  "isListed": false,
                  "isRemote": false,
                  "jobUrl": "https://jobs.ashbyhq.com/linear/hidden-draft-1",
                  "descriptionPlain": "should not appear"
                }
              ]
            }
            """;

    private static final String GREENHOUSE_JSON = """
            {
              "jobs": [
                {
                  "id": 8172508,
                  "title": "Abuse Investigator",
                  "updated_at": "2026-09-25T16:45:00-04:00",
                  "first_published": "2026-08-01T10:00:00-04:00",
                  "absolute_url": "https://stripe.com/jobs/search?gh_jid=8172508",
                  "location": { "name": "Dublin" },
                  "company_name": "Stripe"
                },
                {
                  "id": 9900112,
                  "title": "Senior Software Engineer, Payments",
                  "updated_at": "2026-09-20T12:00:00-04:00",
                  "absolute_url": "https://stripe.com/jobs/search?gh_jid=9900112",
                  "location": { "name": "Remote, US" },
                  "company_name": "Stripe"
                }
              ],
              "meta": { "total": 2 }
            }
            """;

    @Test
    @DisplayName("Ashby：解析职位名/地点/远程标记/申请链接/公司")
    void ashby_parsesCoreFields() {
        List<JobDto> jobs = provider.parseAshby(readTree(ASHBY_JSON), "Linear");

        assertThat(jobs).hasSize(1);
        JobDto j = jobs.get(0);
        assertThat(j.title()).isEqualTo("Senior / Staff Fullstack Engineer");
        assertThat(j.companyName()).isEqualTo("Linear");
        // 远程岗位统一标注「原地区 · 远程」
        assertThat(j.location()).isEqualTo("Europe · 远程");
        assertThat(j.applyUrl()).contains("jobs.ashbyhq.com/linear/");
        assertThat(j.recruitType()).isEqualTo("SOCIAL");
        assertThat(j.description()).contains("fullstack engineer");
        assertThat(j.tags()).contains("Product").contains("Engineering");
    }

    @Test
    @DisplayName("Ashby：isListed=false 的未公开岗位必须被过滤（不猜，按上游标记）")
    void ashby_filtersUnlisted() {
        List<JobDto> jobs = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        assertThat(jobs).extracting(JobDto::title).doesNotContain("Unlisted Draft Role");
    }

    @Test
    @DisplayName("Ashby：externalId 形如 ashby-<uuid>，幂等键稳定")
    void ashby_externalIdStable() {
        List<JobDto> a = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        List<JobDto> b = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        assertThat(a.get(0).externalId()).isEqualTo("ashby-d3bc1ced-3ce4-4086-a050-555055dbb1ff");
        assertThat(a.get(0).externalId()).isEqualTo(b.get(0).externalId());
    }

    @Test
    @DisplayName("Ashby：上游无截止信息 → deadline 留空，不再按 publishedAt 推算假日期")
    void ashby_deadlineIsNull() {
        List<JobDto> jobs = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        assertThat(jobs.get(0).deadline()).isNull();
    }

    @Test
    @DisplayName("Ashby：职位类型按团队/标题判为「技术」")
    void ashby_jobTypeIsTech() {
        List<JobDto> jobs = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        assertThat(jobs.get(0).jobType()).isEqualTo("技术");
    }

    @Test
    @DisplayName("Greenhouse：解析标题/地点/链接；externalId 用数字 id")
    void greenhouse_parsesCoreFields() {
        List<JobDto> jobs = provider.parseGreenhouse(readTree(GREENHOUSE_JSON), "Stripe");

        assertThat(jobs).hasSize(2);
        JobDto j = jobs.get(0);
        assertThat(j.title()).isEqualTo("Abuse Investigator");
        assertThat(j.companyName()).isEqualTo("Stripe");
        assertThat(j.location()).isEqualTo("Dublin");
        assertThat(j.applyUrl()).isEqualTo("https://stripe.com/jobs/search?gh_jid=8172508");
        assertThat(j.externalId()).isEqualTo("gh-8172508");
    }

    @Test
    @DisplayName("Greenhouse：上游无截止信息 → deadline 留空（first_published/updated_at 只作时效参考）")
    void greenhouse_deadlineIsNull() {
        List<JobDto> jobs = provider.parseGreenhouse(readTree(GREENHOUSE_JSON), "Stripe");
        assertThat(jobs.get(0).deadline()).isNull();
    }

    @Test
    @DisplayName("Greenhouse：description 为 null（列表端点不带正文，刻意不请求 26 倍体积的 content）")
    void greenhouse_noDescription() {
        List<JobDto> jobs = provider.parseGreenhouse(readTree(GREENHOUSE_JSON), "Stripe");
        assertThat(jobs.get(0).description()).isNull();
        assertThat(jobs.get(0).requirements()).isNull();
    }

    @Test
    @DisplayName("Greenhouse：缺少 title 或 absolute_url 的条目被跳过（不落半条脏数据）")
    void greenhouse_skipsIncomplete() {
        String broken = """
                {"jobs":[
                  {"id":1,"location":{"name":"X"},"company_name":"Y"},
                  {"id":2,"title":"Good Job","absolute_url":"https://ex.com/2","location":{"name":"Z"}}
                ]}
                """;
        List<JobDto> jobs = provider.parseGreenhouse(readTree(broken), "Y");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("Good Job");
    }

    @Test
    @DisplayName("安静失败防线：Greenhouse 缺 id 时回退用链接末段做 externalId，不得整板丢弃")
    void greenhouse_missingIdFallsBackToUrl() {
        // ⚠️ 这个用例最初写错了：期望「缺 id 一律丢弃」，结果 40 条一起消失（perBoardCap 全红）。
        // id 只是首选 externalId；缺它时用 absolute_url 末段即可——**两者都拿不到**才该丢。
        String missingId = """
                {"jobs":[
                  {"title":"Role A","absolute_url":"https://ex.com/a","location":{"name":"X"}},
                  {"title":"Role B","absolute_url":"https://ex.com/b","location":{"name":"Y"}}
                ]}
                """;
        List<JobDto> jobs = provider.parseGreenhouse(readTree(missingId), "Y");
        assertThat(jobs).hasSize(2);
        assertThat(jobs).extracting(JobDto::externalId).containsExactly("gh-a", "gh-b");
    }

    @Test
    @DisplayName("安静失败防线：Greenhouse 链接取不到末段（externalId 会退化成 gh-）时丢弃")
    void greenhouse_skipsUnderivableExternalId() {
        String noTail = """
                {"jobs":[
                  {"title":"Bad Role","absolute_url":"https://boards.greenhouse.io/","location":{"name":"Z"}}
                ]}
                """;
        assertThat(provider.parseGreenhouse(readTree(noTail), "Z")).isEmpty();
    }

    @Test
    @DisplayName("安静失败防线：Ashby 链接取不到 slug 时丢弃，避免撞成同一个 externalId")
    void ashby_skipsBlankSlugTail() {
        // ⚠️ slugOf 会先剥掉尾部斜杠，所以 "…/linear/" 得到 "linear"（合法岗位页）——
        // 真正要拦的是**取不到末段**的链接（末段为空），否则 externalId 退化成 "ashby-"。
        String noSlug = """
                {"jobs":[
                  {"title":"Weird Role","jobUrl":"https://jobs.ashbyhq.com/","isListed":true}
                ]}
                """;
        List<JobDto> jobs = provider.parseAshby(readTree(noSlug), "Linear");
        assertThat(jobs).isEmpty();
    }

    @Test
    @DisplayName("正常岗位链接（尾段为 uuid）不受上述防线影响")
    void ashby_normalUrlUnaffected() {
        List<JobDto> jobs = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("ashby-d3bc1ced-3ce4-4086-a050-555055dbb1ff");
    }

    @Test
    @DisplayName("幂等：externalId 前缀稳定（ashby-/gh-），改前缀会导致旧岗位全部重复入库")
    void externalIdPrefixStable() {
        List<JobDto> a = provider.parseAshby(readTree(ASHBY_JSON), "Linear");
        List<JobDto> g = provider.parseGreenhouse(readTree(GREENHOUSE_JSON), "Stripe");
        assertThat(a.get(0).externalId()).startsWith("ashby-");
        assertThat(g.get(0).externalId()).startsWith("gh-");
    }

    @Test
    @DisplayName("空/异常输入不抛错，返回空列表（失败隔离的上游保护）")
    void malformedInputsReturnEmpty() {
        assertThat(provider.parseAshby(null, "X")).isEmpty();
        assertThat(provider.parseGreenhouse(null, "X")).isEmpty();
        assertThat(provider.parseAshby(readTree("{\"jobs\":{}}"), "X")).isEmpty();
        assertThat(provider.parseGreenhouse(readTree("{}"), "X")).isEmpty();
        assertThat(provider.parseAshby(readTree("[]"), "X")).isEmpty();
    }

    @Test
    @DisplayName("合规：本源归类为海外源，且只读公开 GET（不登录不投递）")
    void overseasAndReadOnly() {
        assertThat(provider.overseas()).isTrue();
        assertThat(provider.platform()).isEqualTo("科技公司官方板");
    }

    @Test
    @DisplayName("单公司最多取 MAX_PER_BOARD 条，防止一家淹没其他源")
    void perBoardCap() {
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(1000 + i)
              .append(",\"title\":\"Eng ").append(i)
              .append("\",\"absolute_url\":\"https://ex.com/").append(i)
              .append("\",\"location\":{\"name\":\"L\"}}");
        }
        sb.append("]}");
        List<JobDto> jobs = provider.parseGreenhouse(readTree(sb.toString()), "Big");
        assertThat(jobs).hasSize(12);   // MAX_PER_BOARD
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
