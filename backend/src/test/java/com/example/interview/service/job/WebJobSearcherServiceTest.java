package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}