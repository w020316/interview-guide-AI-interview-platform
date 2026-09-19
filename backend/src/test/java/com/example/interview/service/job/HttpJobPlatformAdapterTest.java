package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link HttpJobPlatformAdapter} 单元测试
 *
 * <p>HTTP 路径用 {@link MockRestServiceServer} 绑定 RestClient 拦截（无真实外网请求）；
 * 适配器自建 RestClient 无法注入，通过反射以绑定了 mock server 的客户端替换。
 */
@DisplayName("HTTP 平台适配器测试")
class HttpJobPlatformAdapterTest {

    private JobAgentProperties properties;
    private HttpJobPlatformAdapter adapter;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties = new JobAgentProperties();
        adapter = new HttpJobPlatformAdapter(properties, new ObjectMapper());
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // restClient 为构造器内创建的 private final 字段，反射替换为绑定 mock server 的客户端
        ReflectionTestUtils.setField(adapter, "restClient", builder.build());
    }

    private JobAgentProperties.PlatformConfig platform(String key, String endpoint, String apiKey) {
        JobAgentProperties.PlatformConfig cfg = new JobAgentProperties.PlatformConfig();
        cfg.setEndpoint(endpoint);
        cfg.setApiKey(apiKey);
        properties.getPlatforms().put(key, cfg);
        return cfg;
    }

    @Test
    @DisplayName("fetchFor: 已配置平台携带 Bearer 鉴权头请求并解析岗位")
    void fetchFor_enabled_sendsAuthHeaderAndParses() {
        JobAgentProperties.PlatformConfig cfg = platform("zhaopin", "https://third.example.com/zhaopin", "sk-123");
        String body = """
                {"data":[{"id":"z1","title":"Java 开发","company":"某科技公司","city":"深圳",
                 "salaryDesc":"20k-35k","education":"本科","deadline":"2026-12-31"}]}
                """;
        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-123"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<JobDto> jobs = adapter.fetchFor("zhaopin", cfg);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("z1");
        assertThat(jobs.get(0).title()).isEqualTo("Java 开发");
        assertThat(jobs.get(0).location()).isEqualTo("深圳");
        assertThat(jobs.get(0).deadline()).isEqualTo(LocalDate.of(2026, 12, 31));
        server.verify();
    }

    @Test
    @DisplayName("fetchFor: 未配置 endpoint 的平台直接返回空且不发起 HTTP")
    void fetchFor_disabled_skipsHttp() {
        JobAgentProperties.PlatformConfig cfg = platform("boss", null, null);

        assertThat(adapter.fetchFor("boss", cfg)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("fetchFor: 服务端 5xx 异常向上传播（由 fetch/fetchAllByPlatform 隔离）")
    void fetchFor_serverError_propagates() {
        JobAgentProperties.PlatformConfig cfg = platform("zhaopin", "https://third.example.com/zhaopin", null);
        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.fetchFor("zhaopin", cfg))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    @DisplayName("fetchChannel: 渠道名为空时按第三方渠道处理，apiKey 为空时鉴权头为空串")
    void fetchChannel_blankNameAndNullApiKey() {
        JobAgentProperties.ChannelConfig channel = new JobAgentProperties.ChannelConfig();
        channel.setName("  ");
        channel.setEndpoint("https://third.example.com/channel");
        server.expect(requestTo("https://third.example.com/channel"))
                .andExpect(header("Authorization", ""))
                .andRespond(withSuccess("[{\"id\":\"c1\",\"title\":\"测试岗\",\"company\":\"某公司\"}]",
                        MediaType.APPLICATION_JSON));

        List<JobDto> jobs = adapter.fetchChannel(channel);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).externalId()).isEqualTo("c1");
        server.verify();
    }

    @Test
    @DisplayName("fetch: 平台+渠道聚合拉取，单渠道失败与空白 endpoint 渠道被隔离跳过")
    void fetch_aggregatesPlatformsWithIsolation() {
        platform("zhaopin", "https://third.example.com/zhaopin", "k1");
        platform("boss", "https://third.example.com/boss", null);   // 500 失败
        platform("job51", null, null);                               // 未启用
        JobAgentProperties.ChannelConfig ok = new JobAgentProperties.ChannelConfig();
        ok.setName("聚合服务A");
        ok.setEndpoint("https://third.example.com/channelA");
        JobAgentProperties.ChannelConfig blank = new JobAgentProperties.ChannelConfig();
        blank.setName("聚合服务B");
        blank.setEndpoint("   ");
        properties.getChannels().add(ok);
        properties.getChannels().add(blank);

        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"z1\",\"title\":\"T1\",\"company\":\"C1\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://third.example.com/boss"))
                .andRespond(withServerError());
        server.expect(requestTo("https://third.example.com/channelA"))
                .andRespond(withSuccess("[{\"id\":\"a1\",\"title\":\"T2\",\"company\":\"C2\"}]",
                        MediaType.APPLICATION_JSON));

        List<JobDto> all = adapter.fetch();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(JobDto::externalId).containsExactly("z1", "a1");
        server.verify();
    }

    @Test
    @DisplayName("fetchAllByPlatform: 按平台展示名分组返回，未启用平台不出现在结果中")
    void fetchAllByPlatform_groupsByDisplayName() {
        platform("zhaopin", "https://third.example.com/zhaopin", null);
        platform("boss", null, null);
        JobAgentProperties.ChannelConfig channel = new JobAgentProperties.ChannelConfig();
        channel.setName("聚合服务C");
        channel.setEndpoint("https://third.example.com/channelC");
        properties.getChannels().add(channel);

        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"z1\",\"title\":\"T1\",\"company\":\"C1\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://third.example.com/channelC"))
                .andRespond(withSuccess("[{\"id\":\"c1\",\"title\":\"T2\",\"company\":\"C2\"}]",
                        MediaType.APPLICATION_JSON));

        Map<String, List<JobDto>> grouped = adapter.fetchAllByPlatform();

        assertThat(grouped).containsOnlyKeys("智联招聘", "聚合服务C");
        assertThat(grouped.get("智联招聘")).hasSize(1);
        assertThat(grouped.get("聚合服务C")).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("fetchAllByPlatform: 渠道拉取失败被隔离，不中断其余平台结果")
    void fetchAllByPlatform_channelFailureIsolated() {
        platform("zhaopin", "https://third.example.com/zhaopin", null);
        JobAgentProperties.ChannelConfig bad = new JobAgentProperties.ChannelConfig();
        bad.setName("坏渠道");
        bad.setEndpoint("https://third.example.com/bad");
        properties.getChannels().add(bad);

        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"z1\",\"title\":\"T1\",\"company\":\"C1\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://third.example.com/bad"))
                .andRespond(withServerError());

        Map<String, List<JobDto>> grouped = adapter.fetchAllByPlatform();

        assertThat(grouped).containsOnlyKeys("智联招聘");
        assertThat(grouped.get("智联招聘")).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("isEnabled(platformKey): 未配置/空白 endpoint 为 false，配置后为 true")
    void isEnabled_byPlatformKey() {
        assertThat(adapter.isEnabled("zhaopin")).isFalse();
        platform("zhaopin", "   ", null);
        assertThat(adapter.isEnabled("zhaopin")).isFalse();
        platform("boss", "https://third.example.com/boss", null);
        assertThat(adapter.isEnabled("boss")).isTrue();
    }

    @Test
    @DisplayName("parseJobs: JSON 非法/data 非数组/空 body 时返回空列表")
    void parseJobs_invalidInputsReturnEmpty() {
        assertThat(adapter.parseJobs("P", null)).isEmpty();
        assertThat(adapter.parseJobs("P", "   ")).isEmpty();
        assertThat(adapter.parseJobs("P", "这不是 JSON")).isEmpty();
        assertThat(adapter.parseJobs("P", "{\"data\":\"不是数组\"}")).isEmpty();
    }

    @Test
    @DisplayName("parseJobs: 顶层裸数组同样可解析，recruitType 全枚举映射与日期格式兼容")
    void parseJobs_bareArrayAndEnumAndDateMappings() {
        String body = """
                [
                  {"id":"r1","title":"秋招后端","company":"A","recruitType":"校招"},
                  {"id":"r2","title":"春招前端","company":"B","recruitType":"春招"},
                  {"id":"r3","title":"社招运维","company":"C","recruitType":"社会招聘"},
                  {"id":"r4","title":"实习生","company":"D","recruitType":"实习"},
                  {"id":"r5","title":"神秘类型","company":"E","recruitType":"神秘类型"},
                  {"id":"r6","title":"无类型","company":"F"},
                  {"id":"r7","title":"斜杠日期","company":"G","closeTime":"2026/01/05"},
                  {"id":"r8","title":"坏日期","company":"H","closeTime":"not-a-date"},
                  {"id":"r9","title":"中文日期","company":"I","closeTime":"2026年1月5日"}
                ]
                """;

        List<JobDto> jobs = adapter.parseJobs("测试平台", body);

        assertThat(jobs).hasSize(9);
        assertThat(jobs.get(0).recruitType()).isEqualTo("AUTUMN");
        assertThat(jobs.get(1).recruitType()).isEqualTo("SPRING");
        assertThat(jobs.get(2).recruitType()).isEqualTo("SOCIAL");
        assertThat(jobs.get(3).recruitType()).isEqualTo("INTERN");
        assertThat(jobs.get(4).recruitType()).isEqualTo("AUTUMN");
        assertThat(jobs.get(5).recruitType()).isEqualTo("AUTUMN");
        assertThat(jobs.get(6).deadline()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(jobs.get(7).deadline()).isNull();
        assertThat(jobs.get(8).deadline()).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    @DisplayName("fetch: 渠道拉取失败被 catch 隔离，不影响平台与其余渠道结果")
    void fetch_channelFailureIsolated() {
        JobAgentProperties.ChannelConfig bad = new JobAgentProperties.ChannelConfig();
        bad.setName("坏渠道");
        bad.setEndpoint("https://third.example.com/bad");
        properties.getChannels().add(bad);
        JobAgentProperties.ChannelConfig ok = new JobAgentProperties.ChannelConfig();
        ok.setName("好渠道");
        ok.setEndpoint("https://third.example.com/ok");
        properties.getChannels().add(ok);

        server.expect(requestTo("https://third.example.com/bad"))
                .andRespond(withServerError());
        server.expect(requestTo("https://third.example.com/ok"))
                .andRespond(withSuccess("[{\"id\":\"o1\",\"title\":\"T\",\"company\":\"C\"}]",
                        MediaType.APPLICATION_JSON));

        List<JobDto> all = adapter.fetch();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).externalId()).isEqualTo("o1");
        server.verify();
    }

    @Test
    @DisplayName("fetchAllByPlatform: 平台失败隔离、空白 endpoint 渠道跳过、无名渠道回退第三方渠道")
    void fetchAllByPlatform_platformFailureAndChannelEdgeCases() {
        platform("zhaopin", "https://third.example.com/zhaopin", null); // 500 失败
        platform("boss", "https://third.example.com/boss", null);       // 正常
        JobAgentProperties.ChannelConfig blank = new JobAgentProperties.ChannelConfig();
        blank.setName("空白渠道");
        blank.setEndpoint("  ");
        properties.getChannels().add(blank);
        JobAgentProperties.ChannelConfig unnamed = new JobAgentProperties.ChannelConfig();
        unnamed.setName(null);
        unnamed.setEndpoint("https://third.example.com/unnamed");
        properties.getChannels().add(unnamed);

        server.expect(requestTo("https://third.example.com/zhaopin"))
                .andRespond(withServerError());
        server.expect(requestTo("https://third.example.com/boss"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"b1\",\"title\":\"T1\",\"company\":\"C1\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://third.example.com/unnamed"))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"title\":\"T2\",\"company\":\"C2\"}]",
                        MediaType.APPLICATION_JSON));

        Map<String, List<JobDto>> grouped = adapter.fetchAllByPlatform();

        assertThat(grouped).containsOnlyKeys("BOSS直聘", "第三方渠道");
        server.verify();
    }

    @Test
    @DisplayName("parseJobs: 缺 externalId / 缺 title / 缺 company 三类脏数据均被丢弃")
    void parseJobs_dirtyDataAllMissingKeyPaths() {
        String body = """
                {"data":[
                  {"title":"缺ID","company":"某公司"},
                  {"id":"k2","company":"缺标题"},
                  {"id":"k3","title":"缺公司"}
                ]}
                """;

        assertThat(adapter.parseJobs("测试平台", body)).isEmpty();
    }

    @Test
    @DisplayName("platform(): 返回第三方平台标识")
    void platform_returnsThirdPartyLabel() {
        assertThat(adapter.platform()).isEqualTo("第三方平台");
    }
}
