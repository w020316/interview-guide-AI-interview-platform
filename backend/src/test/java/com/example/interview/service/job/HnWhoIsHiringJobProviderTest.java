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
 * HN「Who is hiring?」数据源测试：评论 → JobDto 的启发式解析。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HN Who is hiring 数据源解析")
class HnWhoIsHiringJobProviderTest {

    @Mock
    private JobAgentProperties properties;

    private HnWhoIsHiringJobProvider provider() {
        return new HnWhoIsHiringJobProvider(new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("招聘评论 → JobDto：首行拆公司/职位，remote 归「全球远程」，截止=发帖+60 天")
    void parse_commentToDto() throws Exception {
        String json = """
                {"hits":[
                  {"objectID":"12345","author":"recruiter",
                   "text":"<p>Acme Corp | Senior Backend Engineer | Remote<br>我们使用 Java/Spring，待遇优厚。<a href=\\"https://acme.com/careers\\" rel=\\"nofollow\\">申请</a></p>",
                   "created_at":"2026-10-01T12:00:00Z"},
                  {"objectID":"12346","author":"someone","text":"<p>先赞后看</p>",
                   "created_at":"2026-10-01T13:00:00Z"}
                ]}
                """;

        List<JobDto> parsed = provider().parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));

        // 过短的占位评论被跳过，宁缺毋滥
        assertThat(parsed).hasSize(1);
        JobDto dto = parsed.get(0);
        assertThat(dto.externalId()).isEqualTo("hn-12345");
        assertThat(dto.title()).startsWith("Acme Corp | Senior Backend Engineer");
        assertThat(dto.companyName()).isEqualTo("Acme Corp");
        assertThat(dto.recruitType()).isEqualTo("SOCIAL");
        assertThat(dto.location()).isEqualTo("全球远程");
        // 上游只有发帖时间、没有截止时间 → deadline 留 null（前端显示「长期有效」）
        assertThat(dto.deadline()).isNull();
        assertThat(dto.description()).contains("我们使用 Java/Spring");
        // 评论区第一个外部链接作为申请入口；HN 站内链接不算
        assertThat(dto.applyUrl()).isEqualTo("https://acme.com/careers");
    }

    @Test
    @DisplayName("占位/过短评论 → 跳过，不产出「空壳岗位」")
    void parse_shortComment_skipped() {
        String json = """
                {"hits":[
                  {"objectID":"1","author":"a","text":"<p>+1</p>","created_at":"2026-10-01T12:00:00Z"},
                  {"objectID":"2","author":"b","text":"","created_at":"2026-10-01T13:00:00Z"}
                ]}
                """;
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            assertThat(provider().parse(root)).isEmpty();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
