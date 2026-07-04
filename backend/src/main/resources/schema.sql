-- PostgreSQL + pgvector 初始化脚本
-- 在 Supabase SQL Editor 或本地 PostgreSQL 中执行

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 简历表
CREATE TABLE IF NOT EXISTS resume (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    content TEXT,
    target_job VARCHAR(200),
    overall_score INT,
    analysis_result JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 面试会话表
CREATE TABLE IF NOT EXISTS interview_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) UNIQUE NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    resume_id BIGINT REFERENCES resume(id),
    job_description TEXT,
    status VARCHAR(20) DEFAULT 'ONGOING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 面试题表
CREATE TABLE IF NOT EXISTS interview_question (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    question TEXT NOT NULL,
    category VARCHAR(50),
    difficulty VARCHAR(20),
    key_points TEXT,
    reference_answer TEXT,
    user_answer TEXT,
    evaluation_score INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识库元数据表（向量数据由 Spring AI pgvector 自动管理）
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(50),
    title VARCHAR(200),
    content TEXT NOT NULL,
    source VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_resume_user_id ON resume(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_session_user_id ON interview_session(user_id);
CREATE INDEX IF NOT EXISTS idx_interview_question_session_id ON interview_question(session_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_category ON knowledge_doc(category);

-- 插入示例知识数据（可选）
INSERT INTO knowledge_doc (category, title, content, source) VALUES
('Java 基础', 'HashMap 原理', 'HashMap 基于哈希表实现，JDK 8 后采用数组+链表+红黑树结构。当链表长度超过 8 且数组长度超过 64 时，链表转红黑树。', 'JavaGuide'),
('Java 基础', 'ConcurrentHashMap', 'JDK 8 的 ConcurrentHashMap 采用 CAS + synchronized 实现，锁粒度为桶节点。', 'JavaGuide'),
('Spring', 'Spring Bean 生命周期', 'Spring Bean 生命周期包括实例化、属性赋值、初始化、销毁四个阶段，BeanPostProcessor 在初始化前后介入。', 'JavaGuide'),
('数据库', 'MySQL 索引', 'MySQL InnoDB 使用 B+ 树索引，聚簇索引存储数据行，非聚簇索引存储主键。覆盖索引可避免回表。', 'JavaGuide'),
('中间件', 'Redis 持久化', 'Redis 提供两种持久化方式：RDB（快照）和 AOF（追加日志）。RDB 体积小恢复快，AOF 数据更完整。', 'JavaGuide')
ON CONFLICT DO NOTHING;
