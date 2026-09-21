package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebJobSearcherService 单元测试
 * 覆盖：智联 SSR 内嵌 JSON 岗位提取（不依赖网络，用模拟 HTML）。
 */
class WebJobSearcherServiceTest {

    private final WebJobSearcherService svc = new WebJobSearcherService();

    /** 模拟一段智联 SSR 内嵌 JSON（positionName 与相邻 base 字段结构），贴近真实返回 */
    private static final String MOCK_HTML =
            "<script>var data=[{position:{base:{"
                    + "\"education\":\"本科\",\"positionName\":\"Java开发工程师\","
                    + "\"salary\":\"20k-35k\",\"positionWorkingExp\":\"3-5年\"},"
                    + "company:{companyName:\"某互联网公司\"},workLocation:{address:\"深圳\"}}}]</script>";

    @Test
    @DisplayName("parseZhilianHtml：从模拟智联 HTML 提取岗位标题、企业、城市、薪资")
    void parseZhilianHtml_extractsFields() {
        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(MOCK_HTML, "全国");
        assertThat(jobs).hasSize(1);
        WebJobSearcherService.WebJob j = jobs.get(0);
        assertThat(j.title()).isEqualTo("Java开发工程师");
        assertThat(j.salary()).contains("20k");
        // 各字段非空且标准化
        assertThat(j.applyUrl()).isNotBlank();
    }

    @Test
    @DisplayName("parseZhilianHtml：空 HTML 返回空列表不抛异常")
    void parseZhilianHtml_blankHtml_returnsEmpty() {
        assertThat(svc.parseZhilianHtml("", "全国")).isEmpty();
        assertThat(svc.parseZhilianHtml(null, "全国")).isEmpty();
    }

    @Test
    @DisplayName("parseZhilianHtml：无岗位标题时返回空")
    void parseZhilianHtml_noPosition_returnsEmpty() {
        assertThat(svc.parseZhilianHtml("<html><body>无岗位</body></html>", "全国")).isEmpty();
    }

    @Test
    @DisplayName("parseZhilianHtml：单源最多返回 8 条（MAX_PER_SOURCE 裁剪）")
    void parseZhilianHtml_capsAtEight() {
        StringBuilder html = new StringBuilder("<script>var data=[");
        for (int i = 1; i <= 10; i++) {
            html.append("{\"position\":{\"base\":{\"positionName\":\"岗位").append(i)
                    .append("\",\"salary\":\"").append(i).append("0k\"}}},");
        }
        html.append("]</script>");

        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(html.toString(), "全国");

        assertThat(jobs).hasSize(8);
        assertThat(jobs.get(0).title()).isEqualTo("岗位1");
        assertThat(jobs.get(7).title()).isEqualTo("岗位8");
    }

    @Test
    @DisplayName("parseZhilianHtml：缺失字段回退默认值（企业/城市/薪资/学历/经验）")
    void parseZhilianHtml_missingFieldsUseDefaults() {
        String html = "<script>{\"positionName\":\"数据分析师\"}</script>";

        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(html, "全国");

        assertThat(jobs).hasSize(1);
        WebJobSearcherService.WebJob j = jobs.get(0);
        assertThat(j.company()).isEqualTo("智联招聘");
        assertThat(j.location()).isEqualTo("全国");
        assertThat(j.salary()).isEqualTo("面议");
        assertThat(j.degree()).isEmpty();
        assertThat(j.deadline()).isEmpty();
        assertThat(j.applyUrl()).isEqualTo("https://sou.zhaopin.com");
    }

