/**
 * 版本更新日志
 * 每次发布新增版本条目，前端会与 localStorage 中的版本号对比
 * 若版本不同则弹窗展示本次更新内容
 *
 * ── 面向用户的内容筛选（v1.33.3）────────────────────────────────────
 * 每条更新带 `level` 标记决定是否展示给普通用户：
 *   - 'user'（默认）：用户能直接感知的功能/体验变化 → 弹窗中展示
 *   - 'tech'：架构改动、依赖升级、测试数、内部重构、构建配置等 → 弹窗中隐藏
 *
 * 历史版本遗留的纯字符串条目会按关键词自动归类（见 TECH_ITEM_PATTERNS），
 * 避免为了隐藏技术细节而去逐条改写上百条历史数据。
 */
export type ChangelogLevel = 'user' | 'tech'

export interface ChangelogEntry {
  version: string
  date: string
  title: string
  items: Array<string | { text: string; level?: ChangelogLevel }>
}

/** 技术类条目特征词：命中即视为「程序员的更新」，对用户隐藏 */
const TECH_ITEM_PATTERNS: RegExp[] = [
  /^\s*(后端|前端|架构|依赖|构建|工程|运维|测试)\s*[:：]/,
  /单元测试|集成测试|测试用例|全绿|回归用例|用例|覆盖率|jacoco|JaCoCo/,
  /重构|内部实现|代码审查|代码审查|审查|BeanPostProcessor|AutoConfiguration/,
  /依赖升级|依赖漏洞|Dependabot|npm audit|CI\b|构建提速|打包|构建配置|构建引擎|rolldown/,
  /反射|线程模型|连接池|JVM|GC\b|内存|Metaspace|堆内存|OOM/,
  /幂等|单飞|信号量|并发闸门|线程池|异步|拦截器|过滤器|中间件/,
  /环境变量|配置项|启动失败|启动崩溃|上下文|占位符|注入|密钥清洗/,
  /接口|端点|API\b|REST|DTO|实体|实体类|Mapper|Repository|SQL\b|索引优化|建表/,
  /CORS|预检|跨域|请求头|白名单|HTTP\s*状态|缓存策略/,
  /SSE\b|流式（?推|输）|超时|重试|降级|限流|熔断/,
  /日志|埋点|监控|告警|指标|Prometheus|Micrometer|actuator/i,
  /版本号|版本：|v\d+\.\d+\.\d+/,
]

/** 判断一条更新是否属于「仅供开发/运维查看」的技术细节 */
export function isTechItem(raw: string | { text: string; level?: ChangelogLevel }): boolean {
  if (typeof raw === 'object' && raw !== null) {
    if (raw.level) return raw.level === 'tech'
    return isTechItem(raw.text)
  }
  return TECH_ITEM_PATTERNS.some((re) => re.test(raw))
}

/** 取条目文本 */
export function itemText(raw: string | { text: string; level?: ChangelogLevel }): string {
  return typeof raw === 'string' ? raw : raw.text
}

/**
 * 是否自动弹出「版本更新」以及是否显示未读提示（v1.34.1，UX P2-3）。
 *
 * 背景：此前只要 localStorage 的已见版本与当前版本不同就**立即**自动弹模态框，
 * 于是首次访问的用户一进站就被盖住 hero 与主 CTA（实测评审截图确认遮挡）。
 *
 * 现按访问类型区分：
 * - `seen === null`（首次访问）：不打扰，仅标记未读（前端在版本入口显示小红点）；
 * - `seen !== CURRENT_VERSION`（老用户遇到新版本）：延迟自动弹出，保留更新提醒意图；
 * - `seen === CURRENT_VERSION`：无动作。
 *
 * 独立成纯函数以便单测锁定这一交互决策（组件内含 localStorage 与定时器，不易直测）。
 */
export interface ChangelogDecision {
  /** 是否自动弹出弹窗（组件负责延迟） */
  open: boolean
  /** 是否在版本入口显示未读小红点 */
  unread: boolean
}

export function decideChangelogAction(seen: string | null): ChangelogDecision {
  if (seen === CURRENT_VERSION) {
    return { open: false, unread: false }
  }
  if (seen === null) {
    // 首次访问：不打扰，先让用户看懂产品
    return { open: false, unread: true }
  }
  // 老用户 + 新版本
  return { open: true, unread: true }
}

/** 老用户遇到新版本时自动弹窗的延迟（毫秒）：让首屏先渲染完成 */
export const CHANGELOG_AUTO_OPEN_DELAY_MS = 1500

export const CURRENT_VERSION = '1.43.0'

