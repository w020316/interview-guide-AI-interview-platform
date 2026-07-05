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

export const CURRENT_VERSION = '1.6.0'

export const CHANGELOG: ChangelogEntry[] = [
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