    @Test
    @DisplayName("parseZhilianHtml：指定城市时缺失城市字段回退为 location")
    void parseZhilianHtml_nonNationalLocationUsedAsCity() {
        String html = "<script>{\"positionName\":\"后端开发\"}</script>";

        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(html, "深圳");

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).location()).isEqualTo("深圳");
    }

    @Test
    @DisplayName("parseZhilianHtml：标题为空白时跳过该条，后续岗位正常解析")
    void parseZhilianHtml_blankTitleSkipped() {
        String html = "<script>{\"positionName\":\"   \"},{\"positionName\":\"有效岗位\"}</script>";

        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(html, "全国");

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("有效岗位");
    }

    @Test
    @DisplayName("parseZhilianHtml：\\u003d 转义为 = 并去除包裹引号")
    void parseZhilianHtml_unescapesUnicodeEquals() {
        // 源码中拆分拼接，避免 Java unicode escape 在编译期被提前解码
        String escape = "\\" + "u003d";
        String html = "<script>{\"positionName\":\"Java" + escape + "高级工程师\"}</script>";

        List<WebJobSearcherService.WebJob> jobs = svc.parseZhilianHtml(html, "全国");

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).title()).isEqualTo("Java=高级工程师");
    }

    // ─────────────── 多源聚合（v1.34.1 P1 修复回归）───────────────
    // 背景：4 个招聘源共享同一个总列表，且每源用「共享列表 size >= MAX_PER_SOURCE」判断中断。
    // 结果首个源填满 8 条后，后续源首次判断即中断、恒贡献 0 条，MAX_TOTAL(12) 永不达到，
    // 「多源并行提升命中率」的设计意图完全落空。

    private static List<WebJobSearcherService.WebJob> jobs(int n, String prefix) {
        List<WebJobSearcherService.WebJob> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new WebJobSearcherService.WebJob(prefix + i, "公司", "深圳", "面议", "", "", ""));
        }
        return list;
    }

    @Test
    @DisplayName("mergeFromSource：单源最多并入 limit 条")
    void mergeFromSource_capsPerSource() {
        List<WebJobSearcherService.WebJob> out = new ArrayList<>();

        int added = WebJobSearcherService.mergeFromSource(out, jobs(20, "A"), 8);

        assertThat(added).isEqualTo(8);
        assertThat(out).hasSize(8);
        assertThat(out.get(0).title()).isEqualTo("A0");
    }

    @Test
    @DisplayName("mergeFromSource：第一个源填满后，第二个源仍能正常贡献（P1 回归）")
    void mergeFromSource_secondSourceStillContributes() {
        List<WebJobSearcherService.WebJob> out = new ArrayList<>();

        WebJobSearcherService.mergeFromSource(out, jobs(8, "A"), 8);   // 首源填满 8 条
        int added2 = WebJobSearcherService.mergeFromSource(out, jobs(8, "B"), 8);

        // 修复前：第二个源首次判断 out.size()=8 >= 8 即 break，added2 == 0
        assertThat(added2).isEqualTo(8);
        assertThat(out).hasSize(16);
        assertThat(out.get(8).title()).isEqualTo("B0");
    }

    @Test
    @DisplayName("mergeFromSource：多源可累计到全局上限之上，由调用方按 MAX_TOTAL 裁剪")
    void mergeFromSource_aggregatesAcrossSourcesThenTruncated() {
        List<WebJobSearcherService.WebJob> out = new ArrayList<>();
        for (String p : List.of("A", "B", "C", "D")) {
            WebJobSearcherService.mergeFromSource(out, jobs(8, p), 8);
        }

        // 4 源 × 8 条 = 32 条并入（MAX_TOTAL 由 searchWeb 负责裁剪）
        assertThat(out).hasSize(32);
    }

    @Test
    @DisplayName("mergeFromSource：空源/null 源返回 0 且不抛异常")
    void mergeFromSource_nullOrEmptySource() {
        List<WebJobSearcherService.WebJob> out = new ArrayList<>();

        assertThat(WebJobSearcherService.mergeFromSource(out, null, 8)).isZero();
        assertThat(WebJobSearcherService.mergeFromSource(out, List.of(), 8)).isZero();
        assertThat(out).isEmpty();
    }
}