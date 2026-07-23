package com.example.interview.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsonRepairUtil} 单元测试
 *
 * <p>覆盖 AI 返回非标准 JSON 的 8 类修复场景，与前端 jsonRepair.test.ts 对齐。
 * 不依赖 Spring 容器，纯逻辑测试，运行速度快。
 */
class JsonRepairUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode parseStrict(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Nested
    @DisplayName("基础修复")
    class BasicRepair {

        @Test
        @DisplayName("null / 空白输入原样返回")
        void nullAndBlank() {
            assertNull(JsonRepairUtil.repair(null));
            assertEquals("", JsonRepairUtil.repair(""));
            assertEquals("   ", JsonRepairUtil.repair("   "));
        }

        @Test
        @DisplayName("合法 JSON 原样通过（幂等）")
        void validJsonPassthrough() throws Exception {
            String json = "{\"name\":\"张三\",\"age\":25}";
            String repaired = JsonRepairUtil.repair(json);
            assertEquals(json, repaired);
            assertNotNull(parseStrict(repaired));
        }

        @Test
        @DisplayName("Markdown 代码块剥离（```json ... ```）")
        void stripMarkdownFence() throws Exception {
            String raw = "```json\n{\"k\":\"v\"}\n```";
            String repaired = JsonRepairUtil.repair(raw);
            assertEquals("{\"k\":\"v\"}", repaired);
            assertNotNull(parseStrict(repaired));
        }

        @Test
        @DisplayName("无语言标记的 Markdown 代码块剥离")
        void stripMarkdownFenceNoLang() throws Exception {
            String raw = "```\n{\"k\":1}\n```";
            String repaired = JsonRepairUtil.repair(raw);
            assertEquals("{\"k\":1}", repaired);
            assertNotNull(parseStrict(repaired));
        }
    }

    @Nested
    @DisplayName("引号修复")
    class QuoteRepair {

        @Test
        @DisplayName("中文双引号 \" \" 替换为 ASCII 双引号")
        void chineseDoubleQuotes() throws Exception {
            String raw = "{\"name\":\"张三\",\"city\":\"北京\"}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("张三", parseStrict(repaired).get("name").asText());
        }

        @Test
        @DisplayName("中文角标引号 「 」 作为结构性引号时替换为 ASCII 双引号")
        void cornerQuotes() throws Exception {
            // 角标引号替代 JSON 结构性双引号（AI 偶尔返回的格式）
            String raw = "{「title」:「hello」}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("hello", parseStrict(repaired).get("title").asText());
        }

        @Test
        @DisplayName("单引号字符串转双引号（key + value）")
        void singleQuoteStrings() throws Exception {
            String raw = "{'name':'李四','age':30}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("李四", parseStrict(repaired).get("name").asText());
            assertEquals(30, parseStrict(repaired).get("age").asInt());
        }

        @Test
        @DisplayName("value 内嵌单引号正确处理")
        void nestedSingleQuote() throws Exception {
            String raw = "{'desc':'it's a test'}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("it's a test", parseStrict(repaired).get("desc").asText());
        }
    }

    @Nested
    @DisplayName("结构与语法修复")
    class SyntaxRepair {

        @Test
        @DisplayName("尾随逗号清理")
        void trailingComma() throws Exception {
            String raw = "{\"a\":1,\"b\":2,}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals(1, parseStrict(repaired).get("a").asInt());
            assertEquals(2, parseStrict(repaired).get("b").asInt());
        }

        @Test
        @DisplayName("未加引号的 key 加双引号")
        void unquotedKey() throws Exception {
            String raw = "{name:\"王五\",age:40}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("王五", parseStrict(repaired).get("name").asText());
            assertEquals(40, parseStrict(repaired).get("age").asInt());
        }

        @Test
        @DisplayName("中文冒号 ：转 ASCII 冒号 :")
        void chineseColon() throws Exception {
            String raw = "{\"name\"：\"赵六\"}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("赵六", parseStrict(repaired).get("name").asText());
        }

        @Test
        @DisplayName("字符串内控制字符转义（裸换行）")
        void controlCharsInString() throws Exception {
            String raw = "{\"desc\":\"第一行\n第二行\"}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("第一行\n第二行", parseStrict(repaired).get("desc").asText());
        }

        @Test
        @DisplayName("字符串内 Tab 转义")
        void tabInString() throws Exception {
            String raw = "{\"col\":\"a\tb\"}";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("a\tb", parseStrict(repaired).get("col").asText());
        }
    }

    @Nested
    @DisplayName("复合场景")
    class ComplexScenarios {

        @Test
        @DisplayName("前后多余文本剥离（提取 JSON 主体）")
        void extractJsonBody() throws Exception {
            String raw = "好的，这是结果：\n{\"q\":\"1+1\"}\n以上是回答。";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("1+1", parseStrict(repaired).get("q").asText());
        }

        @Test
        @DisplayName("数组类型 JSON 提取")
        void extractArray() throws Exception {
            String raw = "```json\n[{\"id\":1},{\"id\":2}]\n```";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertTrue(parseStrict(repaired).isArray());
            assertEquals(2, parseStrict(repaired).size());
        }

        @Test
        @DisplayName("多重问题混合修复")
        void mixedIssues() throws Exception {
            String raw = "```json\n{'name':'钱七','tags':['a','b',],'active':true}\n```";
            String repaired = JsonRepairUtil.repair(raw);
            assertNotNull(parseStrict(repaired));
            assertEquals("钱七", parseStrict(repaired).get("name").asText());
            assertTrue(parseStrict(repaired).get("active").asBoolean());
        }
    }

    @Nested
    @DisplayName("校验方法")
    class ValidationMethods {

        @Test
        @DisplayName("isValid：合法 JSON 返回 true")
        void isValidTrue() {
            assertTrue(JsonRepairUtil.isValid("{\"a\":1}"));
            assertTrue(JsonRepairUtil.isValid("[1,2,3]"));
        }

        @Test
        @DisplayName("isValid：非法 JSON 返回 false")
        void isValidFalse() {
            assertFalse(JsonRepairUtil.isValid("{'a':1}"));
            assertFalse(JsonRepairUtil.isValid(""));
            assertFalse(JsonRepairUtil.isValid(null));
            assertFalse(JsonRepairUtil.isValid("   "));
            assertFalse(JsonRepairUtil.isValid("{a:}"));
        }

        @Test
        @DisplayName("repairAndLog：合法 JSON 原样返回")
        void repairAndLogValid() {
            String json = "{\"a\":1}";
            assertEquals(json, JsonRepairUtil.repairAndLog(json, "test"));
        }

        @Test
        @DisplayName("repairAndLog：非法 JSON 修复后返回")
        void repairAndLogInvalid() throws Exception {
            String raw = "{'a':1}";
            String result = JsonRepairUtil.repairAndLog(raw, "test");
            assertNotNull(parseStrict(result));
            assertEquals(1, parseStrict(result).get("a").asInt());
        }

        @Test
        @DisplayName("repairAndLog：null/空白原样返回")
        void repairAndLogNull() {
            assertNull(JsonRepairUtil.repairAndLog(null, "test"));
            assertEquals("", JsonRepairUtil.repairAndLog("", "test"));
        }
    }
}
