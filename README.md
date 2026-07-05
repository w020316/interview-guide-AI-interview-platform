# AI 智能面试辅助平台

> 基于 Spring Boot 3.3 + Spring AI 1.0 + Java 21 的 AI 面试辅助系统
> 零成本部署到云端（Vercel + Render + Supabase + Upstash，全部免费）
> AI 模型使用 Agnes AI（兼容 OpenAI 协议，免费无限量）

## 核心功能

- **智能简历分析**：AI 从 4 个维度评分（技术栈匹配度/项目含金量/表述清晰度/求职意向匹配度）
- **AI 模拟面试**：根据简历 + 岗位 JD 生成个性化面试题
- **RAG 知识库问答**：基于 pgvector 向量检索 JavaGuide 八股文
- **回答质量评估**：从完整性、准确性、表达能力 3 维度打分
- **简历历史记录**：每次分析自动持久化，可回看完整评分与建议 ✨
- **个人中心仪表盘**：统计数据卡片 + 平均分横幅 + 最近活动流 ✨
- **知识库管理**：批量导入知识点，RAG 智能问答 ✨

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.6 + Java 21（虚拟线程） |
| AI 框架 | Spring AI 1.0.0（OpenAI 协议接入通义千问） |
| 数据库 | PostgreSQL 16 + pgvector 向量扩展 |
| 缓存 | Redis 7 |
| 前端 | Vue 3 + Element Plus + TypeScript + Vite |
| 部署 | Vercel + Render + Supabase + Upstash（0 元） |
| AI 模型 | Agnes AI（兼容 OpenAI，免费无限量） |

## 项目结构

```
interview-guide/
├── backend/                    # 后端 Spring Boot 项目
│   ├── src/main/java/com/example/interview/
│   │   ├── InterviewGuideApplication.java
│   │   ├── config/             # AI、Redis 配置
│   │   ├── common/             # 通用工具
│   │   ├── controller/         # REST 接口
│   │   └── service/            # 业务逻辑（简历分析/面试/RAG）
│   ├── src/main/resources/
│   │   ├── application.yml     # 本地配置
│   │   ├── application-prod.yml # 生产配置
│   │   └── schema.sql          # 数据库初始化脚本
│   ├── Dockerfile              # Docker 构建文件
│   └── pom.xml
├── frontend/                   # 前端 Vue3 项目
│   ├── src/
│   │   ├── api/index.ts        # API 封装
│   │   ├── App.vue             # 主界面
│   │   └── main.ts
│   ├── vite.config.ts
│   └── package.json
├── docker-compose.yml          # 本地开发环境
├── render.yaml                 # Render 部署配置
├── vercel.json                 # Vercel 部署配置
└── .env.example                # 环境变量模板
```

## 本地开发

### 1. 准备环境
- JDK 21+
- Maven 3.9+
- Node.js 18+

### 2. 启动数据库（任选其一）

**方式 A：Docker（推荐）**
```bash
docker-compose up -d postgres redis
```

**方式 B：本地安装 PostgreSQL 16 + Redis 7**
- 安装后执行 `backend/src/main/resources/schema.sql`

### 3. 获取 Agnes AI API Key（免费无限量）
1. 访问 https://platform.agnes-ai.com/settings/apikeys
2. 创建 API Key（格式 `sk-xxxx`）
3. Agnes AI 兼容 OpenAI 协议，免费且不限量

### 4. 启动后端
```bash
cd backend
export AI_API_KEY=sk-your-agnes-key   # Windows: set AI_API_KEY=sk-your-agnes-key
mvn spring-boot:run
```
访问：http://localhost:8080/api/info

### 5. 启动前端
```bash
cd frontend
npm install
npm run dev
```
访问：http://localhost:3000

## 云端部署（0 元）

详见 [DEPLOY.md](./DEPLOY.md)

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/info` | GET | 系统信息 |
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/logout` | POST | 用户登出 |
| `/api/resume/analyze` | POST | 简历分析（自动持久化） |
| `/api/resume/upload` | POST | 上传 PDF/TXT 简历分析 |
| `/api/resume/history` | GET | 简历历史列表 ✨ |
| `/api/resume/{id}` | GET | 简历详情 ✨ |
| `/api/interview/questions` | POST | 生成面试题 |
| `/api/interview/evaluate` | POST | 评估回答 |
| `/api/interview/ask/stream` | POST | SSE 流式 AI 提示 |
| `/api/session/create` | POST | 创建面试会话 |
| `/api/session/{id}` | GET | 会话详情 |
| `/api/session/{id}/finish` | PUT | 结束会话 |
| `/api/session/list` | GET | 用户会话列表 |
| `/api/session/{id}/questions` | GET | 会话题目详情 |
| `/api/session/answer` | POST | 提交回答与评分 |
| `/api/knowledge/search` | GET | 知识检索 |
| `/api/knowledge/ask` | POST | RAG 问答 |
| `/api/knowledge/import` | POST | 导入知识文档 |
| `/api/knowledge/import/batch` | POST | 批量导入分块 |
| `/api/stats/dashboard` | GET | 个人中心统计 ✨ |
| `/actuator/health` | GET | 健康检查 |
| `/swagger-ui.html` | GET | API 文档 |

✨ 标记为 v2 新增功能

## License

MIT
