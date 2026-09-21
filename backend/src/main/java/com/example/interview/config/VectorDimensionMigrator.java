package com.example.interview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 向量表维度自动迁移（v1.34.1）
 *
 * <p><b>解决的问题</b>：pgvector 表 {@code vector_store} 的 {@code embedding} 列维度在**建表时**固定，
 * 而 Spring AI 用的是 {@code CREATE TABLE IF NOT EXISTS} —— 更换 embedding 模型（维度变化）后，
 * 旧表不会自动重建，所有入库都会因维度不匹配而失败。
 *
 * <p>项目文档（DEPLOY.md / ai-full-review）此前把这一步列为**人工操作**：
 * 「清空旧的向量表：DROP TABLE IF EXISTS vector_store」——每次换 embedding 提供方都要登数据库手工执行。
 * 本迁移器把它自动化，规则刻意保守：
 * <ul>
 *   <li>仅当表**存在、为空、且维度与配置不一致**时才 DROP+按新维度重建（空表无数据可丢）；</li>
 *   <li>表非空且维度不匹配时**只告警不动作**（真实数据不能被自动删除），需人工处理；</li>
 *   <li>维度一致或表不存在时什么也不做；</li>
 *   <li>仅对 PostgreSQL 执行（H2/local 用文件型向量库，无此表）。</li>
 * </ul>
 *
 * <p><b>为什么放在 CommandLineRunner</b>：Spring AI 在 Bean 初始化阶段已按旧维度建好表；
 * 本迁移器在其后运行，DROP 后**按新维度重建同构表**（含 Spring AI 默认的 HNSW 索引），
 * 应用其余部分无需感知。
 */
@Component
@Order(50)   // 必须先于 KnowledgeSeedInitializer(@Order(100))：先重建表再播种
public class VectorDimensionMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorDimensionMigrator.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final int configuredDimension;

    public VectorDimensionMigrator(JdbcTemplate jdbcTemplate,
                                   DataSource dataSource,
                                   @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int configuredDimension) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.configuredDimension = configuredDimension;
    }

    @Override
    public void run(String... args) {
        try {
            if (!isPostgres()) {
                return; // H2/local：使用文件型向量库，无 pgvector 表
            }
            migrate();
        } catch (Exception e) {
            // 迁移失败不阻断启动：后续 embedding 调用会以明确错误暴露
            log.warn("向量表维度迁移检查失败（忽略，不影响启动）：{}", e.getMessage());
        }
    }

    private boolean isPostgres() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("postgresql");
        }
    }

    private void migrate() {
        // 1. 表是否存在 + embedding 列的实际维度（pgvector 的 atttypmod 即维度）
        Integer dim = jdbcTemplate.query(
                "SELECT a.atttypmod FROM pg_attribute a " +
                        "WHERE a.attrelid = 'vector_store'::regclass AND a.attname = 'embedding'",
                rs -> rs.next() ? rs.getInt(1) : null);
        if (dim == null) {
            log.info("向量表 vector_store 不存在，跳过维度迁移（将由向量库自动建表）");
            return;
        }

        // 2. 表非空则绝不自动动
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        if (count != null && count > 0) {
            if (dim != configuredDimension) {
                log.error("向量表维度({})与配置({})不一致，且表内有 {} 条数据——请人工迁移："
                                + "导出后 DROP TABLE vector_store 再重启应用（本迁移器不自动删除非空表）",
                        dim, configuredDimension, count);
            }
            return;
        }

        // 3. 空表且维度一致 → 无需处理
        if (dim == configuredDimension) {
            log.info("向量表维度({})与配置({})一致，无需迁移", dim, configuredDimension);
            return;
        }

        // 4. 空表 + 维度不匹配 → DROP 后按新维度重建（含 Spring AI 默认 HNSW 索引）
        log.warn("向量表为空且维度({})与配置({})不一致，自动重建", dim, configuredDimension);
        jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS vector_store ("
                + "id uuid DEFAULT gen_random_uuid() PRIMARY KEY, "
                + "content text, "
                + "metadata json, "
                + "embedding vector(" + configuredDimension + "))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS spring_ai_hnsw_index "
                + "ON vector_store USING hnsw (embedding vector_cosine_ops)");
        log.info("✓ 向量表已按维度({})重建，可直接入库", configuredDimension);
    }
}
