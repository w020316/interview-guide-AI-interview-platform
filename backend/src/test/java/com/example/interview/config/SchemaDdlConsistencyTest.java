package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code schema.sql} 与 {@link SchemaInitializer#DDL_LIST} 的 ALTER 一致性锁（P1-2 部署阻断项）。
 *
 * <h2>为什么需要这道锁</h2>
 *
 * <p>生产库<b>不执行</b> {@code schema.sql}：`application-prod.yml` 的 `ddl-auto: none`，
 * 表结构由 {@link SchemaInitializer} 在启动时遍历硬编码的 {@code DDL_LIST} 维护
 * （schema.sql 仅供人工初始化/参考）。也就是说「加一列」要写两处，而
 * <b>漏写第二处不会让任何测试变红</b>：
 *
 * <ul>
 *   <li>编译通过、启动正常、日志干净；</li>
 *   <li>依赖该列的功能在生产静默失效（例如 P1-2 的 {@code users.token_version}：
 *       查询抛异常 → 版本校验 fail-open 跳过 → 登出/改密/封禁永不生效）；</li>
 *   <li>只有在生产库上手工 {@code \d users} 才会发现列不存在。</li>
 * </ul>
 *
 * <p>这是本轮第三次遇到「同一份配置散落两处」（前两次：前端 {@code includes} 子串
 * vs 后端段边界前缀导致 `/autofill` 死规则；429 手写散落 10 处）。做法一致：
 * 用一条会自己变红的测试把两处绑起来。
 *
 * <h2>只锁 ALTER，不锁 CREATE TABLE</h2>
 *
 * <p>建表语句在两边格式差异大（多行拼接、列顺序、索引分开写），强行比对会做成脆测试；
 * 而「加列忘了加另一处」恰恰是最高发的形态，所以只锁 {@code ALTER ... ADD COLUMN}。
 *
 * <p>本测试不起 Spring 上下文：直接读文件 + 反射读常量，秒级完成。
 */
@DisplayName("schema.sql 与 DDL_LIST 的 ALTER 一致性（P1-2 部署阻断项）")
class SchemaDdlConsistencyTest {

    /** 匹配 `ALTER TABLE x ADD COLUMN IF NOT EXISTS y <type...>`（到分号或行尾） */
    private static final Pattern ALTER_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+\\S+\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+[^;\\n]+",
            Pattern.CASE_INSENSITIVE);

    private static final Path SCHEMA_SQL = Path.of("src", "main", "resources", "schema.sql");

    @Test
    @DisplayName("schema.sql 里的每一条 ALTER 都必须在 DDL_LIST 里存在（漏一处 = 生产静默失效）")
    void everyAlterInSchemaSqlAlsoExistsInDdlList() throws Exception {
        List<String> schemaAlters = normalizeAll(extractAlters(Files.readString(SCHEMA_SQL)));
        List<String> ddlList = normalizeAll(List.of(ddlList()));

        // 防止正则失效导致「一条都没解析到 → 空集也算通过」的假绿
        assertThat(schemaAlters)
                .as("schema.sql 应至少解析出 2 条 ALTER；若为 0 说明正则或文件路径失效，本测试失去意义")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(schemaAlters)
                .as("锚点断言：users.token_version 必须在 schema.sql 里（P1-2 的存量库补列）")
                .anyMatch(a -> a.startsWith("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS TOKEN_VERSION"));

        List<String> missing = new ArrayList<>();
        for (String alter : schemaAlters) {
            boolean present = ddlList.stream().anyMatch(ddl -> ddl.startsWith(alter));
            if (!present) {
                missing.add(alter);
            }
        }

        assertThat(missing)
                .as("以下 ALTER 只写在 schema.sql 里，生产库不会执行 schema.sql，"
                        + "等于这些列在生产永远不存在（静默失效）。请同步追加到 SchemaInitializer.DDL_LIST：%s",
                        missing)
                .isEmpty();
    }

    @Test
    @DisplayName("DDL_LIST 必须包含 users.token_version 这一条（防止被误删）")
    void ddlListContainsTokenVersionAlter() throws Exception {
        assertThat(normalizeAll(List.of(ddlList())))
                .as("DDL_LIST 缺少 users.token_version：P1-2 的吊销在生产会静默失效")
                .anyMatch(ddl -> ddl.startsWith(
                        "ALTER TABLE USERS ADD COLUMN IF NOT EXISTS TOKEN_VERSION"));
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private static String[] ddlList() throws Exception {
        Field field = SchemaInitializer.class.getDeclaredField("DDL_LIST");
        field.setAccessible(true);
        return (String[]) field.get(null);
    }

    /** 从 SQL 文本里抽取所有 ALTER ... ADD COLUMN IF NOT EXISTS 语句 */
    static List<String> extractAlters(String sql) {
        List<String> found = new ArrayList<>();
        Matcher matcher = ALTER_PATTERN.matcher(sql);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * 归一化：去注释与分号、压缩空白、统一大写。
     *
     * <p>两处的差异只有空白与行尾分号（schema.sql 每条以 {@code ;} 结束，DDL_LIST 没有），
     * 归一化后即可逐字比较；不做更宽松的模糊匹配，避免把「表名写错一个字母」也放过。
     */
    static List<String> normalizeAll(List<String> statements) {
        List<String> normalized = new ArrayList<>();
        for (String raw : statements) {
            if (raw == null) {
                continue;
            }
            String s = raw.replaceAll("--[^\\n]*", " ")   // 行注释
                    .replace(";", " ")
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) {
                normalized.add(s);
            }
        }
        return normalized;
    }
}
