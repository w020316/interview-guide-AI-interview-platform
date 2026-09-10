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
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_interview_event_user_id ON interview_event(user_id);

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

-- 预置知识数据
INSERT INTO knowledge_doc (category, title, content, source) VALUES
('Java 基础', 'HashMap 原理', 'HashMap 基于哈希表实现，JDK 8 后采用数组+链表+红黑树结构。', 'JavaGuide'),
('Java 基础', 'ConcurrentHashMap', 'JDK 8 的 ConcurrentHashMap 采用 CAS + synchronized 实现，锁粒度为桶节点。', 'JavaGuide'),
('Spring', 'Spring Bean 生命周期', 'Spring Bean 生命周期包括实例化、属性赋值、初始化、销毁四个阶段。', 'JavaGuide'),
('数据库', 'MySQL 索引', 'MySQL InnoDB 使用 B+ 树索引，聚簇索引存储数据行，非聚簇索引存储主键。', 'JavaGuide'),
('中间件', 'Redis 持久化', 'Redis 提供两种持久化方式：RDB（快照）和 AOF（追加日志）。', 'JavaGuide')
ON CONFLICT DO NOTHING;
