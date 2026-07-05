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

export const CURRENT_VERSION = '1.4.0'

export const CHANGELOG: ChangelogEntry[] = [
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
