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
 * We Work Remotely 数据源测试：RSS → JobDto 的解析。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeWorkRemotely 数据源解析")
class WeWorkRemotelyJobProviderTest {

    @Mock
    private JobAgentProperties properties;

    private final WeWorkRemotelyJobProvider provider =
            new WeWorkRemotelyJobProvider(new ObjectMapper(), properties);

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title><![CDATA[Acme: Senior Backend Engineer (Anywhere)]]></title>
                <link>https://weworkremotely.com/remote-jobs/acme-senior-backend-engineer</link>
                <category>Full-Time Programming Jobs</category>
                <pubDate>Wed, 30 Sep 2026 09:00:00 +0000</pubDate>
                <description><![CDATA[<table><tr><td>我们用 Java 与 Kafka 构建高并发系统</td></tr></table>]]></description>
              </item>
              <item>
                <title><![CDATA[Senior Product Designer]]></title>
                <link>https://weworkremotely.com/remote-jobs/designer-1</link>
                <category>Full-Time Design Jobs</category>
                <pubDate>Wed, 30 Sep 2026 08:00:00 +0000</pubDate>
                <description><![CDATA[<p>Design systems</p>]]></description>
              </item>
            </channel></rss>
            """;

    @Test
    @DisplayName("「Company: Job Title (Region)」拆出公司与职位；分类映射职位类型")
    void parse_rss() {
        List<JobDto> parsed = provider.parseRss(RSS);

        assertThat(parsed).hasSize(2);
        JobDto first = parsed.get(0);
        assertThat(first.companyName()).isEqualTo("Acme");
        assertThat(first.title()).isEqualTo("Senior Backend Engineer");
        assertThat(first.location()).isEqualTo("Anywhere");
        assertThat(first.jobType()).isEqualTo("技术");
        assertThat(first.externalId()).isEqualTo("wwr-acme-senior-backend-engineer");
        assertThat(first.recruitType()).isEqualTo("SOCIAL");

        JobDto second = parsed.get(1);
        assertThat(second.title()).isEqualTo("Senior Product Designer");
        assertThat(second.companyName()).isNull();
        assertThat(second.jobType()).isEqualTo("设计");
    }

    @Test
    @DisplayName("description 的 HTML 清洗为纯文本")
    void parse_plainTextDescription() {
        List<JobDto> parsed = provider.parseRss(RSS);
        assertThat(parsed.get(0).description()).contains("我们用 Java 与 Kafka 构建高并发系统");
        assertThat(parsed.get(0).description()).doesNotContain("<table");
    }
}
