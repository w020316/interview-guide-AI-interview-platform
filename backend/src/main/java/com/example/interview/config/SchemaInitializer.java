package com.example.interview.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 启动时幂等建表初始化器
 *
 * 背景：生产库（Supabase PostgreSQL）的表由 schema.sql 手动维护，且生产 ddl-auto 为 none。
 * 为避免每次新增表都依赖人工去生产库执行 DDL，本初始化器在应用启动时对新增表
 * 执行「CREATE TABLE IF NOT EXISTS」。
 *
 * 约束：
 * - 仅对 PostgreSQL 执行（H2 本地 profile 由 JPA ddl-auto=update 自动建表，跳过本初始化器，
 *   避免 H2 不兼容 PostgreSQL 方言导致启动失败）。
 * - DDL 均以 IF NOT EXISTS 包裹，重复执行/重启应用是安全的。
 * - 新增表时将对应建表语句追加到 {@link #DDL_LIST} 与 schema.sql，两处保持一致。
 *
 * 注意：这里只负责「增量建新表」，已存在的表结构变更（alter）不在此处理，仍需人工迁移。
 */
@Component
public class SchemaInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增表的幂等建表 DDL（PostgreSQL 方言） */
    private static final String[] DDL_LIST = {
            "CREATE TABLE IF NOT EXISTS favorite_question ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "session_id VARCHAR(64), "
                    + "question_id BIGINT, "
                    + "question TEXT NOT NULL, "
                    + "category VARCHAR(50), "
                    + "difficulty VARCHAR(20), "
                    + "reference_answer TEXT, "
                    + "user_answer TEXT, "
                    + "evaluation_score INT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_favorite_user_id ON favorite_question(user_id)",
            "CREATE TABLE IF NOT EXISTS interview_event ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "interviewer VARCHAR(100), "
                    + "location VARCHAR(200), "
                    + "note TEXT, "
                    + "interview_at TIMESTAMP NOT NULL, "
                    + "status VARCHAR(20) DEFAULT 'UPCOMING', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_interview_event_user_id ON interview_event(user_id)"
    };

    @Override
    public void run(String... args) {
        if (!isPostgreSQL()) {
            log.info("非 PostgreSQL 数据库，跳过 SchemaInitializer（由 JPA ddl-auto 建表）");
            return;
        }
        for (String ddl : DDL_LIST) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (Exception e) {
                // 建表失败不阻断启动：若该功能未使用，仍可正常访问其他接口；
                // 但错误会被记录，便于排查。
                log.warn("执行建表 DDL 失败（已忽略，功能可能不可用）：{}", e.getMessage());
            }
        }
        log.info("SchemaInitializer 完成：已确保新增表存在");
    }

    /** 检测当前数据库是否为 PostgreSQL */
    private boolean isPostgreSQL() {
        try {
            DataSource ds = jdbcTemplate.getDataSource();
            if (ds == null) return false;
            try (Connection conn = ds.getConnection()) {
                String product = conn.getMetaData().getDatabaseProductName();
                return product != null && product.toLowerCase().contains("postgres");
            }
        } catch (SQLException e) {
            log.warn("无法识别数据库类型，跳过 SchemaInitializer：{}", e.getMessage());
            return false;
        }
    }
}