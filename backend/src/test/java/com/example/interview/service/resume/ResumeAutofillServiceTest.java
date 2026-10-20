package com.example.interview.service.resume;

import com.example.interview.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简历自动填充校验逻辑单元测试（v1.78.0）
 *
 * <p>重点锁定「什么算模型没读出东西」：这是本项目的老毛病——
 * 「解析失败但 HTTP 200」曾让前端渲染出一个全空却看似正常的简历面板。
 * 所以这里对「三段经历全空」必须判**不可用**，而不是当成功返回。
 */
class ResumeAutofillServiceTest {

    private final ResumeAutofillService service = new ResumeAutofillService();

    /** 一份结构完整、内容非空的合法输出 */
    private static final String VALID_JSON = """
            {"basics":{"name":"张三","title":"后端开发","phone":"13800000000","email":"z@example.com",
             "location":"上海","links":[{"label":"GitHub","url":"https://github.com/z"}]},
             "summary":"三年后端经验",
             "education":[{"school":"某某大学","degree":"本科","major":"计算机","start":"2020.09","end":"2024.06","gpa":"3.8","highlights":["一等奖学金"]}],
             "experience":[{"org":"某某科技","role":"后端开发","start":"2024.07","end":"至今","location":"上海","bullets":["主导订单服务重构"]}],
             "projects":[],"skills":[{"category":"后端","items":["Java","Spring Boot"]}],"extras":[]}
            """;

    @Test
    @DisplayName("合法输出通过校验，并补齐缺失的顶层键")
    void validOutputPasses() throws Exception {
        ResumeAutofillService.Validation v = service.validate(VALID_JSON);

        assertTrue(v.ok, "合法结构应通过");
        assertNotNull(v.json);
        // 规范化后 6 个顶层键齐全
        for (String key : new String[]{"basics", "summary", "education", "experience", "projects", "skills", "extras"}) {
            assertTrue(v.json.contains("\"" + key + "\""), "缺少顶层键 " + key);
        }
    }

    @Test
    @DisplayName("缺 basics 判不可用，并给出可执行的修正提示")
    void missingBasicsRejected() {
        ResumeAutofillService.Validation v = service.validate("""
                {"summary":"x","education":[],"experience":[],"projects":[],"skills":[],"extras":[]}
                """);

        assertFalse(v.ok);
        assertTrue(v.retryHint.contains("basics"), "重试提示必须指出缺什么");
    }

    @Test
    @DisplayName("缺任一数组段判不可用，提示里点名缺了哪几个")
    void missingArraySectionRejected() {
        ResumeAutofillService.Validation v = service.validate("""
                {"basics":{},"summary":"","education":[],"experience":[]}
                """);

        assertFalse(v.ok);
        assertTrue(v.retryHint.contains("projects"));
        assertTrue(v.retryHint.contains("skills"));
        assertTrue(v.retryHint.contains("extras"));
    }

    @Test
    @DisplayName("三段经历全空判不可用 —— 不能把「模型没读出来」当成功")
    void allContentSectionsEmptyRejected() {
        ResumeAutofillService.Validation v = service.validate("""
                {"basics":{"name":"张三"},"summary":"",
                 "education":[],"experience":[],"projects":[],
                 "skills":[{"category":"后端","items":["Java"]}],"extras":[]}
                """);

        assertFalse(v.ok, "只有技能段有内容、三段经历全空，必须判不可用");
        assertTrue(v.retryHint.contains("education") || v.retryHint.contains("重新逐段抽取"));
    }

    @Test
    @DisplayName("只有技能没有经历，也判不可用（技能段不能单独撑起一份简历）")
    void skillsOnlyRejected() {
        ResumeAutofillService.Validation v = service.validate("""
                {"basics":{"name":"张三"},"summary":"","education":[],"experience":[],"projects":[],
                 "skills":[{"category":"语言","items":["Go"]}],"extras":[{"title":"CET-6","date":"","desc":""}]}
                """);

        assertFalse(v.ok);
    }

    @Test
    @DisplayName("非法 JSON 判不可用，提示要求只输出 JSON")
    void malformedJsonRejected() {
        ResumeAutofillService.Validation v = service.validate("{这不是 JSON");

        assertFalse(v.ok);
        assertNotNull(v.retryHint);
    }

    @Test
    @DisplayName("空输出判不可用")
    void blankOutputRejected() {
        assertFalse(service.validate("").ok);
        assertFalse(service.validate(null).ok);
    }

    @Test
    @DisplayName("顶层不是对象（如数组）判不可用")
    void nonObjectRejected() {
        assertFalse(service.validate("[1,2,3]").ok);
    }

    @Test
    @DisplayName("空简历文本在调用 AI 之前就报错，不做无意义的模型调用")
    void blankResumeTextThrowsBeforeCallingModel() {
        // chatClient 未注入（null）：若实现先调模型再校验，这里会 NPE 而不是 BusinessException
        assertThrows(BusinessException.class, () -> service.autofill(""));
        assertThrows(BusinessException.class, () -> service.autofill("   "));
        assertThrows(BusinessException.class, () -> service.autofill(null));
    }

    @Test
    @DisplayName("错误文案不暴露内部实现，且给出可执行的下一步")
    void errorMessageIsActionable() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.autofill("  "));
        String msg = e.getMessage();
        assertEquals("这份简历没有可用的文本内容，无法自动填充", msg);
    }
}
