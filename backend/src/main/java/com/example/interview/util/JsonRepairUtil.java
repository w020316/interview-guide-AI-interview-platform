package com.example.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 修复工具
 * 解决 AI 模型返回非标准 JSON 的问题：
 * 1. 单引号字符串 -> 双引号字符串
 * 2. 中文引号 ' ' " " -> 标准双引号
 * 3. 尾随逗号清理
 * 4. 未加引号的 key
 * 5. 控制字符清理
 * 6. Markdown 代码块剥离
 *
 * 这是前端报错 "Unexpected token '''" 的根因修复。
 */
public final class JsonRepairUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonRepairUtil.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 中文左单引号 ’  右单引号 ’ */
    private static final Pattern CN_SINGLE_QUOTE = Pattern.compile("[\u2018\u2019]");
    /** 中文左双引号 “  右双引号 ” */
    private static final Pattern CN_DOUBLE_QUOTE = Pattern.compile("[\u201C\u201D]");
    /** 中文左单角标 「 右单角标 」 */
    private static final Pattern CN_CORNER_QUOTE = Pattern.compile("[\u300C\u300D\u300E\u300F]");

    /** 匹配单引号字符串：'...' （非贪婪，支持转义） */
    private static final Pattern SINGLE_QUOTE_STRING = Pattern.compile(
            "'((?:\\\\.|[^'\\\\])*)'"
    );

    /** 匹配未加引号的 JSON key：{ key: ... } 或 , key: ... */
    private static final Pattern UNQUOTED_KEY = Pattern.compile(
            "([{,])\\s*([A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5\\-]*)\\s*:"
    );

    /** 尾随逗号：}, ] 之前的逗号 */
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    private JsonRepairUtil() {}

    /**
     * 修复 AI 返回的 JSON 字符串
     * @param raw AI 原始返回
     * @return 可被标准 JSON.parse 解析的字符串
     */
    public static String repair(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String s = raw;

        // 1. 剥离 Markdown 代码块 ```json ... ``` 或 ``` ... ```
        s = stripMarkdownFence(s);

        // 2. 提取首个 JSON 对象/数组（去除前后多余文本）
        s = extractJsonBody(s);

        // 3. 中文引号统一替换为 ASCII 双引号
        s = CN_DOUBLE_QUOTE.matcher(s).replaceAll("\"");
        s = CN_CORNER_QUOTE.matcher(s).replaceAll("\"");

        // 4. 中文单引号先转 ASCII 单引号，再走单引号字符串处理
        s = CN_SINGLE_QUOTE.matcher(s).replaceAll("'");

        // 4.5 中文冒号 ：(U+FF1A) -> ASCII 冒号 :
        s = s.replace("\uFF1A", ":");

        // 5. 单引号字符串 -> 双引号字符串
        s = convertSingleQuotedStrings(s);

        // 6. 未加引号的 key -> 加双引号
        s = UNQUOTED_KEY.matcher(s).replaceAll("$1\"$2\":");

        // 7. 尾随逗号清理
        s = TRAILING_COMMA.matcher(s).replaceAll("$1");

        // 8. 清理控制字符（除 \n \r \t 外）
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        return s;
    }

    /**
     * 修复并校验：修复后尝试解析，成功返回 true
     */
    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            MAPPER.readTree(raw);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 修复并尝试解析，返回是否成功（用于日志记录）
     */
    public static String repairAndLog(String raw, String context) {
        if (raw == null || raw.isBlank()) return raw;

        // 先试原值能否解析
        try {
            MAPPER.readTree(raw);
            return raw; // 已是合法 JSON，无需修复
        } catch (Exception ignored) {
            // 继续走修复流程
        }

        String repaired = repair(raw);
        try {
            MAPPER.readTree(repaired);
            if (context != null) {
                log.debug("JSON 修复成功 context={}", context);
            }
            return repaired;
        } catch (Exception e) {
            log.warn("JSON 修复后仍无法解析 context={} err={} raw(前200)={}",
                    context, e.getMessage(),
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return repaired; // 返回修复后的，至少比原值好
        }
    }

    /** 剥离 ```json ... ``` 或 ``` ... ``` 代码块 */
    private static String stripMarkdownFence(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            // 去掉首行 ```json 或 ```
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // 去掉末尾 ```
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
            return trimmed.trim();
        }
        return trimmed;
    }

    /**
     * 从可能包含前后多余文本的字符串中提取 JSON 主体
     * 定位第一个 { 或 [，与最后一个 } 或 ]
     */
    private static String extractJsonBody(String s) {
        int start = -1;
        int end = -1;
        char startChar = 0;
        char endChar = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                startChar = c;
                endChar = (c == '{') ? '}' : ']';
                break;
            }
        }
        if (start < 0) return s;

        // 从后往前找匹配的结束符
        for (int i = s.length() - 1; i > start; i--) {
            if (s.charAt(i) == endChar) {
                end = i + 1;
                break;
            }
        }
        if (end <= start) return s;

        return s.substring(start, end);
    }

    /**
     * 将单引号字符串转换为双引号字符串
     * 正确处理字符串内嵌套单引号的情况：
     * '项目 '教材ING'' -> "项目 '教材ING'"
     *
     * 分两阶段处理：
     * 1. 先处理 key 单引号：'key': -> "key":（非贪婪，到第一个 ':')
     * 2. 再处理 value 单引号：: 'value' -> : "value"（贪婪到最后一个 ',}])
     */
    private static String convertSingleQuotedStrings(String s) {
        // 阶段 1：处理 key 单引号字符串（'xxx': -> "xxx":）
        // 非贪婪匹配，key 不会包含单引号或冒号
        Pattern keyPattern = Pattern.compile("'([^']*?)'(?=\\s*:)");
        s = keyPattern.matcher(s).replaceAll(mr -> {
            String content = mr.group(1).replace("\"", "\\\"");
            return "\"" + content + "\"";
        });

        // 阶段 2：处理 value 单引号字符串
        // value 可能内嵌单引号，结束符是最后一个 ' 后紧跟 , } ]
        // 使用状态机逐个处理
        StringBuilder result = new StringBuilder(s.length() + 16);
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = findValueEnd(s, i + 1);
                if (end > i) {
                    String content = s.substring(i + 1, end);
                    content = content.replace("\\'", "'");
                    content = content.replace("\"", "\\\"");
                    result.append('"').append(content).append('"');
                    i = end + 1;
                    continue;
                }
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    /**
     * 查找 value 单引号字符串的结束位置
     * 扫描到字符串末尾或下一个结构字符，取最后一个合法的闭单引号
     */
    private static int findValueEnd(String s, int start) {
        int len = s.length();
        int lastValidEnd = -1;
        for (int i = start; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                while (j < len && Character.isWhitespace(s.charAt(j))) j++;
                if (j >= len) {
                    lastValidEnd = i;
                    break; // 字符串末尾，结束扫描
                }
                char next = s.charAt(j);
                if (next == ',' || next == '}' || next == ']') {
                    lastValidEnd = i;
                    break; // value 结束符，取第一个匹配的结束位置
                    // 注意：这里用 break 而非继续，避免跨字段贪婪匹配
                    // 内嵌单引号如 '教材ING' 后面跟的是 ' 而非结构字符，不会误判
                }
            }
        }
        return lastValidEnd;
    }
}
