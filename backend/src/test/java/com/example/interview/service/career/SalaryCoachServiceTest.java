package com.example.interview.service.career;

import com.example.interview.service.career.SalaryCoachService.Mode;
import com.example.interview.service.career.SalaryCoachService.SalaryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SalaryCoachService} 单元测试（v1.85.0 谈薪助手）。
 *
 * <p>除了常规的「合法 / 空返回 / 不可解析」，重点锁两件事：
 * <ol>
 *   <li><b>金额只由服务端算</b>：缺数据必须是 {@code null}，不能补 0；</li>
 *   <li><b>提示词里写死「禁止编造市场薪资数据」</b>：这条约束一旦被后续改动删掉，
 *       产品就会开始输出看起来很专业的假行情，而所有功能测试仍然全绿。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalaryCoachService 谈薪助手单元测试")
class SalaryCoachServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @InjectMocks
    private SalaryCoachService service;

    private static final String VALID_JSON =
            "{\"headline\":\"先问清薪数再谈月薪\","
                    + "\"analysis\":[{\"point\":\"15 薪下年薪并不低\",\"basis\":\"你填写的 22000 × 15\"}],"
                    + "\"scripts\":[{\"scene\":\"HR 问期望\",\"say\":\"我想先了解薪资结构\"}],"
                    + "\"qa\":[{\"q\":\"这是上限\",\"a\":\"理解，能否确认薪数和调薪周期\"}],"
                    + "\"warnings\":[\"确认薪数是否写进 offer\"],"
                    + "\"actions\":[\"让 HR 把 15 薪写进 offer letter\"]}";

    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    private static SalaryRequest request(Mode mode) {
        return new SalaryRequest(mode, "Java 后端", "杭州", 3,
                18000, 25000, 22000, 15, "", "手里还有一个流程中的面试");
    }

    @Test
    @DisplayName("合法返回：保留结构化内容，并附上服务端算出的金额")
    void coach_validJson_attachesNumbers() {
        stubChatClient(VALID_JSON);

        Map<String, Object> result = service.coach("user-1", request(Mode.OFFER));

        assertThat(result).containsEntry("headline", "先问清薪数再谈月薪");
        assertThat(result.get("analysis")).isInstanceOf(List.class);
        assertThat(result.get("warnings")).isEqualTo(List.of("确认薪数是否写进 offer"));

        @SuppressWarnings("unchecked")
        Map<String, Object> numbers = (Map<String, Object>) result.get("numbers");
        assertThat(numbers).containsEntry("raiseAmount", 4000)
                .containsEntry("raisePct", 22.2)
                .containsEntry("offerAnnual", 330000)
                .containsEntry("expectedGapMonthly", -3000)
                .containsEntry("expectedGapPct", -12.0);
    }

    @Test
    @DisplayName("模型返回空：回退到结构等价的空报告，但金额照常算")
    void coach_emptyResponse_usesFallbackShape() {
        stubChatClient("");

        Map<String, Object> result = service.coach("user-1", request(Mode.EXPECTATION));

        assertThat(result.get("headline")).asString().contains("没有生成有效建议");
        assertThat(result.get("analysis")).isEqualTo(List.of());
        assertThat(result.get("scripts")).isEqualTo(List.of());
        assertThat(result.get("qa")).isEqualTo(List.of());
        assertThat(result.get("actions")).isEqualTo(List.of());
        assertThat(result.get("numbers")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("模型返回纯文本：同样走兜底，不抛异常")
    void coach_unparsableResponse_usesFallbackShape() {
        stubChatClient("抱歉，我无法完成这个请求。");

        Map<String, Object> result = service.coach("user-1", request(Mode.NEGOTIATION));

        assertThat(result.get("headline")).asString().contains("没有生成有效建议");
    }

    @Test
    @DisplayName("字段类型不符（headline 是数字、analysis 是字符串）时归一到空值，前端不会崩")
    void coach_wrongShapesAreNormalized() {
        stubChatClient("{\"headline\":123,\"analysis\":\"oops\",\"warnings\":null}");

        Map<String, Object> result = service.coach("user-1", request(Mode.OFFER));

        assertThat(result).containsEntry("headline", "");
        assertThat(result.get("analysis")).isEqualTo(List.of());
        assertThat(result.get("warnings")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("提示词必须明确禁止编造市场薪资数据与未提供的事实")
    void promptForbidsFabricatedMarketData() {
        stubChatClient(VALID_JSON);

        service.coach("user-1", request(Mode.EXPECTATION));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(captor.capture());
        String prompt = captor.getValue();

        assertThat(prompt).contains("禁止输出任何市场薪资数据")
                .contains("不给薪资区间、分位、公司薪酬带宽、同行报价")
                .contains("禁止编造用户未提供的事实")
                .contains("不承诺任何谈判结果");
    }

    @Test
    @DisplayName("提示词在用户未填某项时写「未填写」，不让模型自行假设")
    void promptMarksMissingFields() {
        stubChatClient(VALID_JSON);
        SalaryRequest sparse = new SalaryRequest(Mode.EXPECTATION, "Java 后端", "", null,
                null, null, null, null, "", "");

        service.coach("user-1", sparse);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(captor.capture());
        String prompt = captor.getValue();

        assertThat(prompt).contains("未填写");
        assertThat(prompt).doesNotContain("null");
    }

    @Test
    @DisplayName("三种场景的提示词侧重不同（期望报价 / offer 评估 / 还价）")
    void promptVariesByMode() {
        stubChatClient(VALID_JSON);

        service.coach("user-1", request(Mode.OFFER));
        service.coach("user-1", request(Mode.NEGOTIATION));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, org.mockito.Mockito.times(2)).user(captor.capture());
        String offerPrompt = captor.getAllValues().get(0);
        String negotiationPrompt = captor.getAllValues().get(1);

        assertThat(offerPrompt).contains("值不值得接");
        assertThat(negotiationPrompt).contains("怎么处理僵局");
    }

    @Test
    @DisplayName("金额计算：缺数据一律 null，绝不补 0")
    void numbers_missingValuesStayNull() {
        Map<String, Object> numbers = SalaryCoachService.computeNumbers(null, null, 22000, null);

        assertThat(numbers).containsKeys("raiseAmount", "raisePct", "offerAnnual",
                "expectedGapMonthly", "expectedGapPct");
        assertThat(numbers.values()).containsOnlyNulls();
    }

    @Test
    @DisplayName("金额计算：期望月薪为 0 时不算百分比（避免除零成 Infinity）")
    void numbers_zeroExpectedDoesNotDivide() {
        Map<String, Object> numbers = SalaryCoachService.computeNumbers(18000, 0, 22000, 12);

        assertThat(numbers).containsEntry("raiseAmount", 4000)
                .containsEntry("raisePct", 22.2)
                .containsEntry("offerAnnual", 264000)
                .containsEntry("expectedGapMonthly", 22000)
                .containsEntry("expectedGapPct", null);
    }

    @Test
    @DisplayName("字段归一：缺失键全部补齐，且顺序稳定")
    void normalize_fillsAllKeys() {
        Map<String, Object> normalized = SalaryCoachService.normalize(new LinkedHashMap<>());

        assertThat(normalized.keySet()).containsExactly(
                "headline", "analysis", "scripts", "qa", "warnings", "actions");
        assertThat(normalized.values()).containsOnly("", List.of());
    }

    @Test
    @DisplayName("mode 解析：缺省为 EXPECTATION，大小写不敏感，未知值直接报错")
    void modeParsing() {
        assertThat(Mode.parse(null)).isEqualTo(Mode.EXPECTATION);
        assertThat(Mode.parse("  ")).isEqualTo(Mode.EXPECTATION);
        assertThat(Mode.parse("offer")).isEqualTo(Mode.OFFER);
        assertThat(Mode.parse("NEGOTIATION")).isEqualTo(Mode.NEGOTIATION);

        assertThatThrownBy(() -> Mode.parse("FANCY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mode 只能是 EXPECTATION / OFFER / NEGOTIATION");
    }
}
