package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一键投递助手单元测试（v1.67.0）
 *
 * <p>重点锁定三条产品约束：
 * <ol>
 *   <li><b>不编造</b>：没有匹配关键词时，话术不得声称「有直接经验」；</li>
 *   <li><b>不抛错</b>：定制简历是历史快照，JSON 坏了也只能降级，不能让整个弹窗打不开；</li>
 *   <li><b>合规提示必须存在</b>：它承担「本平台不代投」的告知义务，不能被省略。</li>
 * </ol>
 */
class ApplyKitServiceTest {

    private final ApplyKitService service = new ApplyKitService();

    private JobApplicationEntity baseApp() {
        return JobApplicationEntity.builder()
                .id(1L)
                .userId("u1")
                .jobId(10L)
                .title("后端开发工程师")
                .companyName("某某科技有限公司")
                .applyUrl("https://example.com/job/1")
                .status("PLANNED")
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checklistOf(Map<String, Object> kit) {
        return (List<Map<String, Object>>) kit.get("checklist");
    }

    private Map<String, Object> findCheck(Map<String, Object> kit, String key) {
        return checklistOf(kit).stream()
                .filter(m -> key.equals(m.get("key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("检查项缺失：" + key));
    }

    @Test
    @DisplayName("没有定制简历时，话术不得声称具备匹配经验")
    void greetingWithoutTailoredDoesNotClaimMatch() {
        Map<String, Object> kit = service.build(baseApp());
        String greeting = (String) kit.get("greeting");

        assertFalse(greeting.contains("方面有直接的项目经验"),
                "无 matchedKeywords 时不能编造匹配声明");
        assertTrue(greeting.contains("后端开发工程师"), "话术应包含岗位名");
        assertTrue(greeting.contains("某某科技有限公司"), "话术应包含公司名");
        assertEquals(false, kit.get("hasTailored"));
    }

    @Test
    @DisplayName("有定制简历时，话术引用匹配关键词与一条成果")
    void greetingUsesMatchedKeywords() {
        String tailored = """
                {"matchedKeywords":["Java","Spring Boot","PostgreSQL"],
                 "missingKeywords":["Kubernetes"],
                 "rewrittenBullets":[{"original":"做过订单重构","rewritten":"主导订单服务重构，P99 从 820ms 降到 190ms","reason":"加数字"}]}
                """;
        JobApplicationEntity app = baseApp().toBuilder().tailoredResume(tailored).build();

        Map<String, Object> kit = service.build(app);
        String greeting = (String) kit.get("greeting");

        assertTrue(greeting.contains("Java"), "应引用匹配关键词");
        assertTrue(greeting.contains("Spring Boot"));
        assertTrue(greeting.contains("主导订单服务重构"), "应引用第一条改写成果作为证据");
        assertEquals(true, kit.get("hasTailored"));
    }

    @Test
    @DisplayName("匹配关键词最多引用 3 个，避免堆砌")
    void greetingCapsKeywords() {
        String tailored = """
                {"matchedKeywords":["A1","A2","A3","A4","A5"],
                 "missingKeywords":[],"rewrittenBullets":[]}
                """;
        JobApplicationEntity app = baseApp().toBuilder().tailoredResume(tailored).build();
        String greeting = (String) service.build(app).get("greeting");

        assertTrue(greeting.contains("A3"));
        assertFalse(greeting.contains("A4"), "超过 3 个的不应出现在话术里");
    }

    @Test
    @DisplayName("定制简历 JSON 非法时降级处理，不抛异常")
    void invalidTailoredJsonDegradesGracefully() {
        JobApplicationEntity app = baseApp().toBuilder().tailoredResume("{不是合法 JSON").build();

        Map<String, Object> kit = service.build(app);

        assertNotNull(kit);
        assertEquals(false, kit.get("hasTailored"));
        assertNotNull(kit.get("greeting"));
    }

    @Test
    @DisplayName("截止日期已过时，检查项标记为未通过并给出原因")
    void expiredDeadlineFailsCheck() {
        JobApplicationEntity app = baseApp().toBuilder()
                .deadline(LocalDate.now().minusDays(3))
                .build();

        Map<String, Object> check = findCheck(service.build(app), "deadline");

        assertEquals(false, check.get("done"));
        assertNotNull(check.get("hint"), "未通过项必须说明原因");
    }

    @Test
    @DisplayName("没有投递链接时，检查项提示去原平台手动投递")
    void missingApplyUrlFailsCheck() {
        JobApplicationEntity app = baseApp().toBuilder().applyUrl(null).build();

        Map<String, Object> check = findCheck(service.build(app), "applyUrl");

        assertEquals(false, check.get("done"));
        assertTrue(((String) check.get("hint")).contains("手动投递"));
    }

    @Test
    @DisplayName("待补关键词只作提示，检查项标为未通过并原样列出")
    void missingKeywordsAreSurfaced() {
        String tailored = """
                {"matchedKeywords":["Java"],"missingKeywords":["Kubernetes","Go"],
                 "rewrittenBullets":[{"original":"a","rewritten":"b","reason":"c"}]}
                """;
        JobApplicationEntity app = baseApp().toBuilder().tailoredResume(tailored).build();

        Map<String, Object> check = findCheck(service.build(app), "missing");

        assertEquals(false, check.get("done"));
        String hint = (String) check.get("hint");
        assertTrue(hint.contains("Kubernetes") && hint.contains("Go"));
        assertTrue(hint.contains("不要写进简历"), "必须明确提示这些词不能进简历正文");
    }

    @Test
    @DisplayName("合规提示必须存在且写明不代投")
    void complianceNoteIsAlwaysPresent() {
        Object note = service.build(baseApp()).get("compliance");

        assertNotNull(note);
        String text = (String) note;
        assertTrue(text.contains("不会代替你登录"));
        assertTrue(text.contains("本人完成"));
    }

    @Test
    @DisplayName("五个检查项齐备")
    void checklistHasAllItems() {
        assertEquals(5, checklistOf(service.build(baseApp())).size());
    }
}
