package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorDimensionMigrator} 单元测试（v1.34.1）
 *
 * <p>覆盖：维度一致跳过、空表维度不匹配自动重建、非空表拒绝自动迁移、表不存在跳过、
 * 非 PostgreSQL（H2/local）跳过。核心安全约束：**非空表绝不自动删除**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("向量表维度自动迁移测试")
class VectorDimensionMigratorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;

    private VectorDimensionMigrator newMigrator(int dim) throws SQLException {
        return newMigrator(dim, false);
    }

    private VectorDimensionMigrator newMigrator(int dim, boolean force) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        return new VectorDimensionMigrator(jdbcTemplate, dataSource, dim, force);
    }

    private void mockTableDimension(Integer dim) {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenReturn(dim);
    }

    private void mockEmpty() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    }

    private void mockNonEmpty() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
    }

    @Test
    @DisplayName("空表 + 维度不匹配 → DROP 后按新维度重建（含 HNSW 索引）")
    void emptyTableAndMismatch_rebuilds() throws Exception {
        mockTableDimension(1024);   // 旧维度
        mockEmpty();

        newMigrator(512).run(null); // 新维度 512

        verify(jdbcTemplate).execute("DROP TABLE IF EXISTS vector_store");
        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS vector_store ("
                + "id uuid DEFAULT gen_random_uuid() PRIMARY KEY, "
                + "content text, metadata json, embedding vector(512))");
        verify(jdbcTemplate).execute("CREATE INDEX IF NOT EXISTS spring_ai_hnsw_index "
                + "ON vector_store USING hnsw (embedding vector_cosine_ops)");
    }

    @Test
    @DisplayName("维度一致 → 什么都不做")
    void sameDimension_noop() throws Exception {
        mockTableDimension(512);
        mockEmpty();

        newMigrator(512).run(null);

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("非空表 + 维度不匹配 → 绝不自动删除（安全约束）")
    void nonEmptyTable_neverDropped() throws Exception {
        mockTableDimension(1024);
        mockNonEmpty();

        newMigrator(512).run(null);

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("表不存在 → 跳过（由向量库自动建表）")
    void tableMissing_skip() throws Exception {
        mockTableDimension(null);

        newMigrator(512).run(null);

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("非 PostgreSQL（H2/local profile）→ 跳过")
    void notPostgres_skip() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        new VectorDimensionMigrator(jdbcTemplate, dataSource, 512, false).run(null);

        verify(jdbcTemplate, never()).query(anyString(), any(ResultSetExtractor.class));
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("迁移器自身异常不向上抛（不阻断启动）")
    void exception_swallowed() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
        // 不应抛出
        new VectorDimensionMigrator(jdbcTemplate, dataSource, 512, false).run(null);
        assertThat(true).isTrue();
    }
}
