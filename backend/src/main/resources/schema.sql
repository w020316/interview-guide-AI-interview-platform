-- PostgreSQL + pgvector 初始化脚本
CREATE EXTENSION IF NOT EXISTS vector;

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email         VARCHAR(128) UNIQUE,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- 简历表
CREATE TABLE IF NOT EXISTS resume (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    content         TEXT,
    file_url        VARCHAR(512),
    target_job      VARCHAR(200),
    overall_score   INT,
    analysis_result JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_resume_user_id ON resume(user_id);

-- 面试会话表
CREATE TABLE IF NOT EXISTS interview_session (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) UNIQUE NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    resume_id       BIGINT REFERENCES resume(id),
    job_description TEXT,
    status          VARCHAR(20) DEFAULT 'ONGOING',
    created_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_session_user_id ON interview_session(user_id);

-- 面试题目表
CREATE TABLE IF NOT EXISTS interview_question (
    id               BIGSERIAL PRIMARY KEY,
    session_id       VARCHAR(64) NOT NULL,
    question         TEXT NOT NULL,
    category         VARCHAR(50),
    difficulty       VARCHAR(20),
    key_points       TEXT,
    reference_answer TEXT,
    user_answer      TEXT,
    evaluation_score INT,
    -- 第三批 A：题目维度明细（JSON 文本，可空；存量行=NULL，不回溯、不填 0）
    eval_detail      TEXT,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_question_session_id ON interview_question(session_id);

-- 知识库文档表
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id         BIGSERIAL PRIMARY KEY,
    category   VARCHAR(50),
    title      VARCHAR(200),
    content    TEXT NOT NULL,
    source     VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_category ON knowledge_doc(category);

-- 错题收藏表（收藏夹：对面试题做快照，便于集中回看，按用户隔离）
CREATE TABLE IF NOT EXISTS favorite_question (
    id               BIGSERIAL PRIMARY KEY,
    user_id          VARCHAR(64) NOT NULL,
    session_id       VARCHAR(64),
    question_id      BIGINT,
    question         TEXT NOT NULL,
    category         VARCHAR(50),
    difficulty       VARCHAR(20),
    reference_answer TEXT,
    user_answer      TEXT,
    evaluation_score INT,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_favorite_user_id ON favorite_question(user_id);

-- 面试日历表（求职面试规划日程，按用户隔离）
CREATE TABLE IF NOT EXISTS interview_event (
    id           BIGSERIAL PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL,
    title        VARCHAR(200) NOT NULL,
    interviewer   VARCHAR(100),
    location     VARCHAR(200),
    note         TEXT,
    interview_at TIMESTAMP NOT NULL,
    status       VARCHAR(20) DEFAULT 'UPCOMING',
    -- v1.61.0：关联的投递记录（可为 NULL = 用户在日历里手工建的日程）
    application_id BIGINT,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_event_user_id ON interview_event(user_id);

-- v1.61.0 投递 ↔ 面试时序视图：存量库补列。
-- ⚠️ 必须写 ALTER：上面的 CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，加列不会生效。
ALTER TABLE interview_event ADD COLUMN IF NOT EXISTS application_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_interview_event_application ON interview_event(application_id);

-- 岗位信息表（招聘信息智能体，v1.22.0）
CREATE TABLE IF NOT EXISTS job_posting (
    id            BIGSERIAL    PRIMARY KEY,
    platform      VARCHAR(50)  NOT NULL,
    external_id   VARCHAR(128) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    company_name  VARCHAR(200) NOT NULL,
    industry      VARCHAR(50),
    job_type      VARCHAR(50),
    location      VARCHAR(100),
    salary        VARCHAR(100),
    degree        VARCHAR(50),
    experience    VARCHAR(50),
    recruit_type  VARCHAR(20),
    deadline      DATE,
    apply_url     VARCHAR(500),
    description   TEXT,
    requirements  TEXT,
    tags          VARCHAR(500),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_posting_platform_external UNIQUE (platform, external_id)
);
CREATE INDEX IF NOT EXISTS idx_job_posting_deadline ON job_posting(deadline);
CREATE INDEX IF NOT EXISTS idx_job_posting_recruit_type ON job_posting(recruit_type);
CREATE INDEX IF NOT EXISTS idx_job_posting_active ON job_posting(active);

-- 岗位收藏表（快照式收藏 + 截止日提醒，v1.23.3）
CREATE TABLE IF NOT EXISTS job_favorite (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      VARCHAR(64)  NOT NULL,
    job_id       BIGINT       NOT NULL,
    title        VARCHAR(200) NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    platform     VARCHAR(50),
    location     VARCHAR(100),
    salary       VARCHAR(100),
    deadline     DATE,
    apply_url    VARCHAR(500),
    -- 第三批 H：偏好四档（可空，NULL=未标记，不得默认成任何一档）
    preference   VARCHAR(20),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_favorite_user_job UNIQUE (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_job_favorite_user ON job_favorite(user_id);

-- 智能体会话与消息表（Career Copilot，v1.23.0）
CREATE TABLE IF NOT EXISTS agent_conversation (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    title       VARCHAR(60) NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_conv_user ON agent_conversation(user_id);

CREATE TABLE IF NOT EXISTS agent_message (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_msg_conv ON agent_message(conversation_id);

-- 投递台账表（v1.35.0：人工确认投递 + 回复监测 + 定制简历）
-- 合规边界：平台只做本地台账与提醒，不代替用户登录招聘平台或自动投递。
CREATE TABLE IF NOT EXISTS job_application (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    job_id          BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL,
    company_name    VARCHAR(200) NOT NULL,
    platform        VARCHAR(50),
    location        VARCHAR(100),
    salary          VARCHAR(100),
    deadline        DATE,
    apply_url       VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
    tailored_resume TEXT,
    note            TEXT,
    applied_at      TIMESTAMP,
    last_reply_at   TIMESTAMP,
    next_action_at  TIMESTAMP,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_application_user_job UNIQUE (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_job_application_user ON job_application(user_id);
CREATE INDEX IF NOT EXISTS idx_job_application_status ON job_application(status);

-- 面试故事库表（v1.36.0：STAR 故事资产 + 六项质检快照）
-- 洞察来源：「面试不是背答案，是经得起追问」——把真实经历预先整理成讲得清、有证据的 STAR 故事。
CREATE TABLE IF NOT EXISTS story_bank (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    situation       TEXT,
    task            TEXT,
    action          TEXT,
    result          TEXT,
    evidence        TEXT,
    capability_tags VARCHAR(500),
    target_track    VARCHAR(200),
    check_result    TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_story_bank_user ON story_bank(user_id);

-- 导入幂等日志表（第三批 C：(user_id, import_id) 唯一，跨进程重启持久）
CREATE TABLE IF NOT EXISTS data_import_log (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      VARCHAR(64)  NOT NULL,
    import_id    VARCHAR(64)  NOT NULL,
    mode         VARCHAR(10)  NOT NULL,
    summary_json TEXT,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_data_import_log_user_import UNIQUE (user_id, import_id)
);
CREATE INDEX IF NOT EXISTS idx_data_import_log_user ON data_import_log(user_id);

-- 第四批（v1.53.0）：用户自持 AI Key 设置
-- api_key_cipher 为 AES-256-GCM 密文（主密钥由 JWT_SECRET 派生，见 UserKeyCipher）；
-- 主键即 user_id（一人一套配置）；base_url 强制 https 且拒绝私网地址（SSRF 防护在服务层）
CREATE TABLE IF NOT EXISTS user_ai_setting (
    user_id        VARCHAR(64)  PRIMARY KEY,
    api_key_cipher TEXT         NOT NULL,
    base_url       VARCHAR(200) NOT NULL,
    model          VARCHAR(100) NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 预置知识数据
INSERT INTO knowledge_doc (category, title, content, source) VALUES
('Java 基础', 'HashMap 原理', 'HashMap 基于哈希表实现，JDK 8 后采用数组+链表+红黑树结构。', 'JavaGuide'),
('Java 基础', 'ConcurrentHashMap', 'JDK 8 的 ConcurrentHashMap 采用 CAS + synchronized 实现，锁粒度为桶节点。', 'JavaGuide'),
('Spring', 'Spring Bean 生命周期', 'Spring Bean 生命周期包括实例化、属性赋值、初始化、销毁四个阶段。', 'JavaGuide'),
('数据库', 'MySQL 索引', 'MySQL InnoDB 使用 B+ 树索引，聚簇索引存储数据行，非聚簇索引存储主键。', 'JavaGuide'),
('中间件', 'Redis 持久化', 'Redis 提供两种持久化方式：RDB（快照）和 AOF（追加日志）。', 'JavaGuide')
ON CONFLICT DO NOTHING;
