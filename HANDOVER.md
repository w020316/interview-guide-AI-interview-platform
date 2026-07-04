# AI 智能面试辅助平台 - 项目交接文档

> 本文档用于将项目交接给 Claude Code 继续开发，包含项目现状、技术栈、待办任务和开发约束。

---

## 一、项目基本信息

| 项 | 内容 |
|---|------|
| **项目名称** | AI 智能面试辅助平台（interview-guide） |
| **GitHub 仓库** | https://github.com/w020316/interview-guide-AI-interview-platform |
| **本地路径** | `d:\xm\wz\新建文件夹\interview-guide` |
| **当前 commit** | `6c918e6 feat: 切换为 Agnes AI 免费模型 + 适配 Render 部署` |
| **项目状态** | 后端骨架已完成（编译/打包通过），待功能完善 |

---

## 二、技术栈

### 后端
- **JDK**：Java 21（启用虚拟线程）
- **框架**：Spring Boot 3.3.6
- **AI 框架**：Spring AI 1.0.0（BOM 管理版本）
  - `spring-ai-starter-model-openai`
  - `spring-ai-starter-vector-store-pgvector`
  - `spring-ai-tika-document-reader`
- **数据库**：PostgreSQL 16 + pgvector 向量扩展
- **ORM**：Spring Data JPA
- **缓存**：Redis 7
- **构建工具**：Maven 3.9

### 前端
- **框架**：Vue 3 + TypeScript
- **UI 库**：Element Plus 2.7
- **构建工具**：Vite 5
- **HTTP 客户端**：axios
- **Markdown 渲染**：marked

### AI 模型
- **服务商**：Agnes AI（兼容 OpenAI 协议，免费无限量）
- **Base URL**：`https://apihub.agnes-ai.com/v1`
- **Chat 模型**：`agnes-base`
- **Embedding 模型**：`text-embedding-3-small`
- **API Key**：`sk-Vr4yB5a0DbzPXcB5wQfthyCH28LF5ZoR2t4sxaOWdNZidC`

### 部署架构（0 元方案）
- **前端托管**：Vercel（永久免费，100GB 流量/月）
- **后端容器**：Render 免费层（512MB 内存，15 分钟无访问休眠，UptimeRobot 保活）
- **数据库**：Supabase（PostgreSQL + pgvector，500MB 免费）
- **Redis**：Upstash（10K 命令/天免费）
- **监控保活**：UptimeRobot

---

## 三、项目结构

```
interview-guide/
├── backend/                                    # 后端 Spring Boot 项目
│   ├── src/main/java/com/example/interview/
│   │   ├── InterviewGuideApplication.java     # 启动类
│   │   ├── config/
│   │   │   ├── AiConfig.java                  # ChatClient 配置
│   │   │   └── RedisConfig.java               # RedisTemplate 配置
│   │   ├── common/
│   │   │   └── Result.java                    # 统一响应结构（Record）
│   │   ├── controller/
│   │   │   ├── HealthController.java          # 健康检查 + 系统信息
│   │   │   ├── ResumeController.java          # 简历分析接口
│   │   │   ├── InterviewController.java       # 面试生成 + 回答评估
│   │   │   └── KnowledgeController.java       # RAG 知识库接口
│   │   └── service/
│   │       ├── ResumeAnalysisService.java     # 简历 AI 分析（含 Redis 缓存）
│   │       ├── InterviewService.java          # 面试题生成 + 回答评估
│   │       └── RagSearchService.java          # RAG 向量检索 + 问答
│   ├── src/main/resources/
│   │   ├── application.yml                    # 本地配置
│   │   ├── application-prod.yml               # 生产配置（云端部署用）
│   │   └── schema.sql                         # 数据库建表脚本
│   ├── Dockerfile                             # 多阶段构建（适配 Render 512MB）
│   └── pom.xml
├── frontend/                                  # 前端 Vue3 项目
│   ├── src/
│   │   ├── App.vue                            # 主界面（3 个 Tab）
│   │   ├── main.ts                            # 入口
│   │   └── api/index.ts                       # axios 封装
│   ├── vite.config.ts                         # 含 /api 代理
│   ├── package.json
│   └── tsconfig.json
├── docker-compose.yml                         # 本地 PostgreSQL + Redis
├── render.yaml                                # Render Blueprint 部署配置
├── vercel.json                                # Vercel 前端部署配置
├── .env.example                               # 环境变量模板
├── start-dev.ps1                              # Windows 一键启动脚本
├── start-dev.sh                               # Linux/macOS 一键启动脚本
├── README.md                                  # 项目说明
└── DEPLOY.md                                  # 8 步云端部署指南
```

