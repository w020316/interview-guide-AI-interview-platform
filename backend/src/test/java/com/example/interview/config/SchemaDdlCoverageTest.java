package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建表清单覆盖率门禁（2026-10-31）。
 *
 * <h2>为什么必须有一条这样的门禁</h2>
 *
 * <p>{@code ai_task} 表曾经**只写进了 {@code schema.sql}**，没有进 {@link SchemaInitializer} 的
 * {@code DDL_LIST}。而 prod 是 {@code sql.init.mode=never} + {@code ddl-auto=none} ——
 * 也就是说这张表**在生产从未被创建过**。任务中心开关默认关闭把问题盖了整整一天，
 * 直到 2026-10-31 放开查询接口的开关闸门，线上 {@code GET /api/tasks} 才暴露为
 * 「数据库操作失败，请联系管理员」（500）。
 *
 * <p>这正是本项目最贵的那条教训：**已有门禁的约束不会漂移，没有门禁的约束全部漂移**。
 *
 * <h2>为什么扫实体而不是比对 schema.sql</h2>
 *
 * <p>比对 schema.sql 只能发现「写进了 schema.sql 但忘了 DDL_LIST」。
 * 扫实体能连「两边都忘了」一起发现 —— 只要有人新建了 {@code @Table} 实体，
 * 这条门禁就会报红点名，不需要任何人记得维护枚举清单。
 *
 * <p>{@link #LEGACY_TABLES} 是唯一的白名单：它们在建库时就已存在（早于
 * {@code SchemaInitializer}），本来就不需要 DDL。新增实体**不允许**往这里加。
 */
@DisplayName("建表清单覆盖所有实体（schema.sql / DDL_LIST 脱节门禁）")
class SchemaDdlCoverageTest {

    /** 建库时就存在的表：早于 SchemaInitializer，由当时的 ddl-auto 建好，不需要重复 DDL */
    private static final Set<String> LEGACY_TABLES = Set.of(
            "users", "resume", "interview_session", "interview_question");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("每个 @Table 实体都有对应的 CREATE TABLE DDL（新增实体自动纳入）")
    void everyEntityTableHasCreateDdl() throws Exception {
        Set<String> entityTables = scanEntityTables();
        assertThat(entityTables)
                .as("扫描不到实体说明包名或过滤器写错了，门禁会变成永远为真的空断言")
                .hasSizeGreaterThan(10);

        Set<String> created = tablesInDdlList();
        Set<String> missing = entityTables.stream()
                .filter(t -> !LEGACY_TABLES.contains(t))
                .filter(t -> !created.contains(t))
                .collect(Collectors.toSet());

        assertThat(missing)
                .as("这些表有实体但没有建表 DDL：prod 是 ddl-auto=none + sql.init=never，"
                        + "缺 DDL 就等于线上没有这张表，接口会在运行时 500。"
                        + "请把 CREATE TABLE 追加到 SchemaInitializer.DDL_LIST 与 schema.sql 两处")
                .isEmpty();
    }

    @Test
    @DisplayName("白名单里的 LEGACY 表不能是「其实已经有 DDL」的（避免白名单腐坏）")
    void legacyTablesAreReallyLegacy() {
        Set<String> created = tablesInDdlList();
        Set<String> redundant = LEGACY_TABLES.stream()
                .filter(created::contains)
                .collect(Collectors.toSet());

        assertThat(redundant)
                .as("这些表已经在 DDL_LIST 里了，应从 LEGACY_TABLES 移除——白名单越脏，门禁越假")
                .isEmpty();
    }

    /** 扫 {@code com.example.interview.entity} 下所有带 {@code @Table} 的实体 */
    private static Set<String> scanEntityTables() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(jakarta.persistence.Table.class));

        Set<String> tables = new HashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.interview.entity")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            jakarta.persistence.Table table = type.getAnnotation(jakarta.persistence.Table.class);
            if (table != null && !table.name().isBlank()) {
                tables.add(table.name());
            }
        }
        return tables;
    }

    private static Set<String> tablesInDdlList() {
        String[] ddl = (String[]) ReflectionTestUtils.getField(SchemaInitializer.class, "DDL_LIST");
        assertThat(ddl).as("DDL_LIST 读不到：字段被改名了，这条门禁已失效").isNotNull();

        Set<String> tables = new HashSet<>();
        for (String statement : ddl) {
            Matcher m = CREATE_TABLE.matcher(statement);
            if (m.find()) {
                tables.add(m.group(1).toLowerCase());
            }
        }
        return tables;
    }
}
