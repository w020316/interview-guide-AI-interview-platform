package com.example.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonRepairUtil 单元测试
 * 覆盖用户报错的核心场景：单引号、中文引号、Markdown 代码块、尾随逗号等
 */
@DisplayName("JSON 修复工具测试")
class JsonRepairUtilTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("修复单引号字符串：{'suggestion': '项目 '教材ING''}")
    void repair_singleQuote_shouldConvertToDoubleQuote() throws Exception {
        // 用户报错的原始场景：AI 返回单引号 + 内嵌单引号
        String raw = "{'suggestion': '项目 '教材ING''}";
        String repaired = JsonRepairUtil.repair(raw);
        // 修复后必须可被标准 JSON 解析
        var node = mapper.readTree(repaired);
        assertThat(node.get("suggestion").asText()).contains("项目");
    }

    @Test
    @DisplayName("修复中文引号：{'suggestion'：'项目'}")
    void repair_chineseQuote_shouldConvertToAscii() throws Exception {
        String raw = "{'suggestion'：'项目'}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("suggestion").asText()).isEqualTo("项目");
    }

    @Test
    @DisplayName("修复中文双引号：{“suggestion“：“项目“}")
    void repair_chineseDoubleQuote_shouldConvertToAscii() throws Exception {
        String raw = "{“suggestion“：“项目“}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("suggestion").asText()).isEqualTo("项目");
    }

    @Test
    @DisplayName("剥离 Markdown 代码块")
    void repair_markdownFence_shouldStrip() throws Exception {
        String raw = "```json\n{\"score\": 80}\n```";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("score").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("剥离前后多余文本，只保留 JSON 主体")
    void repair_surroundingText_shouldExtractBody() throws Exception {
        String raw = "好的，以下是分析结果：\n{\"score\": 80}\n希望对你有帮助。";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("score").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("清理尾随逗号")
    void repair_trailingComma_shouldRemove() throws Exception {
        String raw = "{\"a\": 1, \"b\": 2,}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("a").asInt()).isEqualTo(1);
        assertThat(node.get("b").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("未加引号的 key 自动加引号")
    void repair_unquotedKey_shouldQuote() throws Exception {
        String raw = "{score: 80, name: \"张三\"}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("score").asInt()).isEqualTo(80);
        assertThat(node.get("name").asText()).isEqualTo("张三");
    }

    @Test
    @DisplayName("合法 JSON 不被破坏")
    void repair_validJson_shouldKeepAsIs() throws Exception {
        String raw = "{\"score\": 80, \"name\": \"张三\"}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("score").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("综合场景：单引号 + 中文引号 + 尾随逗号 + Markdown")
    void repair_complexScenario_shouldAllFix() throws Exception {
        String raw = "```json\n{'overallScore': 75, 'suggestion': '项目 '教材ING'',}\n```";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("overallScore").asInt()).isEqualTo(75);
        assertThat(node.get("suggestion").asText()).contains("项目");
    }

    @Test
    @DisplayName("空值与空白字符串安全处理")
    void repair_nullOrBlank_shouldReturnAsIs() {
        assertThat(JsonRepairUtil.repair(null)).isNull();
        assertThat(JsonRepairUtil.repair("")).isEqualTo("");
        assertThat(JsonRepairUtil.repair("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("isValid: 合法 JSON 返回 true")
    void isValid_validJson_returnsTrue() {
        assertThat(JsonRepairUtil.isValid("{\"a\":1}")).isTrue();
    }

    @Test
    @DisplayName("isValid: 非法 JSON 返回 false")
    void isValid_invalidJson_returnsFalse() {
        assertThat(JsonRepairUtil.isValid("{'a':1}")).isFalse();
    }

    @Test
    @DisplayName("repairAndLog: 已合法 JSON 直接返回原值")
    void repairAndLog_validJson_returnsOriginal() {
        String raw = "{\"a\":1}";
        String result = JsonRepairUtil.repairAndLog(raw, "test");
        assertThat(result).isEqualTo(raw);
    }

    @Test
    @DisplayName("修复字符串内裸换行符：Bad control character in string literal")
    void repair_controlCharInString_shouldEscape() throws Exception {
        // AI 在 suggestion 字段里直接返回裸换行符，导致 JSON.parse 报错
        // position 729 (line 29 column 23) 即此场景
        String raw = "{\"suggestion\": \"项目1\n项目2\"}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("suggestion").asText()).contains("项目1");
        assertThat(node.get("suggestion").asText()).contains("项目2");
    }

    @Test
    @DisplayName("修复字符串内回车+制表符：保留 JSON 缩进格式化")
    void repair_controlChars_shouldEscapeInStringOnly() throws Exception {
        // 字符串内 \n \r \t 转义，但 JSON 结构外的 \n 保留（缩进格式化）
        String raw = "{\n  \"score\": 80,\n  \"desc\": \"line1\nline2\ttabbed\"\n}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("score").asInt()).isEqualTo(80);
        assertThat(node.get("desc").asText()).isEqualTo("line1\nline2\ttabbed");
    }

    @Test
    @DisplayName("综合：单引号+裸换行+中文引号（用户实际报错场景）")
    void repair_complexWithControlChars() throws Exception {
        String raw = "{'overallScore': 72, 'dimensions': [{'name': '技术栈', 'suggestion': '建议补充：\n1. Spring Boot\n2. MySQL'}],}";
        String repaired = JsonRepairUtil.repair(raw);
        var node = mapper.readTree(repaired);
        assertThat(node.get("overallScore").asInt()).isEqualTo(72);
        assertThat(node.get("dimensions").get(0).get("suggestion").asText()).contains("Spring Boot");
    }
}