---

## 四、当前进度

### ✅ 已完成（70%）

| 阶段 | 内容 | 状态 |
|------|------|------|
| 阶段 1 | 项目选型与规划 | ✅ 100% |
| 阶段 2 | 环境准备（JDK 21 + Maven + Node.js） | ✅ 100% |
| 阶段 3 | 后端代码骨架（编译/打包通过） | ✅ 100% |
| 阶段 4 | 前端代码骨架 | ✅ 100% |
| 阶段 5 | 部署配置（Dockerfile/render.yaml/vercel.json） | ✅ 100% |
| 阶段 6 | 文档（README + DEPLOY + 启动脚本） | ✅ 100% |
| 阶段 7 | Git 仓库初始化 + 推送 GitHub | ✅ 100% |

### 🔄 进行中（0%，需用户本人操作）

| 阶段 | 内容 | 负责方 |
|------|------|--------|
| 阶段 8 | 云端部署（Supabase + Upstash + Render + Vercel） | 用户 |

### ⏳ 待开发（30%，交给 Claude Code）

| 阶段 | 内容 | 负责方 |
|------|------|--------|
| 阶段 9 | 功能完善（12 项任务） | **Claude Code** |
| 阶段 10 | 求职准备（简历 + 面试题 + 演示视频） | 用户 + AI 协助 |

---

## 五、Claude Code 任务清单

### P0 - 必须完成（核心功能）

#### 1. 简历 PDF 解析
- **依赖**：`spring-ai-tika-document-reader`（已在 pom.xml 引入）
- **任务**：实现 `POST /api/resume/upload` 接口，接收 PDF 文件，用 Tika 解析为文本，再调用 `ResumeAnalysisService.analyze()`
- **文件位置**：新建 `backend/src/main/java/com/example/interview/service/ResumeParseService.java`
- **参考**：Spring AI Tika Document Reader 官方文档

#### 2. 面试会话 CRUD
- **数据库表**：`interview_session` 和 `interview_question`（已在 schema.sql 建好）
- **任务**：
  - 创建 JPA Entity：`InterviewSessionEntity`、`InterviewQuestionEntity`
  - 创建 Repository：`InterviewSessionRepository`、`InterviewQuestionRepository`
  - 创建 Service：`InterviewSessionService`（创建会话、保存题目、保存用户回答、保存评估结果）
  - 创建 Controller：`InterviewSessionController`（会话列表、会话详情、历史记录）
- **文件位置**：`backend/src/main/java/com/example/interview/{entity,repository,service,controller}/`

#### 3. SSE 流式输出
- **任务**：AI 回答改为流式返回，提升前端体验
- **接口**：`POST /api/interview/ask/stream`（返回 `SseEmitter` 或 `Flux<ServerSentEvent>`）
- **实现**：使用 Spring AI 的 `ChatClient.prompt().stream()` 方法
- **前端适配**：`App.vue` 中用 `EventSource` 接收
- **参考**：Spring AI Streaming 响应文档

#### 4. 全局异常处理
- **任务**：`@ControllerAdvice` + `@ExceptionHandler` 统一处理异常
- **异常类型**：
  - `IllegalArgumentException` → 400
  - `RuntimeException` → 500
  - AI 调用超时 → 504
  - 数据库异常 → 500
- **文件位置**：`backend/src/main/java/com/example/interview/common/GlobalExceptionHandler.java`

### P1 - 建议完成（增强功能）

#### 5. 文件上传到 Supabase Storage
- **任务**：简历 PDF 上传到 Supabase Storage，返回 URL
- **配置**：在 `application-prod.yml` 添加 Supabase Storage 配置
- **依赖**：使用 Supabase Java SDK 或 REST API

#### 6. 知识库批量导入
- **任务**：`POST /api/knowledge/import/batch` 接口，支持批量导入知识文档
- **实现**：读取 Markdown 文件，切分后批量向量化存入 pgvector
- **数据来源**：JavaGuide 开源知识库