export const CHANGELOG: ChangelogEntry[] = [
  {
    version: '1.43.0',
    date: '2026-09-24',
    title: '版本 1.43.0 · 数字与状态回归真实',
    items: [
      { text: '首页：准备度演示卡不再对所有人展示同一套硬编码分数——未登录时明确标注「示例」；已登录用户展示自己最新一次简历评估的真实分数与维度（还没有评估记录则显示空态与引导，而不是虚构的「击败 78% 求职者」「面试题已就绪」）', level: 'user' },
      { text: '管理后台：岗位列表默认收窄到「国内岗位」——此前海外远程源批量导入后按导入时间倒序，首屏整屏都是海外岗位，容易误以为国内数据丢失；可一键切换海外/全部', level: 'user' },
      { text: '管理后台：系统指标的「缓存」区块新增 RAG 检索缓存的命中/未命中/命中率——该缓存此前只在内部计数，指标页永远显示 0/0，优化效果无法被观测', level: 'user' },
      { text: '运维：修复「知识库文档数」重启漂移——文档计数是进程内累加值，重启后只回补预置知识的数量，用户导入的部分全部漏计，容量上限检查因此形同虚设；现改为启动及播种后直接以向量库真实行数校准', level: 'tech' },
    ],
  },
  {
    version: '1.42.1',
    date: '2026-09-23',
    title: '版本 1.42.1 · 管理后台图例补全',
    items: [
      { text: '管理后台：数据总览「招聘类型分布」的图例里，有一个类型显示的是原始英文标识（PART_TIME）而不是中文；现已补为「兼职」，与招聘广场的口径一致', level: 'user' },
      { text: '前端：把招聘类型的中文标签抽到独立模块并导出取值清单，新增单测断言「后端所有取值都有中文标签」——这类漏配此前只会表现为界面上冒出一串英文，没有任何测试会拦下来', level: 'tech' },
    ],
  },
  {
    version: '1.42.0',
    date: '2026-09-23',
    title: '版本 1.42.0 · 知识库状态不再误报',
    items: [
      { text: '知识库：修复「知识库暂时不可用」的误报——此前若服务启动时恰好赶上向量服务处于冷启动，首次初始化失败后就不再重试，于是状态被永久标记为不可用（而实际上导入、检索都是正常的）。现在失败会自动按 1/5/15/60 分钟退避重试，恢复后状态随之转为可用', level: 'user' },
      { text: '管理后台：数据总览的「招聘类型分布」此前一直显示「暂无数据」，其实接口一直有数据（是页面判断写错了对象属性）。现已正常展示各类型的岗位数量与占比', level: 'user' },
      { text: '运维可观测：系统指标的「P95 耗时」此前恒为 0（与平均耗时几十秒自相矛盾）——根因是指标库的耗时统计默认只覆盖 30 秒以内，而 AI 调用动辄 20~70 秒，超出的样本被直接丢弃。现已上调统计范围到 3 分钟', level: 'tech' },
      { text: '安全：embedding 服务的共享令牌此前以明文写在公开仓库的部署配置里，实测外部可凭该令牌直接调用该服务。现已移出仓库改为平台上维护，并把服务的鉴权语义改为「未配置令牌即拒绝」（旧实现是「未配置则不校验」，误删变量会让服务对公网完全开放）', level: 'tech' },
      { text: '安全：embedding 服务的令牌现支持逗号分隔多值，可用于零中断轮换（先同时接受新旧令牌 → 再切换调用方 → 最后移除旧令牌），过程中不会出现认证失败窗口', level: 'tech' },
      { text: '配置：兜底模型 glm-4.5-flash 已于 2026-01-30 下线（请求被上游静默路由到 glm-4.7-flash，配置错误因此长期不可见），现改为显式指定 glm-4.7-flash', level: 'tech' },
      { text: '文档：修正部署配置里关于向量服务的注释（原注释描述的是已弃用的另一套方案，且写着「维度必须 1024」，而实际是自托管模型的 512 维——照注释改会直接导致知识库不可用）', level: 'tech' },
    ],
  },
  {
    version: '1.41.1',
    date: '2026-09-23',
    title: '版本 1.41.1 · 搜索框更早拦住不合规输入',
    items: [
      { text: '招聘广场：搜索词里含引号、分号等特殊符号时，现在会在请求发出之前直接提示「仅支持中英文、数字与常见符号」，不用再等请求被安全网关拦掉、看到一个看不懂的网络错误', level: 'user' },
      { text: '运维可观测：线上复验发现边缘安全网关在应用之前就拦掉了这类查询串，后端入口校验对它们永远不会执行；因此把校验前移到前端（规则与后端一致），后端那道保留用于拦截能到达应用的非法字符（如分号、百分号）', level: 'tech' },
    ],
  },
  {
    version: '1.41.0',
    date: '2026-09-23',
    title: '版本 1.41.0 · 数字更可信，等待更可懂',
    items: [
      { text: '招聘广场：「招聘类型」标签上的数字现在跟着分栏走——此前切到「全部国内」仍显示含海外岗位的全局数字（显示「社招 1883」，该分栏实际只有 71 条），点进去会让人以为数据丢了一大半', level: 'user' },
      { text: '招聘广场：搜索词里含引号、分号等特殊符号时，不再弹出「后端服务正在冷启动」这种毫不相干的提示，而是直接说明该输入不被支持、请修改后重试', level: 'user' },
      { text: '管理后台：禁用用户的提示与真实行为对齐了——禁用是持久保存的（服务重启后仍然生效），并在禁用前要求二次确认；此前文案写「重启自动恢复」，很容易被当成临时封禁来用', level: 'user' },
      { text: '管理后台：现在不能禁用你自己的账号，也不能禁用最后一个管理员账号（此前一次误点会让管理后台永久进不去，只能手工改数据库恢复）', level: 'user' },
      { text: '复盘报告：「与历史成绩对比」不再对老用户显示「首次」——只有确实没有任何历史记录才算首次；历史数据读取失败时会如实说明，而不是谎称你是新手', level: 'user' },
      { text: '模拟面试：评分等待期间会显示已用时长，超过 15 秒提示「正在深度评估，请耐心等待」。AI 评分实测 11 秒到 1 分钟以上都有，此前只有按钮转圈，容易让人以为卡死而刷新页面、丢掉答题进度', level: 'user' },
      { text: '知识库：同一个问题的重复检索会直接命中缓存，不再每次都等知识库唤醒（首次检索仍可能需要 20–30 秒，界面已明确说明）', level: 'user' },
      { text: '招聘广场：/api/jobs/meta 支持 overseas 参数，按分栏返回 recruitCounts；入口处对搜索关键词做白名单与长度校验（≤50 字符，仅中英文、数字与常见符号），从源头避免请求打到边缘安全网关', level: 'tech' },
      { text: '管理后台：AdminService.banUser 增加「不能禁用自己」「不能禁用最后一个可用管理员」两道服务端校验（按 app.admin-usernames 统计可用管理员数）', level: 'tech' },
      { text: '知识库：RagSearchService 对 (userId, topK, query) 加 10 分钟 TTL 缓存（含空结果），检索失败不写缓存；知识导入时主动清缓存，避免新知识 10 分钟内检索不到', level: 'tech' },
      { text: '复盘报告：历史对比区分「真首次 / 有会话但趋势为空 / 加载失败」三态，不再把数据异常降级成「首次」', level: 'tech' },
      { text: '网络层：401 与 403 语义分离——只有 401 才清 token 跳登录，403 保留会话仅提示；裸 Network Error 不再被当作后端冷启动信号（既不误报文案，也不再静默重放一次请求）', level: 'tech' },
    ],
  },
  {
    version: '1.40.0',
    date: '2026-09-23',
    title: '版本 1.40.0 · 面试记录与评分更可靠了',
    items: [
      { text: '模拟面试：修复答完整场面试后「什么都没留下」的问题——此前题目没能存下来，导致面试历史、错题本、成长趋势里全是空的，而界面上也不会有任何提示；现在记录会正常保存，万一保存失败也会明确告诉你', level: 'user' },
      { text: '模拟面试：修复评分结果偶尔不完整、评分面板四个维度全显示「-」却没有报错的问题；遇到这种情况会自动重试一次，仍然不行会明确提示重试，而不是给你一个看不懂的空面板', level: 'user' },
      { text: '模拟面试：修复「综合分」与三个维度分数对不上的问题（曾出现维度 15 / 8 / 95、综合却显示 70 分的情况）。现在综合分严格按 完整性 30% + 准确性 40% + 表达力 30% 计算，你可以自己复算核对', level: 'user' },
      { text: '模拟面试：评分现在会结合题目的参考答案逐项核对，不再只凭印象给分，分数与评语更贴合你实际答到了哪些要点', level: 'user' },
      { text: '模拟面试：修复语音作答说了一段之后按钮卡在「语音识别中…」、再点也没反应的问题——此前一旦卡住只能刷新页面', level: 'user' },
      { text: '模拟面试：语音作答的语速提示不再出现「语速 适中（0 字/分）」这种自相矛盾的文案，没听清时会直接告诉你怎么处理', level: 'user' },
      { text: '模拟面试：从面试历史继续答题时，复盘报告会写明「本次新作答 N 题（该会话累计已作答 M 题）」，不会再让人以为报告把之前的题弄丢了', level: 'user' },
      { text: '模拟面试：从面试历史继续答题后，复盘报告与分享卡片的标题不再显示「未指定岗位」，会带上这场面试真实的岗位名称', level: 'user' },
      { text: '模拟面试：复盘报告不会再因为点了一下弹窗外的空白处就关闭、并且再也找不回来；关闭只保留明确的按钮与右上角关闭', level: 'user' },
      { text: '模拟面试：作答附图上传失败时的提示更明确——如果服务端还没配好图片存储会直接说明，不再统一回一句「请稍后重试」', level: 'user' },
      { text: '账号安全：登录失败锁定不再只看网络地址——此前换个网络就能对同一个账号无限次试密码；现在同一账号连续输错会被真正锁定 5 分钟', level: 'user' },
      { text: '知识库：修复简历始终无法进入知识库、导致 AI 出题时检索不到你简历内容的问题', level: 'user' },
      { text: '模拟面试：评分结果新增结构校验，四维分数缺失或格式异常时不再当作成功下发（这正是「面板全显示 -」的根因）；校验失败自动重试一次，两次都失败才报错', level: 'tech' },
      { text: '模拟面试：AI 评分提示词中「要求引用原句」与「禁止在字符串里用引号」两条规则自相矛盾，导致约三分之一的响应 JSON 被引号击穿；现已统一为「引用一律用书名号」', level: 'tech' },
      { text: '模拟面试：修复题目入库的请求体契约不匹配（AI 返回的 keyPoints 是数组，接口却只接受字符串），这是答题记录全部丢失的根因；新增回归测试锁定', level: 'tech' },
      { text: '登录安全：登录失败计数由「仅按 IP」改为「IP + 账号」双维度，账号维度不随 IP 轮换重置；同时新增 app.security.trusted-proxy-prefixes 配置，供反向代理部署下恢复真实客户端 IP 解析', level: 'tech' },
      { text: '知识库：修复简历向量化文档 ID 长度超出数据库 uuid 列上限（拼接后约 58 字符）导致「UUID string too large」入库失败的问题，改为派生 36 字符确定性 UUID，保留「同简历覆盖」语义', level: 'tech' },
      { text: '运维可观测：/api/health/detail 新增 storage 区块（配置状态 + 最近一次上传失败原因）并计入 degraded；此前作答附图 100% 失败却体检全绿，从外部无法区分「没配 / 配错 / bucket 不存在」', level: 'tech' },
      { text: '运维可观测：修正 Storage 占位符告警的检测字符串与配置默认值不一致的问题（原检测 placeholder.supabase.co，而 base 配置默认值是 your-project.supabase.co，非 prod profile 下告警永不触发）', level: 'tech' },
    ],
  },
  {
    version: '1.39.0',
    date: '2026-09-23',
    title: '版本 1.39.0 · 招聘广场国内岗位更多了',
    items: [
      { text: '招聘广场：新增「社招精选」数据源，补上 47 个国内社招岗位，覆盖互联网、金融、制造与新能源、医药、教育、消费零售、物流、建筑能源、职能、设计传媒等方向，已工作几年想跳槽的用户不再只能看到校招岗', level: 'user' },
      { text: '招聘广场：默认列表改为「全部国内」，海外远程岗位只在「海外远程」分栏出现——此前海外岗位数量远多于国内，一打开广场满屏都是英文职位', level: 'user' },
      { text: '招聘广场：数据来源筛选会跟着分栏走，不会再出现「点了某个来源一条都筛不出来」的情况', level: 'user' },
      { text: '简历分析：修复手机上已安装对应软件却提示「未检测到 App」、导致无法跳转的问题——此前只要系统弹一次确认框就会被误判为没装；现在每张卡片下方都常驻「打开网页版」入口，任何情况下都有路可走', level: 'user' },
      { text: '简历分析：在微信、钉钉等内置浏览器里打开时会直接说明原因并给出网页版入口，不再假装「唤起失败」', level: 'user' },
      { text: '首页：修复「本轮准备度」演示卡片的浮动标签压住标题文字的问题', level: 'user' },
      { text: '登录页：注册入口此前在同一页出现了三次，现在收敛为一处；「100% 免费使用」改为直白的「免费 · 全部功能开放」', level: 'user' },
      { text: '全站观感：卡片与小标题不再使用衬线字体（此前在 Windows 与多数安卓设备上会退化成宋体，小字号下发虚、显旧），标题层级改由字重与字距建立；数字与得分统一等宽显示，列表更整齐', level: 'user' },
      { text: '全站观感：修复暗色主题下部分区块仍是浅底深字、像贴了块补丁的问题（收藏按钮、截止提醒横幅、能力分层标签、解析告警等）', level: 'user' },
      { text: '全站观感：补齐按钮的按下反馈与锚点平滑滚动，点起来更有实感', level: 'user' },
      { text: '前端：字体拆为展示级衬线 / 界面级无衬线 / 等宽数字三级，批量迁移 36 处内联字体声明；新增 text-wrap: balance 防标题孤字、tabular-nums 数字对齐', level: 'tech' },
      { text: '前端：新增 utils/appLaunch.ts，用 visibilitychange / pagehide / blur 三信号判断 App 是否交棒成功（原实现只看 visibilityState，在 iOS 系统确认框、部分 Android ROM 与宿主 WebView 下必然误报），配套 17 条单元测试', level: 'tech' },
      { text: '后端：公开招聘 API 增加单源单轮入库配额 25 条，避免五个海外源一次灌入数百条英文岗位淹没国内岗位', level: 'tech' },
      { text: '后端：新增 SeedDomesticSocialJobProvider 种子数据源（社招精选），并同步更新种子数据一致性测试', level: 'tech' },
      { text: '后端：修复 /api/info 的版本号写死为 1.0.0、不读配置的问题——此前它与 /actuator/info 报出两个不同版本号，与「版本号需和 changelog.ts 同步」的约定相悖；配套用例原先断言的正是那个写死值，等于把 bug 一起锁进了测试', level: 'tech' },
      { text: '验证：后端 883 个用例全过（BUILD SUCCESS）、前端 313 个用例全过、类型检查 0 错误、生产构建通过', level: 'tech' },
    ]
  },
  {
    version: '1.38.1',
    date: '2026-09-22',
    title: '版本 1.38.1 · 登录等待体验修复',
    items: [
      { text: '登录体验：修复长时间未使用后打开网站，登录会长时间无响应、连续两次都进不去的问题——等待期间现在会显示真实进度，超时后可直接「继续等待」，不必再刷新页面把已等待的时间全部作废', level: 'user' },
      { text: '登录体验：等待提示改为如实说明（免费实例冷启动通常 5-6 分钟），不再让用户在 2 分钟后误以为程序坏了，也不再在服务只是启动较慢时提示「请检查网络」', level: 'user' },
      { text: '运维：保活主力由 GitHub Actions 定时任务改为 Cloudflare Worker 定时触发——实测 GitHub 的定时任务被降级到 3~6 小时才跑一次，导致后端几乎一直处于休眠状态（脚本 scripts/keepalive-worker.mjs，步骤 docs/keepalive-setup.md）', level: 'tech' },
      { text: '前端：冷启动唤醒预算 150s → 480s、单次探测超时 20s → 45s（实例启动期间请求会被挂起，20s 超时会反复中断探测）；新增 1s 进度心跳，避免等待数字长时间不动；「继续等待」改为追加预算而非清零重来', level: 'tech' },
      { text: '前端：探测判定收紧——边缘节点 502/503/504 且响应体不是业务 JSON 时不再视为已就绪，避免把「尚未启动」误判为就绪后紧接着登录秒失败', level: 'tech' },
      { text: '验证：前端 291 个用例全过、类型检查 0 错误、生产构建通过', level: 'tech' },
    ]
  },
  {
    version: '1.38.0',
    date: '2026-09-22',
    title: '版本 1.38.0 · 兼职上线，海外远程独立成栏',
    items: [
      { text: '招聘广场：新增「海外远程」分栏，海外岗位与国内岗位分开看——按「秋招」筛选时不会再被英文海外职位稀释', level: 'user' },
      { text: '招聘广场：新增「兼职」分栏，收录门店、餐饮、配送、在线答疑、数据标注、促销、校园大使等兼职岗位，薪资按时薪/日薪/单价展示', level: 'user' },
      { text: '招聘广场：秋招岗位大幅增加（新增 60 个主流雇主的 2027 届岗位，覆盖互联网、芯片通信、汽车新能源、银行、快消、游戏、央企、咨询等方向）', level: 'user' },
      { text: '招聘广场：海外岗位来源新增 Jobicy 与 Himalayas 两家，海外远程岗位数量接近翻倍', level: 'user' },
      { text: '智能体：修复「问社招岗位却只返回秋招结果」的问题——此前智能体在没有明确类型时会把范围锁死在秋招，现在改为不限类型，并在回答里说明检索范围与命中数量', level: 'user' },
      { text: '智能体：岗位回答会标注每条岗位的招聘类型与数据来源，并明确说明「在你给的条件范围内没找到」而不是笼统地说没有岗位', level: 'user' },
      { text: '智能体：修复偶发地把内部调用指令整段显示成回答的问题（此前会看到形如 {"action": "searchJobs"} 的原始 JSON）', level: 'user' },
      { text: '管理后台：新增数据源拉取失败告警，某个数据源挂掉时总览页会直接列出是哪个源、什么原因、连续失败几次；数据源页新增「最近拉取」列', level: 'user' },
      { text: '后端：新增 JobSourceHealthRegistry 记录每个数据源的最近一次拉取结果（成败/条数/耗时/错误摘要/连续失败次数）', level: 'tech' },
      { text: '后端：修复 open-api-enabled=false 只影响后台展示、实际仍会请求海外 API 的问题（刷新前补 isEnabled 判断）', level: 'tech' },
      { text: '后端：公开 API 数据源失败时改为抛异常交由调度层统一登记，使「源挂了」与「源正常但没岗位」可被区分；并按 6 小时冷却节流，避免高频打扰上游', level: 'tech' },
      { text: '后端：JobPlatformAdapter 新增 overseas() 与 minRefreshIntervalMs() 声明；检索新增 overseas 条件（IN / NOT IN 适配器声明的海外源清单），旧 10 参数签名保留兼容', level: 'tech' },
      { text: '后端：JsonRepairUtil 支持 Python 字面量（True/False/None → true/false/null），只匹配值位置以免误伤字符串文案；AgentService 增加「疑似动作载荷但解析失败」防御，避免原始 JSON 被当回答推送', level: 'tech' },
      { text: '后端：智能体 searchJobs 新增 overseas 参数并默认只查国内；fallbackToLocalJobs 口径对齐', level: 'tech' },
      { text: '验证：本地启动 embedding + 后端跑通 39 项 AI 端到端断言（简历分析/优化、出题、评估、RAG、SSE、智能体岗位检索防编造）', level: 'tech' },
      { text: '后端：新增 Jobicy / Himalayas 两个公开数据源适配器，以及秋招精选2027（60 条）、兼职专区（36 条）两个种子数据源', level: 'tech' },
      { text: '测试：后端新增数据源健康、海外分栏、种子数据一致性与两个新 API 源解析用例，前端类型检查与构建通过', level: 'tech' },
    ]
  },
  {
    version: '1.37.0',
    date: '2026-09-22',
    title: '版本 1.37.0 · 导航更清爽，岗位更多更全',
    items: [
      { text: '顶部导航：原来挤在一起的十几个入口重新梳理为「5 个主入口 + 求职工具下拉菜单」，找功能更快，页面顶部不再拥挤', level: 'user' },
      { text: '顶部导航：手机端改为分组抽屉菜单，各项功能不再需要左右滑动查找', level: 'user' },
      { text: '简历分析：新增「从其他软件提取简历」，可一键直达微信、QQ、钉钉、WPS 云文档、腾讯文档、百度网盘、超级简历、BOSS 直聘取简历；新增「选择本机文件」按钮，从电脑文件夹或手机「文件」App 直接选取', level: 'user' },
      { text: '招聘广场：岗位数量大幅增加（新增近百条覆盖制造、能源、建筑、医药、消费、物流、农业、金融、传媒、政务、法律、教育等行业的岗位），不再只集中在互联网大厂', level: 'user' },
      { text: '招聘广场：接入 3 个公开岗位数据源，并新增「数据来源」快捷筛选，可一眼看到岗位来自哪些渠道、按来源筛选', level: 'user' },
      { text: '招聘广场：新增秋招/春招/实习之外的定向专项（选调生、三支一扶、西部计划、军队文职、特岗教师）与欧洲/全球远程岗位', level: 'user' },
      { text: '管理后台：新增「数据源」页，可查看每个数据源是否正常、贡献了多少岗位、最近一次更新是什么时候，某个渠道不再默默失效', level: 'user' },
      { text: '管理后台：数据总览新增数据源分布、招聘类型分布与近 7 天新增趋势图，岗位管理的筛选条件新增来源、招聘类型、有效/失效', level: 'user' },
      { text: '管理后台：界面配色统一到全站设计规范，深色模式下文字不再看不清', level: 'user' },
      { text: '后端：新增公开招聘数据源适配器体系（AbstractOpenApiJobProvider + RemoteOK / Remotive / Arbeitnow），含失败隔离、列长裁剪、HTML 转纯文本；可用 app.job-agent.open-api-enabled 一键关闭', level: 'tech' },
      { text: '后端：新增行业精选（53 条）与服务精选（43 条）种子数据源，覆盖实体产业与服务/公共部门，补齐此前完全缺位的行业方向', level: 'tech' },
      { text: '后端：新增 GET /api/admin/sources 数据源健康接口；GET /api/admin/jobs 支持 source / recruitType / active 筛选；GET /api/admin/overview 返回 sourceDist / recruitDist / trend', level: 'tech' },
      { text: '后端：聚合统计刻意不在 SQL 中按天分组（DATE() 在 H2 与 PostgreSQL 写法不一致，易「本地绿、线上红」），改为取回时间戳后 Java 侧归组', level: 'tech' },
      { text: '前端：App.vue 导航重构——自定义 dropdown（hover/点击/ESC/外部点击/键盘可达）+ ≤960px 分组抽屉；导航滚动浮起阴影', level: 'tech' },
      { text: '前端：AdminView 全面改用设计 token，新增 conic-gradient 环形图与纯 CSS 条形图、柱状趋势图（不引入图表库）', level: 'tech' },
      { text: '测试：后端用例新增数据源/总览分布与趋势断言，前端 286 例全绿', level: 'tech' },
    ]
  },
  {
    version: '1.33.3',
    date: '2026-09-19',
    title: '版本 1.33.3 · 登录更稳，知识问答更准',
    items: [
      { text: '登录体验：修复长时间未使用后打开网站，登录页会长时间无响应的问题。现在页面会主动唤醒服务，并在输入账号密码期间提前开始准备，登录通常一次成功', level: 'user' },
      { text: '登录体验：等待期间会显示实时进度（已等待多少秒），不再出现「点了登录没反应」的困惑', level: 'user' },
      { text: '登录体验：网络恢复或服务刚启动完成时，会自动重试而不是直接报错', level: 'user' },
      { text: '知识问答：修复「AI 问答」始终提示「参考资料中没有相关内容」的问题——现在内置了覆盖 Java、Spring、数据库、Redis、网络、算法、系统设计的面试知识库，提问会自动引用相关资料作答', level: 'user' },
      { text: '知识问答：内置题库为所有账号共享，新注册用户无需自行导入即可直接使用', level: 'user' },
      { text: '稳定性：AI 服务临时不可用时给出更清晰的提示，不再是一句笼统的报错', level: 'user' },
      { text: 'AI 故障诊断：降级链全失败时日志输出可诊断的一手原因（如「Invalid token」「insufficient balance」「model not found」），替代理先前无信息量的「Error while extracting response」', level: 'tech' },
      { text: '后端：新增共享知识库播种器（KnowledgeSeedInitializer），启动时把预置知识以 shared=true 写入向量库，受向量库容量上限保护，失败不阻断启动', level: 'tech' },
      { text: '后端：新增 app.rag.seed-enabled 开关（默认 true），可关闭启动播种', level: 'tech' },
      { text: '前端：更新弹窗按内容分级展示，技术类条目（架构/依赖/测试/内部实现）不再打扰普通用户', level: 'tech' },
      { text: '测试：新增共享知识库播种 6 例、AI 失败诊断 5 例回归用例，后端 620 → 631 例全绿', level: 'tech' },
      { text: '验证：真实 Edge（153.0.4234.32）真机复测登录/会话/多设备/AI 全链路', level: 'tech' },
    ]
  },
  {
    version: '1.33.2',
    date: '2026-09-19',
    title: '版本 1.33.2 · 冷启动预热修复（真机回归）',
    items: [
      { text: '登录体验：修复长时间闲置后首次打开网站时，后端预热在真实浏览器中完全失效、导致登录白等的问题', level: 'user' },
      { text: '登录体验：修复上条带来的「点登录后要等 2 分钟以上才发出请求」，现在会立即或在服务就绪后马上登录', level: 'user' },
      { text: '修复：预热探测在跨域下触发浏览器预检被拦截，导致实例已就绪却仍判定为未启动', level: 'tech' },
      { text: '修复：预热探测改走 URL 时间戳参数实现缓存失效，不再携带任何自定义请求头', level: 'tech' },
      { text: '修复：探测改用「任何 HTTP 响应即视为实例已唤醒」（含 4xx/5xx），仅网络层错误才判定未就绪', level: 'tech' },
      { text: '后端：CORS 允许请求头白名单补充 Cache-Control 作为防御性兜底', level: 'tech' },
      { text: '测试：前端 262 → 263 例全绿', level: 'tech' },
      { text: '验证：真机复测——修复前 80s 内重试 23 次全失败，修复后仅 1 次探测（184ms）即就绪', level: 'tech' },
    ]
  },
  {
    version: '1.33.1',
    date: '2026-09-19',
    title: '版本 1.33.1 · 冷启动韧性提升',
    items: [
      { text: '登录体验：修复服务冷启动导致首次登录必失败的问题（此前登录等待上限小于服务启动耗时）', level: 'user' },
      { text: '登录体验：打开网站即开始准备服务，让你在输入账号密码期间就完成等待', level: 'user' },
      { text: '登录体验：等待服务就绪时展示实时进度，不再出现「点了登录没反应」', level: 'user' },
      { text: '登录体验：服务刚启动完成时的临时错误现在可读且会自动重试', level: 'user' },
      { text: '稳定性：AI 服务未配置时不再导致整个网站（含登录）无法打开', level: 'user' },
      { text: '修复：幂等 GET 请求在冷启动/超时后自动唤醒并重放一次，长闲置后打开页面不再直接报错', level: 'tech' },
      { text: '修复：非幂等与 AI 请求不再被静默重放，避免网络抖动造成双份 AI 推理与重复入库', level: 'tech' },
      { text: '修复：Supabase Storage / Redis 配置缺失不再阻断启动，仅对应能力降级并在启动日志告警', level: 'tech' },
      { text: '修复：Embedding 密钥与降级链密钥不在空白字符清洗清单内，粘贴带入换行会导致该提供方全部 401', level: 'tech' },
      { text: '新增：Embedding 提供方与向量维度一致性启动体检，误配时打印可操作的告警', level: 'tech' },
      { text: '测试：后端 616 → 620 例、前端 262 例全绿', level: 'tech' },
    ]
  },
  {
    version: '1.32.0',
    date: '2026-09-13',
    title: '版本 1.32.0 · 网站可用性保障方案落地',
    items: [
      '新增：深度健康体检接口 /api/health（数据库/Redis/JVM/运行时长，匿名可访问），供监控与运维巡检',
      '新增：保活监控升级——每 10 分钟不仅唤醒后端，还校验数据库与 Redis 状态，异常即告警',
      '修复：生产 Redis 指向已删除实例导致 AI 缓存与登出吊销失效——更新为有效 Upstash 实例（improved-rabbit），/api/health redis=UP 验证通过',
      '修复：健康/信息探活端点从限流拦截器排除，避免监控与压测高频探活被误限流（429）',
      '后端：优雅停机（部署/重启不打断在途请求，减少 5xx）',
      '后端：Tomcat 线程模型与数据库连接池参数适配免费层（连接探测 + 泄漏预警）',
      '前端：补齐 favicon、SEO 元信息、主题色、后端 preconnect，减少首屏 404 与握手延迟',
      '前端：安全响应头升级（HSTS、API no-store 防缓存泄漏）',
      '安全：Dependabot 每周依赖漏洞扫描 + CI 增加 npm audit 高危阻断',
      '安全：依赖升级清除全部漏洞——vite 5→8、vitest 2→5、plugin-vue 5→6、dompurify 3.4.15，audit 0 漏洞；构建引擎切换 rolldown 后构建提速约 20 倍',
      '测试：新增 /api/health 集成测试（含降级场景）、并发压测脚本 scripts/loadtest.mjs',
      '文档：新增 docs/uptime-plan.md（性能/稳定性/监控/安全/应急预案/测试流程/维护清单）'
    ]
  },
  {
    version: '1.31.4',
    date: '2026-09-11',
    title: '版本 1.31.4 · 全面复核与稳定性加固',
    items: [
      '修复：AI 并发控制真正全局统一（面试出题/简历优化此前仍用独立信号量，最坏并发可达 15），避免免费模型限流下批量失败',
      '修复：智能体 30-60s 冷启动唤醒后重试不再失效，唤醒后能正常恢复回答',
      '修复：AI 提示冷启动重试增加次数上限，杜绝极端情况无限重试',
      '修复：长文本截断不再截断 emoji/生僻字（避免产生损坏字符）',
      '修复：智能体消息实体不再因懒加载关系在日志/哈希中触发异常',
      '安全：URL/图片导入与多模态附图增加服务端 SSRF 防护（拦截内网/云元数据/非公网地址）',
      '安全：作答附图上传禁止指定他人用户 ID，命名空间一律取自当前登录身份',
      '安全：招聘刷新与 AI 生成接口增加按用户限流，防止自动化刷配额',
      '健壮性：服务器错误不再透出内部细节、AI 返回的 JSON 修复不再误伤正常字符串内容、出题提示编号统一',
      '修复：退出登录偶发无反应（登出不再等待后端请求，立即清除会话并跳转）',
      '修复：后端启动崩溃循环（JobClassifyService 按具体类型注入 FallbackChatModel，与配置类按接口注册的 ChatModel Bean 不匹配，导致生产启动失败；改为按接口注入，并新增完整上下文启动冒烟测试防回归）',
      '新增：管理员身份体系（配置账号名单即可拥有无限制刷新等管理能力，普通用户不受影响）',
      '新增：内置管理员账号「小吴同学」启动自动创建，登录后即可使用管理后台（建议尽快修改初始密码）',
      '新增：管理后台页面（数据总览/手动刷新、岗位下架恢复删除、用户禁用解禁、系统指标），仅管理员可见',
      '完成全项目与 AI 模块第二轮全面审查，详见 docs/code-review-report.md、docs/ai-module-check-report.md'
    ]
  },
  {
    version: '1.31.3',
    date: '2026-09-11',
    title: '版本 1.31.3 · 稳定性与安全加固',
    items: [
      '修复：登录失败计数定时清理，防止长期运行内存泄漏',
      '修复：岗位实体 Builder 默认值，避免 active 字段为空',
      '修复：岗位分析 AI 调用纳入全局并发闸门（此前用独立信号量，并发预算分散）',
      '完成全项目代码审查 & 功能测试（316 单测全过），详见 docs/code-review-report.md、docs/ai-module-check-report.md'
    ]
  },
  {
    version: '1.31.2',
    date: '2026-09-11',
    title: '版本 1.31.2 · 智能体能力释放',
    items: [
      '智能体全面释放模型能力：现在可直接回答知识问答、平台/求职类元问题，不再局限于岗位查询',
      '回答更完整：放开长回答长度上限（1500→2500 token），复杂问题不截断',
      '更稳：模型调用自动重试一次 + 支持最多 8 步多工具推理，降低偶发失败导致的"没答上来"',
      '流式输出更顺滑：按行推送，长回答/列表体验更自然',
      '会话标题自动提炼：新会话首次回复后用模型生成精炼标题，历史会话更好识别',
      '优化：流式时未闭合的代码块自动保护渲染，避免代码/列表视觉闪烁',
      '新增能力：可直接在对话中粘贴简历要点，智能体为你推荐高吻合岗位（含匹配分）',
      '新增能力：可直接让智能体出模拟面试题练习（可指定数量/技术方向/难度）',
      '新增能力：作答后可让智能体基于你的回答生成深挖追问，逐层深化"出题→作答→追问"练习闭环',
      '优化：对话页快捷建议更新——一键触发联网搜岗/模拟出题/简历匹配等新能力'
    ]
  },
  {
    version: '1.31.0',
    date: '2026-09-11',
    title: '版本 1.31.0 · 智能体联网搜岗',
    items: [
      '新增功能：Career Copilot 智能体支持联网实时搜索招聘岗位——联网抓取智联/BOSS直聘/拉勾/前程无忧等平台的全国岗位（不局限于地区），返回真实岗位信息',
      '智能体稳定性：联网抓取失败时自动降级到本地岗位库，保证对话正常回复',
      '智能体体验：对话页新增「停止生成」按钮 + 冷启动自动唤醒重试，回复更稳定',
      '性能修复：智能体模型调用纳入全局 AI 并发闸门，缓解免费模型限流导致的偶发无回复',
      '用法示例：问智能体「帮我找全国的算法岗位」或「最新产品岗」即可触发联网搜索'
    ]
  },
  {
    version: '1.30.0',
    date: '2026-09-11',
    title: '版本 1.30.0 · 多模态附图',
    items: [
      '新增功能：「上传附图」——回答时可附带代码截图/白板草图/证书，AI 结合图片综合评估（面试猫图片识别优势落地）',
      '新增功能：作答聊聊可携带附图进行多模态评估，切换题目自动清除上一题附图',
      '升级基础：兜底模型升级 agnes-2.5-flash（视觉理解），AI 评估支持结合图片内容点评'
    ]
  },
  {
    version: '1.29.0',
    date: '2026-09-11',
    title: '版本 1.29.0 · 简历岗位匹配',
    items: [
      '新增功能：「简历匹配推荐」——粘贴简历核心内容即自动为岗位匹配打分（技能/学历命中），直通高吻合岗位',
      '优势落地：借鉴 offer毕「基于真实经历检索」能力，打通简历内容与招聘广场，实现双向匹配'
    ]
  },
  {
    version: '1.28.0',
    date: '2026-09-11',
    title: '版本 1.28.0 · 定制题库',
    items: [
      '新增功能：「从收藏发起面试」——把收藏夹题目一键组成新面试，直接答题与评分',
      '新增功能：「手动加题」——可自主添加自定义题目到题库，再发起模拟面试',
      '新增功能：简历+岗位定制出题坚持绑定真实经历（简历RAG），追问围绕项目细节展开'
    ]
  },
  {
    version: '1.27.0',
    date: '2026-09-10',
    title: '版本 1.27.0 · 招聘广场热招速递扩充',
    items: [
      '新增功能：新增「热招速递」数据源，收录 20+ 条真实在招岗位（含来源链接）',
      '新增功能：重点补齐「广州 + Java/后端 + 实习」等此前零覆盖的高频查询组合',
      '新增功能：扩充广深大厂后台/算法/AI 实习与 2027 届秋招，覆盖字节/腾讯/美团/阿里/百度/网易等',
      '新增功能：新增社招技术岗（Java/运维/SRE），覆盖金融、物流科技、网络安全等行业',
      '修复：修复「全部」Tab 被默认过滤为秋招的问题，现可查看全部岗位'
    ]
  },
  {
    version: '1.26.0',
    date: '2026-09-10',
    title: '版本 1.26.0 · 招聘广场全面扩容',
    items: [
      '新增功能：岗位规模扩容至 56+ 条精选数据，新增春招/社招/实习/定向专项四大频道',
      '新增功能：定向专项频道，收录选调生、国企专项、地方引才等计划',
      '新增功能：行业覆盖扩展至教育/医疗/快消/游戏/汽车/物流/咨询/传媒等 15+ 行业，社招岗位含经验分层',
      '新增功能：学历、经验筛选器，支持按自身条件快速定位岗位',
      '体验升级：支持配置任意第三方招聘数据聚合渠道，岗位数据持续扩充'
    ]
  },
  {
    version: '1.25.0',
    date: '2026-09-10',
    title: '版本 1.25.0 · 面试报告分享卡片 + 简历多版本对比',
    items: [
      '新增功能：面试复盘报告一键生成分享卡片，成绩海报可直接保存 PNG 分享',
      '新增功能：简历历史支持多版本对比，勾选两个版本查看综合分与各维度提升/回落',
      '体验升级：对比结果附一句话总结，直指可优化的维度'
    ]
  },
  {
    version: '1.24.0',
    date: '2026-09-10',
    title: '版本 1.24.0 · 招聘广场岗位收藏与截止提醒',
    items: [
      '新增功能：岗位收藏，点击岗位卡片 ♥ 即可收藏，新增「我的收藏」集中查看',
      '新增功能：截止提醒，收藏岗位截止前 7 天自动横幅提醒，不再错过秋招申请',
      '体验升级：收藏快照保存，岗位下架后收藏记录仍可回看'
    ]
  },
  {
    version: '1.23.2',
    date: '2026-09-10',
    title: '版本 1.23.2 · 等待体验优化',
    items: [
      '体验升级：面试题生成全程分步进度展示（连接→分析→生成→保存）+ 已用时长与进度条，告别 2-3 分钟黑盒等待',
      '体验升级：简历分析等待页改为骨架屏，加载布局一目了然',
      '新增功能：简历分析完成后可一键「带着简历去模拟面试」，自动携带简历摘要与目标岗位'
    ]
  },
  {
    version: '1.23.0',
    date: '2026-09-10',
    title: '版本 1.23.0 · 新增「Career Copilot」求职智能体',
    items: [
      '新增功能：AI 智能体对话，自然语言直达平台能力——找岗位、查知识、析薄弱、排日程，无需页面跳转',
      '新增功能：智能体可自主调用 5 大工具（岗位检索/知识库问答/面试统计/错题回顾/面试日程），自动组合完成复杂任务',
      '新增功能：多轮会话记忆，对话历史自动保存，支持会话切换与删除，上下文追问更连贯',
      '新增功能：个性化画像预注入，智能体基于你的真实练习数据（薄弱分类/平均分）给出针对性建议',
      '体验升级：SSE 流式输出 + 思考状态提示，响应过程实时可见'
    ]
  },
  {
    version: '1.22.0',
    date: '2026-09-10',
    title: '版本 1.22.0 · AI 主模型升级 B.AI + 新增「招聘广场」智能体模块',
    items: [
      '新增功能：招聘广场，聚合秋招精选与主流招聘平台岗位信息，支持按行业、职位类型、工作地点、数据来源多条件筛选与关键词搜索',
      '新增功能：秋招信息专场，展示企业名称、岗位要求、申请截止日期与倒计时提醒，一键直达官方申请入口',
      '新增功能：岗位数据定时自动更新（每 6 小时）+ 手动刷新，过期岗位自动下架，确保信息实时有效',
      '新增功能：AI 智能分类，自动为岗位标注行业、职位类型与标签'
    ]
  },
  {
    version: '1.21.0',
    date: '2026-08-31',
    title: '版本 1.21.0 · 跨场实战升级（难度自适应 + 语音作答 + 趋势维度 + 复盘导出）',
    items: [
      '新增功能：语音作答，面试中可用语音直接回答（支持 Chrome/Edge），自动转写为文字并计算语速、停顿次数，实时给出表达改进建议',
      '新增功能：成长趋势支持按 周 / 月 维度查看得分走势（日均各题平均分、周/月平均分），并智能提示距离目标分数的差距与达成路径',
      '新增功能：复盘报告支持一键导出为 PDF，保存到本地或打印分享，答题记录随报告完整留存',
      '新增功能：跨场题目自动生成，支持自定义难度偏好（简单/中等/困难）并按历史成绩自适应调节难度分布，聚焦薄弱环节'
    ]
  },
  {
    version: '1.20.0',
    date: '2026-08-31',
    title: '版本 1.20.0 · 新增「学习中心」四大模块与体验升级',
    items: [
      '新增功能：面试日历，规划每场面试与准备节点，支持新增/编辑/删除、标记完成、待面试/已完成/已取消状态管理',
      '新增功能：错题本，集中回顾低于阈值的低分题目，按分档筛选薄弱题型',
      '新增功能：收藏夹，一键收藏重点题目与高频考点，快照保存随时回看',
      '新增功能：成长趋势，用 SVG 折线图呈现每次面试得分走势，并统计维度掌握度',
      '新增功能：学习中心入口页，一体化汇总勋章数据，快捷进入各学习模块',
      '体验升级：支持浅色 / 深色（暗色）模式切换，自动跟随系统偏好'
    ]
  },
  {
    version: '1.19.0',
    date: '2026-08-30',
    title: '版本 1.19.0 · 模拟面试新增「复盘报告」',
    items: [
      '新增功能：模拟面试结束后自动生成「面试复盘报告」，汇总本轮综合得分与「完整性 / 准确性 / 表达力」三维度表现',
      '新增功能：报告按题目逐题回顾得分，一眼看清每道题的表现',
      '新增功能：提炼出现次数最多的待改进点，并标注高频项，方便针对性补强',
      '新增功能：报告支持一键查看历史面试记录，或直接开始下一次练习'
    ]
  },
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
