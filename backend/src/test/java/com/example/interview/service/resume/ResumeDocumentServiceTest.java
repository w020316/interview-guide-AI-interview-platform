package com.example.interview.service.resume;

import com.example.interview.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化简历文档规范化单元测试（v1.67.0）
 *
 * <p>这一层是「前端不必到处判空」的前提：落库前必须补齐 6 个顶层键。
 * 测试同时锁定「不静默吞掉非法输入」——非法 JSON 必须报错，
 * 否则前端会拿到 `{}` 并渲染出一个全空但看似正常的简历面板
 * （这正是项目历史上「解析失败但 HTTP 200」的老毛病）。
 */
class ResumeDocumentServiceTest {

    /** normalizeDocData 不触碰仓储，传 null 即可 */
    private final ResumeDocumentService service = new ResumeDocumentService(null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("空输入且允许空骨架时，返回结构完整的骨架")
    void blankInputReturnsSkeleton() throws Exception {
        JsonNode root = MAPPER.readTree(service.normalizeDocData(null, true));

        assertTrue(root.has("basics"));
        assertTrue(root.has("summary"));
        assertTrue(root.has("education"));
        assertTrue(root.has("experience"));
        assertTrue(root.has("projects"));
        assertTrue(root.has("skills"));
        assertTrue(root.has("extras"));
        assertTrue(root.get("basics").has("links"), "basics.links 必须是数组");
    }

    @Test
    @DisplayName("更新时传空内容直接报错，不覆盖已有简历")
    void blankInputOnUpdateThrows() {
        assertThrows(BusinessException.class, () -> service.normalizeDocData("  ", false));
    }

    @Test
    @DisplayName("非对象 JSON（如数组）视为非法")
    void nonObjectJsonRejected() {
        assertThrows(BusinessException.class, () -> service.normalizeDocData("[1,2,3]", false));
    }

    @Test
    @DisplayName("语法错误的 JSON 视为非法，不静默降级")
    void malformedJsonRejected() {
        assertThrows(BusinessException.class, () -> service.normalizeDocData("{不是 JSON", false));
    }

    @Test
    @DisplayName("缺失的顶层键被补齐，已有内容保持不变")
    void missingKeysAreFilled() throws Exception {
        String input = "{\"summary\":\"三年后端经验\",\"basics\":{\"name\":\"张三\"}}";
        JsonNode root = MAPPER.readTree(service.normalizeDocData(input, false));

        assertEquals("三年后端经验", root.get("summary").asText());
        assertEquals("张三", root.get("basics").get("name").asText());
        assertTrue(root.get("education").isArray());
        assertTrue(root.get("experience").isArray());
        assertTrue(root.get("projects").isArray());
        assertTrue(root.get("skills").isArray());
        assertTrue(root.get("extras").isArray());
        assertTrue(root.get("basics").get("links").isArray());
    }

    @Test
    @DisplayName("links 类型不对时重置为空数组，而不是原样下发")
    void wrongLinksTypeIsReset() throws Exception {
        String input = "{\"basics\":{\"name\":\"张三\",\"links\":\"https://x.com\"}}";
        JsonNode root = MAPPER.readTree(service.normalizeDocData(input, false));

        assertTrue(root.get("basics").get("links").isArray());
    }

    @Test
    @DisplayName("超出体积上限的内容被拒绝")
    void oversizedDocRejected() {
        String big = "{\"summary\":\"" + "长".repeat(ResumeDocumentService.MAX_DOC_BYTES) + "\"}";
        assertThrows(BusinessException.class, () -> service.normalizeDocData(big, false));
    }

    @Test
    @DisplayName("非法模板 ID 回落到默认模板，不抛错（旧链接不该打不开页面）")
    void unknownTemplateFallsBack() {
        assertEquals(ResumeTemplateCatalog.DEFAULT_TEMPLATE_ID,
                ResumeDocumentService.normalizeTemplateId("no-such-template"));
        assertEquals(ResumeTemplateCatalog.DEFAULT_TEMPLATE_ID,
                ResumeDocumentService.normalizeTemplateId(null));
    }

    @Test
    @DisplayName("合法模板 ID 原样保留")
    void knownTemplateKept() {
        assertEquals("tech-two-col", ResumeDocumentService.normalizeTemplateId("tech-two-col"));
    }

    @Test
    @DisplayName("标题为空时用兜底值，超长时截断")
    void titleNormalization() {
        assertEquals("未命名简历", ResumeDocumentService.normalizeTitle("   ", "未命名简历"));
        String longTitle = "标".repeat(ResumeDocumentService.MAX_TITLE_LEN + 50);
        assertEquals(ResumeDocumentService.MAX_TITLE_LEN,
                ResumeDocumentService.normalizeTitle(longTitle, "x").length());
    }

    @Test
    @DisplayName("模板目录十套齐备，且只有一套被推荐")
    void catalogHasTenTemplatesWithSingleRecommendation() {
        assertEquals(10, ResumeTemplateCatalog.all().size());
        long recommended = ResumeTemplateCatalog.all().stream().filter(t -> t.recommended()).count();
        assertEquals(1, recommended, "推荐标记全站只允许一套");
    }

    @Test
    @DisplayName("模板 ID 全局唯一（重复 ID 会让前端渲染落到错误模板）")
    void templateIdsAreUnique() {
        long distinct = ResumeTemplateCatalog.all().stream()
                .map(ResumeTemplateCatalog.Template::id)
                .distinct()
                .count();
        assertEquals(ResumeTemplateCatalog.all().size(), distinct);
    }

    @Test
    @DisplayName("模板目录的区块顺序完整覆盖六个区块")
    void everyTemplateCoversAllSections() {
        for (ResumeTemplateCatalog.Template t : ResumeTemplateCatalog.all()) {
            assertEquals(6, t.sections().size(), "模板 " + t.id() + " 区块数不对");
            assertNotNull(t.name());
            assertNotNull(t.scene());
        }
    }
}