#### 7. 接口限流
- **任务**：防止 Agnes AI API 被滥用
- **方案**：Bucket4j + Redis，每个 IP 每分钟限 10 次
- **依赖**：`com.bucket4j:bucket4j-redis`

#### 8. 单元测试
- **任务**：JUnit5 + Mockito，覆盖率 ≥ 60%
- **测试类**：
  - `ResumeAnalysisServiceTest`
  - `InterviewServiceTest`
  - `RagSearchServiceTest`
  - Controller 层测试（MockMvc）

#### 9. SpringDoc OpenAPI 文档
- **依赖**：`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0`
- **任务**：自动生成 API 文档，访问 `/swagger-ui.html`
- **配置**：在 `application.yml` 添加 SpringDoc 配置

### P2 - 锦上添花

#### 10. JWT 用户认证
- **依赖**：`spring-boot-starter-security` + `jjwt`
- **任务**：用户注册/登录，JWT 签发与校验
- **接口**：`POST /api/auth/register`、`POST /api/auth/login`

#### 11. Prometheus 监控指标
- **依赖**：`micrometer-registry-prometheus`（已引入）
- **任务**：自定义业务指标（AI 调用次数、响应时间、缓存命中率）
- **配置**：`application.yml` 暴露 `/actuator/prometheus`

#### 12. 前端优化
- **任务**：
  - Markdown 流式渲染（打字机效果）
  - 简历 PDF 上传组件
  - 面试记录历史页面
  - 响应式布局优化

---

## 六、开发约束

### 代码风格
- **保持现有风格**：Record 类型 + Lombok + Constructor Injection
- **中文注释**：所有类、方法、复杂逻辑用中文注释
- **包结构**：按现有 `config/common/controller/service` 分层
- **命名规范**：类名 PascalCase，方法名 camelCase，常量 UPPER_SNAKE_CASE

### Git 提交规范
- **每完成一个功能提交一次**，不要批量提交
- **Commit Message 格式**：`feat: xxx` / `fix: xxx` / `docs: xxx` / `test: xxx`
- **示例**：`feat: 实现简历 PDF 解析功能`

### 技术约束
- **不要修改已有的技术栈**（Spring Boot 3.3.6 + Spring AI 1.0 + Java 21）
- **不要修改 pom.xml 的 parent 版本**
- **不要修改 application.yml 的结构**，只添加新配置
- **不要修改 Dockerfile 和 render.yaml**（已适配 Render 512MB）
- **不要删除已有代码**，只增不改（除非有 bug）

### 部署约束
- **后端必须能在 512MB 内存下运行**（Render 免费层限制）
- **JVM 参数**：`-Xmx384m -Xms128m -XX:+UseZGC`
- **端口**：通过 `--server.port=${PORT:-8080}` 适配 Render

---

## 七、环境变量清单

### 本地开发
```bash
AI_API_KEY=sk-Vr4yB5a0DbzPXcB5wQfthyCH28LF5ZoR2t4sxaOWdNZidC
AI_BASE_URL=https://apihub.agnes-ai.com/v1
AI_MODEL=agnes-base
DATABASE_URL=jdbc:postgresql://localhost:5432/interview_guide
DATABASE_USER=postgres
DATABASE_PASSWORD=postgres
REDIS_HOST=localhost
REDIS_PORT=6379
```

### 生产环境（Render）
```bash
AI_API_KEY=sk-Vr4yB5a0DbzPXcB5wQfthyCH28LF5ZoR2t4sxaOWdNZidC
AI_BASE_URL=https://apihub.agnes-ai.com/v1    # 已在 render.yaml 写死
AI_MODEL=agnes-base                            # 已在 render.yaml 写死
DATABASE_URL=jdbc:postgresql://db.xxxx.supabase.co:5432/postgres
DATABASE_USER=postgres
DATABASE_PASSWORD=你的Supabase密码
REDIS_HOST=xxxx.upstash.io
REDIS_PORT=6379
REDIS_PASSWORD=你的Upstash密码
SPRING_PROFILES_ACTIVE=prod
JAVA_OPTS=-Xmx384m -Xms128m -XX:+UseZGC -XX:MaxMetaspaceSize=128m
```

