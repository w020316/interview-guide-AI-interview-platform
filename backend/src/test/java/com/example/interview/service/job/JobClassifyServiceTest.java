package com.example.interview.service.job;

import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 岗位智能分类服务单测（mock ChatModel 接口——项目曾因注入具体类导致启动失败）
 *
 * <p>覆盖：AI 正常 JSON 数组（含 index 乱序归位 P2-13）、枚举值 normalize、
 * 非数组/异常降级规则分类、分批调用（每批 10 条）、规则分类关键词映射。
 */
@DisplayName("岗位智能分类服务测试")
class JobClassifyServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final JobClassifyService service = new JobClassifyService(chatModel);

    private static ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static JobDto dto(String externalId, String title, String company, String description) {
        return new JobDto(externalId, title, company, null, null,
                "深圳", "20k", null, null, null, null,
                "https://x.com/" + externalId, description, null, null);
    }

    @Test
    @DisplayName("classifyBatch: AI 返回 JSON 数组按 index 归位（乱序/枚举 normalize/标签拼接）")
    void classifyBatch_aiJsonMappedByIndex() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                [
                  {"index":2,"industry":"金融行业","jobType":"技术开发","tags":["校招","高薪"]},
                  {"index":1,"industry":"互联网","jobType":"技术"},
                  {"index":99,"industry":"越界","jobType":"越界"}
                ]
                """));

        var result = service.classifyBatch(List.of(
                dto("j1", "Java 工程师", "阿里", "开发核心系统"),
                dto("j2", "量化开发", "中金", "金融系统开发")));

        assertThat(result).hasSize(2);
        // index=1 归位到第 1 个岗位；tags 缺失为 null
        assertThat(result.get(0).industry()).isEqualTo("互联网");
        assertThat(result.get(0).jobType()).isEqualTo("技术");
        assertThat(result.get(0).tags()).isNull();
        // index=2 归位到第 2 个岗位；"金融行业" 包含"金融"→normalize 为枚举值
        assertThat(result.get(1).industry()).isEqualTo("金融");
        assertThat(result.get(1).jobType()).isEqualTo("技术");
        assertThat(result.get(1).tags()).isEqualTo("校招,高薪");
    }

    @Test
    @DisplayName("classifyBatch: AI 缺失槽位（乱序跳号）走规则兜底")
    void classifyBatch_missingSlotFallsBackToRule() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(
                "[{\"index\":1,\"industry\":\"互联网\",\"jobType\":\"技术\"}]"));

        var result = service.classifyBatch(List.of(
                dto("j1", "Java 工程师", "阿里", null),
                dto("j2", "银行系统开发", "某银行", null)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).industry()).isEqualTo("互联网");
        // 第 2 岗位 AI 未返回 → 规则分类：银行→金融，开发→技术
        assertThat(result.get(1).industry()).isEqualTo("金融");
        assertThat(result.get(1).jobType()).isEqualTo("技术");
        assertThat(result.get(1).tags()).isEqualTo("校招");
    }

    @Test
    @DisplayName("classifyBatch: AI 返回非 JSON 数组时整体降级规则分类")
    void classifyBatch_nonArrayResponseFallsBackToRule() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("{\"not\":\"array\"}"));

        var result = service.classifyBatch(List.of(
                dto("j1", "银行量化分析师", "某基金", null),
                dto("j2", "电商产品经理", "某互联网公司", null)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).industry()).isEqualTo("金融");
        assertThat(result.get(0).jobType()).isEqualTo("金融");
        assertThat(result.get(1).industry()).isEqualTo("互联网");
        assertThat(result.get(1).jobType()).isEqualTo("产品");
        assertThat(result.get(1).tags()).isEqualTo("校招");
    }

    @Test
    @DisplayName("classifyBatch: AI 调用抛异常时逐条降级规则分类，不影响入库主流程")
    void classifyBatch_aiThrowsFallsBackToRule() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("AI 网关超时"));

        var result = service.classifyBatch(List.of(
                dto("j1", "医院信息系统开发", "某医院", null),
                dto("j2", "神秘岗位", "无名企业", null)));

        assertThat(result).hasSize(2);
        // 医院→医疗，开发→技术
        assertThat(result.get(0).industry()).isEqualTo("医疗");
        assertThat(result.get(0).jobType()).isEqualTo("技术");
        // 无关键词命中 → 其他/综合
        assertThat(result.get(1).industry()).isEqualTo("其他");
        assertThat(result.get(1).jobType()).isEqualTo("综合");
    }

    @Test
    @DisplayName("classifyBatch: 超过 10 条按批拆分调用 AI")
    void classifyBatch_splitsBatchesOfTen() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(
                "[{\"index\":1,\"industry\":\"互联网\",\"jobType\":\"技术\"}]"));

        List<JobDto> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            items.add(dto("j" + i, "岗位" + i, "公司" + i, null));
        }

        var result = service.classifyBatch(items);

        assertThat(result).hasSize(12);
        verify(chatModel, times(2)).call(any(Prompt.class));
        // 每批仅 index=1 的岗位拿到 AI 结果，其余走规则兜底
        assertThat(result.get(0).industry()).isEqualTo("互联网");
        assertThat(result.get(1).industry()).isEqualTo("其他");
        assertThat(result.get(10).industry()).isEqualTo("互联网");
    }

    @Test
    @DisplayName("classifyBatch: 描述超 150 字截断送模型；AI 返回未知枚举值 normalize 为 null")
    void classifyBatch_longDescriptionTruncatedAndUnknownEnumNulled() {
        StringBuilder longDesc = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longDesc.append("描");
        }
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(
                "[{\"index\":1,\"industry\":\"航天科技\",\"jobType\":\"航天员\",\"tags\":[\"冷门\"]}]"));

        var result = service.classifyBatch(List.of(dto("j1", "神秘岗位", "某公司", longDesc.toString())));

        assertThat(result).hasSize(1);
        // "航天科技"/"航天员" 不在限定枚举内 → normalize 为 null（由调用方兜底）
        assertThat(result.get(0).industry()).isNull();
        assertThat(result.get(0).jobType()).isNull();
        assertThat(result.get(0).tags()).isEqualTo("冷门");
        // 验证送入模型的 prompt 中描述已被截断为前 150 字
        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getContents();
        assertThat(promptText).contains("描".repeat(150)).doesNotContain("描".repeat(151));
    }

    @Test
    @DisplayName("classifyByRule: 行业/职位类型关键词映射与兜底值")
    void classifyByRule_keywordMapping() {
        assertRule("银行量化分析师", "某基金公司", "金融", "金融");
        assertRule("小学教师招聘", "某学校", "教育", "综合");
        assertRule("护士", "某医院", "医疗", "综合");
        assertRule("电商运营专员", "某互联网公司", "互联网", "运营");
        assertRule("芯片封装专员", "某半导体公司", "制造", "综合");
        assertRule("无人机飞手", "某公司", "硬件", "综合");
        assertRule("神秘岗位", "无名企业", "其他", "综合");
    }

    private void assertRule(String title, String company, String expectedIndustry, String expectedJobType) {
        var c = service.classifyByRule(dto("x", title, company, null));
        assertThat(c.industry()).as("industry of %s", title).isEqualTo(expectedIndustry);
        assertThat(c.jobType()).as("jobType of %s", title).isEqualTo(expectedJobType);
        assertThat(c.tags()).isEqualTo("校招");
    }

    @Test
    @DisplayName("classifyByRule: 描述中的关键词也参与匹配")
    void classifyByRule_matchesDescriptionKeywords() {
        var c = service.classifyByRule(dto("x", "急聘英才", "某公司", "负责光伏电站运维"));
        assertThat(c.industry()).isEqualTo("能源");
    }

    // ─────────── AI 并发闸门（v1.34.1 P1-3 / P3-6 修复回归）───────────
    // 背景：classifyByAi 此前直接 chatModel.call，绕过全局闸门。
    // 定时/手动刷新会批量分类（每批 10 条），可与用户侧 AI 调用叠加打满上游配额，
    // 触发第三方 429 反向影响用户请求。现收敛到闸门内；又因属「系统触发、无用户在等」的
    // 批处理，使用后台专用许可池（P3-6），不与用户前台请求争抢 5 个前台许可。

    @Test
    @DisplayName("classifyBatch: AI 分类在后台闸门内执行，且不占用前台许可")
    void classifyBatch_aiCallRunsInsideConcurrencyGuard() {
        java.util.concurrent.atomic.AtomicInteger bgPermits =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger fgPermits =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            bgPermits.set(com.example.interview.ai.AiConcurrencyGuard.availableBackgroundPermits());
            fgPermits.set(com.example.interview.ai.AiConcurrencyGuard.availablePermits());
            return chatResponse("[{\"index\":1,\"industry\":\"互联网\",\"jobType\":\"技术\"}]");
        });

        var result = service.classifyBatch(List.of(dto("j1", "Java 工程师", "阿里", "开发")));

        assertThat(result).hasSize(1);
        assertThat(bgPermits.get()).as("应在后台闸门内（后台许可 2-1=1）").isEqualTo(1);
        assertThat(fgPermits.get()).as("前台许可不应被后台任务占用").isEqualTo(5);
        // 调用结束后两类许可必须全部归还，否则闸门会逐渐枯竭
        assertThat(com.example.interview.ai.AiConcurrencyGuard.availableBackgroundPermits()).isEqualTo(2);
        assertThat(com.example.interview.ai.AiConcurrencyGuard.availablePermits()).isEqualTo(5);
    }

    @Test
    @DisplayName("classifyBatch: 闸门排队超时时降级为规则分类，不影响入库")
    void classifyBatch_gateTimeoutFallsBackToRule() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new com.example.interview.ai.AiGateTimeoutException("AI 并发闸门排队超时（>30s），请稍后重试"));

        // 用「某互联网公司 + 工程师」使规则分类结果确定：行业=互联网、职位类型=技术
        var result = service.classifyBatch(List.of(dto("j1", "Java 工程师", "某互联网公司", "开发核心系统")));

        // 闸门超时应被 classifyBatch 捕获并走规则兜底，而非向上抛出中断入库
        assertThat(result).hasSize(1);
        assertThat(result.get(0).industry()).isEqualTo("互联网");
        assertThat(result.get(0).jobType()).isEqualTo("技术");
    }
}
