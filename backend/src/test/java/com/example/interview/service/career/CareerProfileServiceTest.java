package com.example.interview.service.career;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link CareerProfileService} 单元测试（v1.35.0 求职 Skill）
 *
 * <p>覆盖点：
 * <ol>
 *   <li>合法 JSON 原样返回；</li>
 *   <li>模型返回中文引号/单引号等非标准 JSON 时被修复为合法 JSON；</li>
 *   <li>模型返回空/无法修复时回退到结构等价的兜底 JSON（前端不会因解析失败白屏）；</li>
 *   <li>输入为空/超长时不抛异常（截断 + 消毒后仍能完成调用）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CareerProfileService 求职 Skill 单元测试")
class CareerProfileServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @InjectMocks
    private CareerProfileService service;

    private static final String VALID_MINE_JSON =
            "{\"positioning\":\"懂业务的 Java 后端\",\"assets\":[{\"evidence\":\"把慢查询改成 ES\","
                    + "\"behavior\":\"定位瓶颈并重构检索链路\",\"capability\":\"性能优化能力\","
                    + "\"jobSignals\":[\"Java 后端\",\"搜索研发\"]}],\"strengthTags\":[\"性能优化\"],"
                    + "\"blindSpots\":[\"缺少量化结果\"],\"nextSteps\":[\"补一段 QPS 数据\"]}";

    private static final String VALID_PLAN_JSON =
            "{\"whyFit\":\"技术栈高度吻合\",\"gaps\":[{\"item\":\"分布式经验\",\"level\":\"HIGH\","
                    + "\"action\":\"做一个分片 demo\"}],\"thirtyDayPlan\":[{\"week\":\"第1周\","
                    + "\"focus\":\"补齐分布式基础\",\"tasks\":[\"读完一致性哈希\"],\"deliverable\":\"一个分片 demo\"}],"
                    + "\"tracks\":[{\"track\":\"互联网中台\",\"companies\":[\"中厂\"],\"reason\":\"看重工程能力\"}],"
                    + "\"cadence\":[\"每周投 8-10 家\"]}";

    @BeforeEach
    void setUp() {
        // 各用例自行 stub，避免 Mockito 严格模式报未使用 stub
    }

    /** ChatClient 同步调用链 stub：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    @Test
    @DisplayName("mine：合法 JSON 原样返回")
    void mine_validJson() {
        stubChatClient(VALID_MINE_JSON);

        String result = service.mine("user-1", "我做过二手交易平台，把慢查询改成了 ES", "Java 后端");

        assertThat(result).contains("positioning").contains("性能优化能力").contains("Java 后端");
        verify(chatClient, times(1)).prompt();
    }

    @Test
    @DisplayName("mine：中文引号等非标准 JSON 被修复为合法 JSON")
    void mine_repairsNonStandardJson() {
        stubChatClient("{'positioning':'懂业务的开发','assets':[],'strengthTags':[],'blindSpots':[],'nextSteps':[],}");

        String result = service.mine("user-1", "经历描述", "");

        assertThat(result).contains("positioning");
        assertThat(com.example.interview.util.JsonRepairUtil.isValid(result)).isTrue();
    }

    @Test
    @DisplayName("mine：模型返回空时回退兜底结构（不抛异常、仍是合法 JSON）")
    void mine_emptyResponse_usesFallback() {
        stubChatClient("");

        String result = service.mine("user-1", "经历描述", "");

        assertThat(result).isEqualTo(CareerProfileService.MINE_FALLBACK_JSON);
        assertThat(com.example.interview.util.JsonRepairUtil.isValid(result)).isTrue();
    }

    @Test
    @DisplayName("mine：模型返回纯文本（无法修复）时回退兜底结构")
    void mine_unparsableResponse_usesFallback() {
        stubChatClient("抱歉，我无法完成这个请求。");

        String result = service.mine("user-1", "经历描述", "");

        assertThat(result).isEqualTo(CareerProfileService.MINE_FALLBACK_JSON);
    }

    @Test
    @DisplayName("mine：超长输入被截断后仍能正常调用（不抛异常）")
    void mine_longNarrative_isTruncated() {
        stubChatClient(VALID_MINE_JSON);

        String result = service.mine("user-1", "经历".repeat(5000), null);

        assertThat(result).contains("positioning");
        verify(requestSpec, times(1)).user(anyString());
    }

    @Test
    @DisplayName("plan：合法 JSON 原样返回，含 whyFit 与 30 天计划")
    void plan_validJson() {
        stubChatClient(VALID_PLAN_JSON);

        String result = service.plan("user-1", "Java 后端", "熟悉 Spring Boot", "");

        assertThat(result).contains("whyFit").contains("thirtyDayPlan").contains("第1周");
    }

    @Test
    @DisplayName("plan：模型返回空时回退兜底结构")
    void plan_emptyResponse_usesFallback() {
        stubChatClient(null);

        String result = service.plan("user-1", "Java 后端", "简历", "");

        assertThat(result).isEqualTo(CareerProfileService.PLAN_FALLBACK_JSON);
        assertThat(com.example.interview.util.JsonRepairUtil.isValid(result)).isTrue();
    }

    @Test
    @DisplayName("plan：把挖掘摘要一并送入提示词（两步分析对齐）")
    void plan_includesMineSummary() {
        stubChatClient(VALID_PLAN_JSON);

        service.plan("user-1", "Java 后端", "简历", "定位：懂业务的 Java 后端");

        verify(requestSpec).user(org.mockito.ArgumentMatchers.contains("懂业务的 Java 后端"));
    }
}