---

## 八、验证方法

### 后端编译验证
```bash
cd d:\xm\wz\新建文件夹\interview-guide\backend
mvn clean compile
# 应输出 BUILD SUCCESS
```

### 后端打包验证
```bash
mvn clean package -DskipTests
# 应生成 target/interview-guide.jar
```

### 后端启动验证
```bash
# 设置环境变量后
mvn spring-boot:run
# 访问 http://localhost:8080/actuator/health 应返回 {"status":"UP"}
# 访问 http://localhost:8080/api/info 应返回系统信息 JSON
```

### 前端启动验证
```bash
cd d:\xm\wz\新建文件夹\interview-guide\frontend
npm install
npm run dev
# 访问 http://localhost:3000 应看到前端界面
```

### API 接口验证
```bash
# 简历分析
curl -X POST http://localhost:8080/api/resume/analyze \
  -H "Content-Type: application/json" \
  -d '{"resumeText":"我的简历内容","targetJob":"Java 后端开发"}'

# 生成面试题
curl -X POST http://localhost:8080/api/interview/questions \
  -H "Content-Type: application/json" \
  -d '{"resumeText":"简历","jobDescription":"岗位描述","count":5}'

# RAG 问答
curl -X POST http://localhost:8080/api/knowledge/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"HashMap 原理"}'
```

---

## 九、API 接口清单

### 已实现
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/info` | GET | 系统信息 |
| `/api/resume/analyze` | POST | 简历分析（4 维度评分） |
| `/api/interview/questions` | POST | 生成面试题 |
| `/api/interview/evaluate` | POST | 评估用户回答 |
| `/api/knowledge/search` | GET | 知识库检索 |
| `/api/knowledge/ask` | POST | RAG 问答 |
| `/api/knowledge/import` | POST | 导入知识文档 |
| `/actuator/health` | GET | 健康检查 |

### 待实现（Claude Code 任务）
| 接口 | 方法 | 说明 | 优先级 |
|------|------|------|--------|
| `/api/resume/upload` | POST | 上传 PDF 简历 | P0 |
| `/api/session/create` | POST | 创建面试会话 | P0 |
| `/api/session/{id}` | GET | 获取会话详情 | P0 |
| `/api/session/list` | GET | 用户会话列表 | P0 |
| `/api/interview/ask/stream` | POST | SSE 流式回答 | P0 |
| `/api/knowledge/import/batch` | POST | 批量导入知识 | P1 |
| `/api/auth/register` | POST | 用户注册 | P2 |
| `/api/auth/login` | POST | 用户登录 | P2 |

---

## 十、数据库表结构

### 已建表（schema.sql）
- `resume` - 简历表
- `interview_session` - 面试会话表
- `interview_question` - 面试题表
- `knowledge_doc` - 知识库元数据表

### 向量表（Spring AI pgvector 自动创建）
- `vector_store` - 向量存储表（由 Spring AI 管理）

---

## 十一、关键参考文档

| 文档 | 链接 |
|------|------|
| Spring AI 1.0 官方文档 | https://docs.spring.io/spring-ai/reference/ |
| Spring AI OpenAI 集成 | https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html |
| Spring AI pgvector | https://docs.spring.io/spring-ai/reference/api/vectorstores/pgvector.html |
| Spring AI Streaming | https://docs.spring.io/spring-ai/reference/api/chatmodel.html |
| Spring AI Tika | https://docs.spring.io/spring-ai/reference/api/etl/document-reader.html |
| Render 部署文档 | https://render.com/docs |
| Agnes AI | https://platform.agnes-ai.com/ |

---

## 十二、下一步行动

1. **用户先完成阶段 8 云端部署**（Supabase + Upstash + Render + Vercel）
2. **部署成功后**，把本文档发给 Claude Code
3. **Claude Code 按优先级开发**：P0（4 项）→ P1（5 项）→ P2（3 项）
4. **每完成一项功能**：提交 Git → 推送 GitHub → Render 自动重新部署
5. **全部完成后**：进入阶段 10 求职准备（简历 + 面试题 + 演示视频）

---

**文档版本**：v1.0
**最后更新**：2026-07-04
**项目 commit**：`6c918e6`
