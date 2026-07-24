/**
 * 版本更新日志
 * 每次发布新增版本条目，前端会与 localStorage 中的版本号对比
 * 若版本不同则弹窗展示本次更新内容
 */
export interface ChangelogEntry {
  version: string
  date: string
  title: string
  items: string[]
}

export const CURRENT_VERSION = '1.18.0'

export const CHANGELOG: ChangelogEntry[] = [
  {
    version: '1.18.0',
    date: '2026-07-24',
    title: '版本 1.18.0 · Service 层测试全覆盖（SupabaseStorageService + ResumeParseService）',
    items: [
      '测试：新增 SupabaseStorageServiceTest，10 个用例覆盖文件上传全链路',
      '测试：SupabaseStorageService 覆盖文件名清洗（路径穿越防御）、HTTP 2xx/4xx/5xx 响应、鉴权头（Bearer + x-upsert）、Content-Type fallback、请求体验证',
      '测试：新增 ResumeParseServiceTest，16 个用例覆盖简历文件解析多格式分发',
      '测试：ResumeParseService 覆盖 TXT/MD/MARKDOWN/HTML/HTM 五种格式解析、文件名空/null、不支持格式（.docx/.doc）、内容空/空白、大小写不敏感扩展名、HTML 去标签 + 空白压缩',
      '测试：parseAndAnalyze 验证委托 ResumeAnalysisService.analyze 的调用链（含 HTML 去标签后传纯文本）',
      '测试：SupabaseStorageService 使用反射注入 mock RestTemplate + ReflectionTestUtils 注入 @Value 字段',
      '测试：ResumeParseService 使用 MockMultipartFile 构造真实文件内容，无需 mock 文件 IO',
      '里程碑：8 个 Service 类全部覆盖测试，后端 Service 层测试全覆盖达成',
      '测试统计：后端 230 tests passed（+26），前端 87 tests passed'
    ]
  },
  {
    version: '1.17.0',
    date: '2026-07-24',
    title: '版本 1.17.0 · Service 层测试补齐（InterviewService + JobAnalysisService）',
    items: [
      '测试：新增 InterviewServiceTest，15 个用例覆盖 generateQuestions/evaluateAnswer 两大核心方法',
      '测试：InterviewService 覆盖缓存命中/未命中、Redis 读写降级、RAG 检索正常/异常、AI 空响应、简历截断、prompt 注入消毒、Micrometer 埋点',
      '测试：新增 JobAnalysisServiceTest，12 个用例覆盖 analyzeJobDescription/diagnoseGap/generateLetter 三大公开方法',
      '测试：JobAnalysisService 覆盖 callAi JSON 修复失败兜底、callAiRaw 空响应异常、type 三分支（email/referral/coverLetter）、Markdown 代码块剥离、文本截断',
      '测试：使用 ArgumentCaptor 捕获 ChatClient prompt 参数，验证 RAG 知识点拼接、简历截断标记、prompt 注入消毒',
      '测试：Mock ChatClient 同步调用链（prompt→user→call→content）、VectorStore、RedisTemplate、Micrometer Counter/Timer',
      '测试统计：后端 204 tests passed（+27），前端 87 tests passed'
    ]
  },
  {
    version: '1.16.0',
    date: '2026-07-24',
    title: '版本 1.16.0 · IDOR 语义统一（越权返回 403）+ SSE 流式端点测试 + 错误处理修复',
    items: [
      '安全：ResumeService.getByIdAndUser 越权访问从 IllegalArgumentException（400）改为 AccessDeniedException（403），与 InterviewSessionController IDOR 防御策略对齐',
      '安全：GlobalExceptionHandler 将 AccessDeniedException 统一映射为 HTTP 403 JSON 响应，前端可精确识别越权场景',
      '测试：ResumeServiceTest 验证越权抛 AccessDeniedException（getByIdAndUser_otherUser_shouldThrowAccessDenied）',
      '测试：ResumeControllerTest 新增 getById 越权返回 403 用例（getById_otherUserResume_returns403）+ 简历不存在返回 400 用例',
      '测试：新增 InterviewControllerSseTest，3 个用例覆盖 /ask/stream SSE 流式端点（入参校验 error 事件 / 正常 token+done 流式推送 / AI 异常 error 事件）',
      '测试：SSE 测试使用 MockMvc asyncDispatch 异步分发模式，mock ChatClient 链式调用（prompt→user→stream→content）返回预设 Flux',
      '修复：SSE doOnError 发送 error 事件后改用 emitter.complete()，避免 completeWithError 导致 asyncDispatch 返回 500、前端 EventSource 无法读取错误内容',
      '修复：Flux.subscribe 补充 onError 回调，消除 Reactor ErrorCallbackNotImplemented ERROR 日志',
      '测试统计：后端 177 tests passed（+4），前端 87 tests passed'
    ]
  },
  {
    version: '1.15.0',
    date: '2026-07-24',
    title: '版本 1.15.0 · ResumeController upload 全链路测试 + IDOR 防御测试 + SecurityConfig 鉴权策略测试',
    items: [
      '测试：ResumeControllerTest 新增 upload 端点 10 个用例（MockMultipartFile，覆盖空文件/超大/文件名空/扩展名非法/CT 非法/PDF magic bytes/合法 TXT/合法 PDF/解析 IllegalArgumentException/解析其他异常）',
      '测试：ResumeControllerTest 新增 history/getById 4 个用例（列表/空列表/本人详情/IDOR 越权返回 400）',
      '测试：新增 SecurityConfigTest，8 个用例验证 Spring Security 鉴权策略（permitAll /api/info + /actuator/health/info，authenticated /api/test-secure + /actuator/metrics，JWT 有效/无效/无 header 三态）',
      '测试：SecurityConfigTest 使用 @Import(SecurityConfig+JwtAuthFilter) + TestSecureController 载体，@MockBean JwtUtil 控制 token 验证',
      '修复：ResumeControllerTest mock RateLimitInterceptor 绕过 24 用例限流阈值（原 10 用例不超限，新增后需 mock）',
      '测试统计：后端 173 tests passed（+22），前端 87 tests passed'
    ]
  },
  {
    version: '1.14.0',
    date: '2026-07-24',
    title: '版本 1.14.0 · AI 调用 Metrics 全链路埋点 + CI 自动化测试 + 组件测试补充',
    items: [
      '可观测性：MetricsConfig 新增 aiCallJobAnalysisCounter / aiCallRagCounter，AI 调用按类型计数（type=jobAnalysis/rag/resume）',
      '可观测性：JobAnalysisService.callAiRaw 统一埋点，覆盖 analyze/gap/letter 三个公开方法的调用次数与耗时',
      '可观测性：RagSearchService.answerWithRag 接入 Counter + Timer，RAG 问答调用全链路监控',
      '工程化：新增 GitHub Actions CI 工作流（ci.yml），push/PR 自动触发后端 Maven test + 前端 Vitest + build 验证',
      '工程化：CI 后端 job 上传 surefire-reports artifact，便于排查失败用例',
      '测试：BaseCardTag.test.ts 新增 6 个交互测试用例（BaseCard feature 变体/flat 属性/feature+hoverable 组合，BaseTag aria-label 无障碍/.base-tag__text 包裹/close 事件 .stop 防冒泡）',
      '测试统计：后端 151 tests passed，前端 87 tests passed（+6）'
    ]
  },
  {
    version: '1.13.0',
    date: '2026-07-23',
    title: '版本 1.13.0 · Controller 测试全覆盖 + IDOR 防御测试 + /actuator/info 版本信息',
    items: [
      '测试：新增 KnowledgeController MockMvc 测试（19 用例，覆盖 search/ask/import/batch/wrong-questions/summary/recent 8 端点）',
      '测试：新增 StatsController MockMvc 测试（4 用例，覆盖空数据/仅简历/含会话 N+1 修复路径/混合活动排序）',
      '测试：新增 InterviewSessionController MockMvc 测试（20 用例，覆盖 create/list/get/finish/questions/answer 7 端点）',
      '安全：InterviewSessionController 测试覆盖 5 个 IDOR 越权场景（非本人会话返回 403）',
      '可观测性：新增 AppInfoContributor，/actuator/info 返回应用名称/版本/描述/构建日期',
      '配置化：app.info.version 从环境变量 APP_INFO_VERSION 读取，与前端版本号同步',
      '工程化：后端 pom.xml 版本从 1.0.0 同步至 1.13.0',
      '测试统计：后端 151 tests passed（+43），前端 81 tests passed'
    ]
  },
  {
    version: '1.12.0',
    date: '2026-07-23',
    title: '版本 1.12.0 · BaseTextarea 组件 + 表单控件批量迁移 + InterviewController 测试',
    items: [
      '前端组件库：新增 BaseTextarea 通用文本域（rows/size/error/block/maxlength + resize:vertical），12 个单测',
      '前端组件库：BaseInput 新增 list 属性，配合 <datalist> 实现岗位自动补全',
      '前端迁移：5 个 View 共 10 个 textarea 批量迁移到 BaseTextarea（InterviewView 2 + ResumeView 1 + JobAnalysisView 5 + KnowledgeView 2）',
      '前端迁移：ResumeView/InterviewView 共 5 个 input 迁移到 BaseInput（含 list 自动补全）',
      '测试：新增 InterviewController MockMvc 测试（12 用例，questions 7 + evaluate 5，覆盖入参校验 + count 边界夹紧 + 默认值）',
      '测试：mock RateLimitInterceptor 绕过 10 次/分钟限流阈值，确保 12 用例全通过',
      '运维：确认 actuator 端点安全策略合理（health/info permitAll，metrics authenticated，show-details=when_authorized）',
      '测试统计：后端 108 tests passed（+12），前端 81 tests passed（+13）'
    ]
  },
  {
    version: '1.11.0',
    date: '2026-07-23',
    title: '版本 1.11.0 · Controller 测试扩展 + CORS 配置化 + SSE 监控 + BaseInput 组件',
    items: [
      '测试：新增 JobAnalysisController MockMvc 测试（8 用例，覆盖 analyze/gap/letter 三端点校验与正常路径）',
      '测试：新增 ResumeController MockMvc 测试（10 用例，覆盖 analyze/optimize 入参校验 + import-url SSRF 防御）',
      '工程化：CORS 允许来源从硬编码改为 app.cors.allowed-origins 配置项，修改部署域名无需改代码',
      '可观测性：SSE 并发限流指标暴露到 actuator（sse.max.concurrent / sse.active.count），便于监控水位',
      '前端组件库：新增 BaseInput 通用输入框（sm/md/lg 尺寸 + error 态 + prefix/suffix 插槽），13 个单测',
      '前端组件库：KnowledgeView 分类输入框迁移到 BaseInput 作为试点',
      '测试统计：后端 96 tests passed（+18），前端 68 tests passed（+13）'
    ]
  },
  {
    version: '1.10.0',
    date: '2026-07-23',
    title: '版本 1.10.0 · UI 组件库深化 + Controller MockMvc + 去重阈值配置化',
    items: [
      'UI 组件库：BaseButton 新增 cta（白底反色）与 gradient（品牌色渐变）variant',
      'UI 组件库：BaseCard 新增 feature variant（顶部品牌色细条，hover 延展满宽）',
      'UI 迁移：7 个 View 共 22 个按钮批量迁移到 BaseButton，清理冗余 CSS',
      '测试：新增 HealthControllerTest（2 用例）+ AuthControllerTest（16 用例，覆盖注册/登录/登出全路径）',
      '修复：@WebMvcTest 下 JwtAuthFilter 依赖 JwtUtil 注入失败（@MockBean 解耦 SecurityConfig 依赖链）',
      '配置化：知识库去重相似度阈值改为 app.rag.dedup-similarity-threshold，运行时可调',
      '测试统计：后端 78 tests passed（+35），前端 55 tests passed'
    ]
  },
  {
    version: '1.9.0',
    date: '2026-07-23',
    title: '版本 1.9.0 · 后端单测基础 + DRY 重构 + SSE 配置化 + 知识库去重',
    items: [
      '工程化：抽取 PromptSanitizer 工具类，消除 9 处重复 sanitizePromptInput 方法（DRY）',
      '测试：新增 JsonRepairUtilTest（21 用例）+ PromptSanitizerTest（18 用例）',
      '测试：修复 RagSearchServiceTest 以适配 v1.8 方法签名变更',
      '配置化：SSE 并发限流从 static Semaphore 改为 @Value + @PostConstruct，运行时可调',
      '功能：知识库导入新增去重预检（向量相似度 >= 0.90 视为重复，跳过）',
      'UI 试点：HomeView Hero 区按钮迁移到 BaseButton，BaseButton 新增 shadow + hoverable prop',
      '测试统计：后端 43 tests passed，前端 55 tests passed'
    ]
  },
  {
    version: '1.8.0',
    date: '2026-07-23',
    title: '版本 1.8.0 · P0/P1 遗留清零 + Vitest 落地 + UI 组件库 + 保活',
    items: [
      '功能：面试题目/答案持久化端点激活（InterviewSessionController + InterviewView 集成）',
      '安全：JWT subject 改用 userId（不可变），用户名变更不再导致 token 失效',
      '安全：Prompt 注入防御统一落地 9 处服务（InterviewService / JobAnalysisService 等）',
      '安全：SSE 并发限流 Semaphore=20，防止虚拟线程池无界扩张',
      '性能：StatsController N+1 查询修复，批量拉取关联数据',
      '安全：知识库按 userId 隔离（metadata 过滤），防止跨用户读取',
      '测试：前端 Vitest 落地，4 个测试文件覆盖率 60%+（jsonRepair/auth/index/BaseButton）',
      'UI 组件库：新增 BaseButton / BaseCard / BaseTag 三类原子组件',
      '工程化：GitHub Actions keepalive.yml 每 10 分钟 ping /api/info，防 Render 休眠',
      '影响：JWT subject 变更要求所有用户重新登录'
    ]
  },
  {
    version: '1.7.1',
    date: '2026-07-22',
    title: '版本 1.7.1 · P0/P1 修复 + iOS 简历导入 + 移动端适配',
    items: [
      '修复：ResumeView 上传失败（axios 响应解包错误）',
      '修复：SSE error 事件污染问题',
      '修复：移动端导航溢出 + iOS 100dvh 视口问题',
      '修复：HTML 检测 TypeError',
      '功能：iOS「从其他平台导入简历」（URL/剪贴板/iCloud，后端 /api/resume/import-url 含 SSRF 防护）',
      '功能：差距诊断 tab 新增简历上传按钮（/api/resume/upload）'
    ]
  },
  {
    version: '1.7.0',
    date: '2026-07-22',
    title: '版本 1.7.0 · 新增岗位分析模块（JD 分析 + 差距诊断 + 求职信）',
    items: [
      '新增功能：JD 岗位分析，拆解职责/硬技能/软技能/隐性条件/关键词（POST /api/job/analyze）',
      '新增功能：差距诊断，简历 vs JD 逐条对比，输出强证据/弱证据/缺口（POST /api/job/gap）',
      '新增功能：求职信/申请邮件/内推私信生成（POST /api/job/letter，支持 coverLetter/email/referral 三类）',
      '前端：JobAnalysisView 三 tab 界面，导航栏新增「岗位分析」入口，/job 路由',
      '前端：首页功能卡新增岗位分析入口'
    ]
  },
  {
    version: '1.6.0',
    date: '2026-07-05',
    title: '版本 1.6.0 · 新增 AI 简历优化 + 文档下载',
    items: [
      '新增功能：基于分析结果一键生成优化版简历，AI 按 STAR 法则改写并量化项目成果',
      '新增功能：优化简历支持预览/源码双视图切换，实时渲染 Markdown',
      '新增功能：支持下载 Markdown 格式简历文档（.md）',
      '新增功能：支持下载 HTML 格式简历文档（.html，含打印样式，可直接打印为 PDF）',
      '新增功能：优化简历支持一键复制到剪贴板',
      '新增功能：优化简历结果缓存 30 分钟，避免重复调用 AI',
      '安全：优化简历 Markdown 经 DOMPurify 消毒后渲染，防 XSS',
      '工程化：后端新增 POST /api/resume/optimize 接口'
    ]
  },
  {
    version: '1.5.1',
    date: '2026-07-05',
    title: '版本 1.5.1 · 修复简历分析 403 + 上传格式校验对齐',
    items: [
      '修复（关键）：SecurityConfig 配置 AuthenticationEntryPoint，未认证请求统一返回 401 JSON（原默认 403 导致前端无法识别 token 失效）',
      '修复（关键）：前端 axios 拦截器同时处理 401 和 403，均清除 auth 并跳登录页',
      '修复（关键）：InterviewView SSE fetch 403 也跳登录页（fetch 不走 axios 拦截器）',
      '修复：ResumeView 上传文件类型校验与后端对齐，补充 HTML/HTM/MD/MARKDOWN（原仅 PDF/TXT 导致上传被前端拒绝）',
      '修复：ResumeController 错误提示文案与实际支持格式对齐'
    ]
  },
  {
    version: '1.5.0',
    date: '2026-07-05',
    title: '版本 1.5.0 · 全面质量升级 + AI 性能优化 + UI 去AI化',
    items: [
      '安全修复（P0）：缓存 key 加入 userId，防止跨用户缓存串扰隐私泄露',
      '安全修复（P0）：isTrustedProxy 精确校验 172.16-31 网段，防 IP 伪造绕过限流',
      '安全修复（P0）：移除 application.yml 中泄露的 Supabase 项目引用 ID',
      '安全修复（P0）：前端引入 DOMPurify 消毒所有 v-html，防 XSS',
      '安全修复（P1）：CORS 收紧为精确域名，移除通配符',
      '安全修复（P1）：JWT 加入 issuer/audience 声明，防 token 跨服务重放',
      '安全修复（P1）：登录失败计数器增加定期清理，防内存泄漏',
      'AI 性能优化：generateQuestions 加入并发控制 Semaphore=5，防 API 限流',
      'AI 性能优化：RAG 检索 topK 从 5 降到 2 + 检测 SimpleVectorStore 短路跳过',
      'AI 性能优化：Prompt 精简，简历文本截断到 800 字，去除岗位描述重复注入',
      'AI 性能优化：AiConfig 移除硬编码 Java 后端默认 system prompt',
      'AI 性能优化：SSE Disposable 保存与取消，客户端断开时释放 AI 订阅防泄漏',
      'UI 美化：HomeView 重写，去除光晕/网格/玻璃态/紫青渐变等 AI slop 元素',
      'UI 美化：全局 18 处硬编码靛蓝色替换为品牌色深墨绿',
      'UI 美化：6 个文件 7 处装饰性 emoji 替换为 SVG 图标',
      '功能完善：评估结果新增「表达力」分数展示',
      '功能完善：题目数量支持直接输入（原仅 +/- 按钮）',
      '功能完善：main.ts 移除全量图标注册（减包体积 200KB）',
      '功能完善：路由 chunk 加载失败自动刷新，防白屏',
      '功能完善：流式请求增加 60s 超时兜底 + reader 释放',
      '功能完善：面试题生成失败时清理已创建会话，防孤儿会话',
      '工程化：package.json 版本同步至 1.5.0'
    ]
  },
  {
    version: '1.4.1',
    date: '2026-07-05',
    title: '版本 1.4.1 · 用户名支持中文',
    items: [
      '注册用户名支持中文字符：可使用中文、字母、数字、下划线组合',
      '用户名最小长度由 3 位放宽至 2 位，方便中文用户名（如「小明」）',
      '前后端校验规则同步更新：正则 ^[A-Za-z0-9_\\u4e00-\\u9fa5]+$ 允许中文',
      '前端提示文案更新：「2-32 位，支持中文」，错误提示同步中文说明',
      '后端 AuthController 注册接口校验逻辑与前端完全对齐'
    ]
  },
  {
    version: '1.4.0',
    date: '2026-07-05',
    title: '版本 1.4.0 · 注册登录体验优化 + Cloudflare Pages 部署',
    items: [
      '登录注册超时优化：认证接口单独 90s 超时，兜底 Render 冷启动',
      '错误信息区分场景：AI 接口/认证接口/普通接口分别提示不同错误信息',
      '登录页新增密码显示/隐藏切换：避免密码输入错误',
      '登录页新增"记住用户名"功能：本地存储用户名方便下次登录',
      '登录页新增冷启动提示：网络错误时显示"后端服务正在冷启动（30-60s）"',
      '登录页新增重试按钮：网络错误后可一键重试，无需重新输入',
      '注册表单实时校验：用户名/密码/邮箱输入时即时反馈错误',
      '后端登录失败限流：IP 维度 5 次失败后锁定 5 分钟，防暴力破解',
      '后端返回剩余尝试次数：用户名或密码错误时提示"还可尝试 X 次"',
      'Cloudflare Pages 部署支持：国内访问更稳定',
      '简历分析支持 HTML/MD 格式：新增 jsoup 解析 HTML 简历',
      'CORS 允许 *.pages.dev 域名：支持 Cloudflare Pages 跨域请求'
    ]
  },
  {
    version: '1.3.0',
    date: '2026-07-05',
    title: '版本 1.3.0 · 简历分析稳定性强化 + 全行业岗位支持',
    items: [
      '简历分析全链路加固：新增兜底 JSON 机制，AI 返回异常时不再报错',
      'JSON 修复工具升级：括号配对算法，正确处理嵌套单引号和前缀文本中的 {',
      '前端抽取公共 jsonRepair util，与后端修复能力完全对齐',
      'ResumeView/ResumeHistoryView：score 字段强制数字转换，避免字符串 "75" 导致颜色错误',
      'ResumeView：watch 递归修复副作用，使用标志位避免循环触发',
      'ResumeAnalysisService：去除 Java 硬编码，根据任意岗位动态生成面试官角色',
      'ResumeAnalysisService：缓存命中也走修复流程，兼容历史脏数据',
      'ResumeService：持久化前增加合法性校验，JSONB 字段不再写入失败',
      'ResumeParseService：TXT 文件显式指定 UTF-8 字符集，修复 Windows 中文乱码',
      'ResumeController：PDF magic bytes 校验改用 try-with-resources，修复资源泄漏',
      'api/index.ts：getErrMessage 优先返回后端友好 message，HTTP 5xx 不再显示 axios 默认信息',
      'api/index.ts：HTML 响应检测修复 null data 时的 TypeError',
      '岗位输入支持 46 个常见行业：开发/产品/设计/医疗/法律/财务/销售/教育/工程/建筑/供应链等',
      'prompt 强化：明确禁止反斜杠未转义、明确 score 必须为整数类型、移除 weaknesses 字段'
    ]
  },
  {
    version: '1.2.0',
    date: '2026-07-05',
    title: '版本 1.2.0 · 全岗位支持 + 知识库关联面试',
    items: [
      '修复 Network Error：vercel.json 添加 /api 反向代理到 Render 后端',
      '修复 JSON 解析错误：字符串内裸换行符自动转义（Bad control character）',
      '修复 JSON 解析错误：单引号、中文引号、中文冒号自动修复',
      '模拟面试支持所有岗位：去除 Java 硬编码，根据岗位动态生成面试官角色',
      '岗位输入支持自动补全（18 个常见岗位）',
      '知识库新增「错题总结」：关联所有模拟面试，按阈值汇总错题',
      '知识库新增「题目汇总」：按分类、难度聚合统计答题情况',
      'SSE 流式接口优化：心跳保活、首 token 优化、即时反馈',
      '前端 UI 全面重设计：编辑风专业平台，深墨绿主色 + 暖米白背景',
      '安全加固：注册接口参数校验、CORS 配置收紧'
    ]
  },
  {
    version: '1.1.0',
    date: '2026-07-04',
    title: '版本 1.1.0 · 设计系统 v3 + 交付完善',
    items: [
      '设计系统 v3：编辑风专业平台，消除 AI slop 美学',
      '深墨绿主色 #0f766e + 暖米白背景 #fafaf9 + 衬线标题字体',
      '所有页面重设计：HomeView / LoginView / InterviewView / HistoryView 等',
      'JsonRepairUtil：两阶段状态机修复非标准 JSON',
      'SSE 优化：心跳保活避免 Vercel/Nginx 60s 超时',
      'AuthController 安全加固：用户名/密码/邮箱校验',
      '34 个后端测试通过'
    ]
  },
  {
    version: '1.0.0',
    date: '2026-07-03',
    title: '版本 1.0.0 · 初始发布',
    items: [
      'Vue 3.4 + TypeScript + Vite 5 前端',
      'Spring Boot 3.3.6 + Spring AI 1.0 + PostgreSQL/pgvector 后端',
      '简历分析：AI 多维度评估 + 改进建议',
      '模拟面试：流式 AI 实时提示 + 自动评估打分',
      'RAG 知识库：语义检索 + 增强问答',
      'JWT 鉴权 + Redis 限流 + Supabase 文件存储'
    ]
  }
]
