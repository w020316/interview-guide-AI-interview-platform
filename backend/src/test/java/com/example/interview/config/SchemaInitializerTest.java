package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SchemaInitializer} 单元测试（第三批 DDL 落地 + 可观测判据）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchemaInitializer 单元测试")
class SchemaInitializerTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private String[] ddlList() throws Exception {
        Field f = SchemaInitializer.class.getDeclaredField("DDL_LIST");
        f.setAccessible(true);
        return (String[]) f.get(null);
    }

    @Test
    @DisplayName("DDL_LIST 含第三批 4 条：eval_detail / preference / data_import_log + 索引")
    void ddlList_containsBatch3Ddl() throws Exception {
        String all = String.join("\n", Arrays.asList(ddlList()));
        assertThat(all).contains("ALTER TABLE interview_question ADD COLUMN IF NOT EXISTS eval_detail TEXT");
        assertThat(all).contains("ALTER TABLE job_favorite ADD COLUMN IF NOT EXISTS preference VARCHAR(20)");
        assertThat(all).contains("CREATE TABLE IF NOT EXISTS data_import_log");
        assertThat(all).contains("CREATE INDEX IF NOT EXISTS idx_data_import_log_user");
        // 唯一约束（幂等键）
        assertThat(all).contains("uk_data_import_log_user_import UNIQUE (user_id, import_id)");
    }

    @Test
    @DisplayName("verifySchema：全部列/表就位时返回 0（缺省项为 0）")
    void verifySchema_allPresent_returnsZero() {
        // 列校验为 4 参调用（sql, Integer, table, column），表校验为 3 参调用（sql, Integer, table）
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(1);
        SchemaInitializer initializer = new SchemaInitializer(jdbcTemplate);

        assertThat(initializer.verifySchema()).isZero();
    }

    @Test
    @DisplayName("verifySchema：某列缺失时返回 >0（可观测判据，暴露静默失效）")
    void verifySchema_missingColumn_returnsPositive() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    String sql = (String) args[0];
                    if (sql.contains("columns")) {
                        String table = String.valueOf(args[2]);
                        String column = String.valueOf(args[3]);
                        // 模拟 eval_detail 列没建成
                        return ("interview_question".equals(table) && "eval_detail".equals(column)) ? 0 : 1;
                    }
                    return 1;
                });
        SchemaInitializer initializer = new SchemaInitializer(jdbcTemplate);

        assertThat(initializer.verifySchema()).isPositive();
    }

    @Test
    @DisplayName("verifySchema：自检查询异常时也返回 >0（不静默通过）")
    void verifySchema_queryThrows_returnsPositive() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenThrow(new RuntimeException("information_schema 不可访问"));
        SchemaInitializer initializer = new SchemaInitializer(jdbcTemplate);

        assertThat(initializer.verifySchema()).isPositive();
    }
}
