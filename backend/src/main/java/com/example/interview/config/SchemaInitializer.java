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
            // P2-20：面试题目表补乐观锁版本列（存量生产库生效；表不存在时失败仅告警，
            // 新表由实体 @Version 建列）
            "ALTER TABLE interview_question ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0",
            // P1-06：收藏唯一约束（存量库也生效；若历史数据已有重复行，此 DDL 失败仅告警不阻断启动，
            // 需人工清理重复数据后重启再生效）
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_favorite_question_user_question "
                    + "ON favorite_question(user_id, question_id)",
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
            "CREATE INDEX IF NOT EXISTS idx_interview_event_user_id ON interview_event(user_id)",
            // v1.61.0 投递 ↔ 面试时序视图（竞品清单 #4）：日历事件关联到投递记录。
            // ⚠️ 用 ALTER 而非改上面的 CREATE TABLE —— 生产库该表已存在，
            // `CREATE TABLE IF NOT EXISTS` 对已存在的表是空操作，只改 CREATE 不会生效。
            "ALTER TABLE interview_event ADD COLUMN IF NOT EXISTS application_id BIGINT",
            "CREATE INDEX IF NOT EXISTS idx_interview_event_application ON interview_event(application_id)",
            "CREATE TABLE IF NOT EXISTS job_posting ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "platform VARCHAR(50) NOT NULL, "
                    + "external_id VARCHAR(128) NOT NULL, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "company_name VARCHAR(200) NOT NULL, "
                    + "industry VARCHAR(50), "
                    + "job_type VARCHAR(50), "
                    + "location VARCHAR(100), "
                    + "salary VARCHAR(100), "
                    + "degree VARCHAR(50), "
                    + "experience VARCHAR(50), "
                    + "recruit_type VARCHAR(20), "
                    + "deadline DATE, "
                    + "apply_url VARCHAR(500), "
                    + "description TEXT, "
                    + "requirements TEXT, "
                    + "tags VARCHAR(500), "
                    + "active BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_job_posting_platform_external UNIQUE (platform, external_id))",
            "CREATE INDEX IF NOT EXISTS idx_job_posting_deadline ON job_posting(deadline)",
            "CREATE INDEX IF NOT EXISTS idx_job_posting_recruit_type ON job_posting(recruit_type)",
            "CREATE INDEX IF NOT EXISTS idx_job_posting_active ON job_posting(active)",
            "CREATE TABLE IF NOT EXISTS job_favorite ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "job_id BIGINT NOT NULL, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "company_name VARCHAR(200) NOT NULL, "
                    + "platform VARCHAR(50), "
                    + "location VARCHAR(100), "
                    + "salary VARCHAR(100), "
                    + "deadline DATE, "
                    + "apply_url VARCHAR(500), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_job_favorite_user_job UNIQUE (user_id, job_id))",
            "CREATE INDEX IF NOT EXISTS idx_job_favorite_user ON job_favorite(user_id)",
            "CREATE TABLE IF NOT EXISTS agent_conversation ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "title VARCHAR(60) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_agent_conv_user ON agent_conversation(user_id)",
            "CREATE TABLE IF NOT EXISTS agent_message ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "conversation_id BIGINT NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE, "
                    + "role VARCHAR(20) NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_agent_msg_conv ON agent_message(conversation_id)",
            // v1.35.0：投递台账（人工确认投递 + 回复监测 + 定制简历）
            "CREATE TABLE IF NOT EXISTS job_application ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "job_id BIGINT NOT NULL, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "company_name VARCHAR(200) NOT NULL, "
                    + "platform VARCHAR(50), "
                    + "location VARCHAR(100), "
                    + "salary VARCHAR(100), "
                    + "deadline DATE, "
                    + "apply_url VARCHAR(500), "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PLANNED', "
                    + "tailored_resume TEXT, "
                    + "note TEXT, "
                    + "applied_at TIMESTAMP, "
                    + "last_reply_at TIMESTAMP, "
                    + "next_action_at TIMESTAMP, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_job_application_user_job UNIQUE (user_id, job_id))",
            "CREATE INDEX IF NOT EXISTS idx_job_application_user ON job_application(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_job_application_status ON job_application(status)",
            // v1.36.0：面试故事库（STAR 故事资产 + 六项质检快照）
            "CREATE TABLE IF NOT EXISTS story_bank ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "title VARCHAR(200) NOT NULL, "
                    + "situation TEXT, "
                    + "task TEXT, "
                    + "action TEXT, "
                    + "result TEXT, "
                    + "evidence TEXT, "
                    + "capability_tags VARCHAR(500), "
                    + "target_track VARCHAR(200), "
                    + "check_result TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_story_bank_user ON story_bank(user_id)",
            // ── 第三批（v1.49.0）：历史复盘维度、岗位偏好四档、导入幂等日志 ──
            // A：题目维度明细（可空；存量行保持 NULL，不回溯、不填 0）
            "ALTER TABLE interview_question ADD COLUMN IF NOT EXISTS eval_detail TEXT",
            // H：岗位收藏偏好四档（可空，NULL=未标记，不得默认成任何一档）
            "ALTER TABLE job_favorite ADD COLUMN IF NOT EXISTS preference VARCHAR(20)",
            // C：导入幂等日志（新表，跨进程重启持久，(user_id, import_id) 唯一）
            "CREATE TABLE IF NOT EXISTS data_import_log ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "user_id VARCHAR(64) NOT NULL, "
                    + "import_id VARCHAR(64) NOT NULL, "
                    + "mode VARCHAR(10) NOT NULL, "
                    + "summary_json TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_data_import_log_user_import UNIQUE (user_id, import_id))",
            "CREATE INDEX IF NOT EXISTS idx_data_import_log_user ON data_import_log(user_id)",
            // ── 第四批（v1.53.0）：用户自持 AI Key 设置 ──
            // api_key_cipher 为 AES-256-GCM 密文（主密钥由 JWT_SECRET 派生，见 UserKeyCipher）；
            // 主键即 user_id（一人一套配置），base_url 强制 https 且拒绝私网地址（SSRF 防护在服务层）
            "CREATE TABLE IF NOT EXISTS user_ai_setting ("
                    + "user_id VARCHAR(64) PRIMARY KEY, "
                    + "api_key_cipher TEXT NOT NULL, "
                    + "base_url VARCHAR(200) NOT NULL, "
                    + "model VARCHAR(100) NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
    };

    /**
     * 关键列/表自检清单（第三批）。
     *
     * <p>背景：{@link #run} 对 DDL 异常**只告警不阻断启动**（历史高频坑）——一旦 DDL 因故失败，
     * 应用照常启动但列/表缺失，症状是「新功能与线上行为不符」，且要到运行时才炸。
     * 这里在 DDL 执行后主动查 {@code information_schema} 复核，缺失时打 <b>ERROR</b> 日志，
     * 让「列没建成」在启动日志里立刻可见，而不是靠线上事故反查。
     *
     * <p>元素格式：{@code {表名, 列名}}；列名为 {@code null} 时表示只校验该表是否存在。
     */
    private static final String[][] REQUIRED_COLUMNS = {
            {"interview_question", "eval_detail"},
            {"job_favorite", "preference"}
    };

    /** 必须存在的新表 */
    private static final String[] REQUIRED_TABLES = {
            "data_import_log"
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
        // 可观测判据：DDL 异常被吞掉后，用 information_schema 自检把「列没建成」暴露到启动日志
        verifySchema();
        log.info("SchemaInitializer 完成：已确保新增表存在");
    }

    /**
     * 启动后自检关键列/表是否到位；缺失时打 ERROR 日志（不阻断启动）。
     *
     * @return 缺失项数量（0 表示全部就位）；返回值为可测试判据
     */
    int verifySchema() {
        int missing = 0;
        for (String[] rc : REQUIRED_COLUMNS) {
            String table = rc[0];
            String column = rc[1];
            if (column == null) {
                continue;
            }
            try {
                Integer n = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name = ? AND column_name = ?",
                        Integer.class, table, column);
                if (n == null || n == 0) {
                    log.error("SchemaInitializer 自检失败：列 {}.{} 不存在——依赖该列的功能将不可用，"
                            + "请手动在数据库执行对应 DDL 后重启", table, column);
                    missing++;
                }
            } catch (Exception e) {
                log.error("SchemaInitializer 自检查询失败（列 {}.{}）：{}", table, column, e.getMessage());
                missing++;
            }
        }
        for (String table : REQUIRED_TABLES) {
            try {
                Integer n = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                        Integer.class, table);
                if (n == null || n == 0) {
                    log.error("SchemaInitializer 自检失败：表 {} 不存在——依赖该表的功能将不可用，"
                            + "请手动在数据库执行对应 DDL 后重启", table);
                    missing++;
                }
            } catch (Exception e) {
                log.error("SchemaInitializer 自检查询失败（表 {}）：{}", table, e.getMessage());
                missing++;
            }
        }
        if (missing == 0) {
            log.info("SchemaInitializer 自检通过：全部新增列/表已就位");
        }
        return missing;
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