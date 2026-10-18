package com.example.interview.service.career;

import com.example.interview.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TailoredResumeService} 单元测试（v1.74.0 定制简历事实溯源）
 *
 * <p>覆盖点：
 * <ol>
 *   <li>合法 JSON 原样返回，findings 为空数组；</li>
 *   <li>结构非法（缺 rewrittenBullets / 空响应）触发重试一次，仍非法则抛 {@link BusinessException}
 *       —— 不再把兜底串当成功结果下发；</li>
 *   <li>数字溯源：编造的数字整条剔除并写 findings；原文已有的数字与中文数字（三个 ↔ 3 个）不得误杀；</li>
 *   <li>{@code stripUnsourcedNumbers} 自身异常时原样返回（安全网不能带崩主流程）；</li>
 *   <li>{@code chineseNumeral} 0-99 边界。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TailoredResumeService 定制简历单元测试")
class TailoredResumeServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @InjectMocks
    private TailoredResumeService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条结构完全合法、且数字都能在原文找到的模型输出 */
    private static final String VALID_JSON =
            "{\"matchedKeywords\":[\"Spring Boot\"],\"missingKeywords\":[\"Kafka\"],"
                    + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化，响应时间下降 40%\","
                    + "\"rewritten\":\"负责订单系统性能优化，响应时间下降 40%，支撑大促峰值\","
                    + "\"reason\":\"贴合 JD 对高并发经验的要求\"}],"
                    + "\"summary\":\"懂业务的 Java 后端\",\"suggestions\":[\"补充 QPS 数据\"]}";

    /** ChatClient 同步调用链 stub：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    /** 首次返回 first、重试返回 second 的 stub */
    private void stubChatClientSequence(String first, String second) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(first, second);
    }

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("合法 JSON：条目保留，findings 补为空数组")
    void tailor_validJson_keepsBullets() throws Exception {
        stubChatClient(VALID_JSON);

        String result = service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "熟悉 Spring Boot");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("rewrittenBullets").get(0).get("rewritten").asText()).contains("40%");
        assertThat(node.get("findings").isArray()).isTrue();
        assertThat(node.get("findings").size()).isZero();
        assertThat(node.get("summary").asText()).isEqualTo("懂业务的 Java 后端");
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("数字溯源 · 编造：原文没有的 40% 导致整条改写被剔除并写入 findings")
    void tailor_fabricatedNumber_removesBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化\","
                + "\"rewritten\":\"负责订单系统性能优化，响应时间下降 40%\",\"reason\":\"量化结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责订单系统性能优化", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        JsonNode findings = node.get("findings");
        assertThat(findings.size()).isEqualTo(1);
        assertThat(findings.get(0).get("type").asText()).isEqualTo("unsourced_number");
        assertThat(findings.get(0).get("tokens").toString()).contains("40%");
        assertThat(findings.get(0).get("rewritten").asText()).contains("响应时间下降 40%");
    }

    @Test
    @DisplayName("数字溯源 · 防自证：模型在 original 里自写数字不能给 rewritten 背书")
    void tailor_originalCannotVouchForRewritten() throws Exception {
        // 用户原文只有「负责订单系统性能优化」；模型把 40% 同时写进 original 和 rewritten，
        // 若把 original 并入溯源基准，这条编造就会被放行——这是本批要堵的红线本身
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化，响应时间下降 40%\","
                + "\"rewritten\":\"主导订单系统性能优化，响应时间下降 40%\",\"reason\":\"量化结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责订单系统性能优化", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        assertThat(node.get("findings").size()).isEqualTo(1);
        assertThat(node.get("findings").get(0).get("tokens").toString()).contains("40%");
    }

    @Test
    @DisplayName("数字溯源 · 合法：原文已有 40% 时条目保留，findings 为空")
    void tailor_sourcedNumber_keepsBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"响应时间下降 40%\","
                + "\"rewritten\":\"响应时间下降 40%，覆盖核心链路\",\"reason\":\"突出结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("响应时间下降 40%", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("数字溯源 · 中文数字兼容：原文「三个」不得误杀改写后的「3 个」")
    void tailor_chineseNumeral_keepsBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责三个核心模块\","
                + "\"rewritten\":\"负责 3 个核心模块的设计与落地\",\"reason\":\"关键词前置\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责三个核心模块", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("结构非法：第一次缺 rewrittenBullets，重试一次拿到合法结果")
    void tailor_invalidStructure_retriesOnce() throws Exception {
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(missingBullets, VALID_JSON);

        String result = service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "");

        assertThat(parse(result).get("rewrittenBullets").size()).isEqualTo(1);
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("重试后仍非法：抛 BusinessException，文案面向用户")
    void tailor_stillInvalid_throws() {
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(missingBullets, missingBullets);

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("定制简历结果格式异常");
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("空响应：走重试，最终抛 BusinessException（不得把兜底串当成功返回）")
    void tailor_emptyResponse_throwsInsteadOfFallback() {
        stubChatClient("");

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("定制简历结果格式异常");
    }

    @Test
    @DisplayName("stripUnsourcedNumbers：非法 JSON 入参不抛异常，原样返回")
    void stripUnsourcedNumbers_invalidJson_returnsAsIs() {
        String broken = "抱歉，我无法完成这个请求。";

        assertThat(TailoredResumeService.stripUnsourcedNumbers(broken, "负责订单系统")).isEqualTo(broken);
        assertThat(TailoredResumeService.stripUnsourcedNumbers(null, "负责订单系统")).isNull();
    }

    @Test
    @DisplayName("stripUnsourcedNumbers：缺 findings 字段时补空数组")
    void stripUnsourcedNumbers_addsFindingsField() throws Exception {
        String json = "{\"rewrittenBullets\":[{\"original\":\"负责三个模块\","
                + "\"rewritten\":\"负责 3 个模块\",\"reason\":\"\"}],\"summary\":\"\"}";

        JsonNode node = parse(TailoredResumeService.stripUnsourcedNumbers(json, "负责三个模块"));

        assertThat(node.get("findings").isArray()).isTrue();
        assertThat(node.get("findings").size()).isZero();
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("chineseNumeral：0-99 边界拼写")
    void chineseNumeral_boundaries() {
        assertThat(TailoredResumeService.chineseNumeral(3)).isEqualTo("三");
        assertThat(TailoredResumeService.chineseNumeral(10)).isEqualTo("十");
        assertThat(TailoredResumeService.chineseNumeral(12)).isEqualTo("十二");
        assertThat(TailoredResumeService.chineseNumeral(20)).isEqualTo("二十");
        assertThat(TailoredResumeService.chineseNumeral(25)).isEqualTo("二十五");
        assertThat(TailoredResumeService.chineseNumeral(99)).isEqualTo("九十九");
    }
}
