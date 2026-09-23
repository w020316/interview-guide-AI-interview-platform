# 前端审美重构说明（v1.39.0）

> 来源：抖音视频「2 个 skill 提升你的 AI 审美」（嘉瑜聊 AI）。
> 视频讲的两个 skill 是 **Taste-Skill**（`github.com/Leonxlnx/taste-skill`）里的
> `taste-skill`（安装名 `design-taste-frontend`）与 `redesign-skill`
> （安装名 `redesign-existing-projects`）。两者已装进本机技能目录：
> `~/.workbuddy-ai/skills/design-taste-frontend/` 与 `~/.workbuddy-ai/skills/redesign-existing-projects/`
> （纯 markdown，无可执行脚本）。

---

## 一、Design Read（先读场景，再动手）

按 taste-skill 第 0 节的要求，动手前先声明「这是什么页面、给谁看、什么气质」：

> **Reading this as:** 面向中文求职者的 **AI 面试准备工具型 Web App**（不是落地页、不是营销站），
> 受众是校招/社招求职者，气质应当 **克制、可信、有数据感**；
> 沿用既有「Calm Momentum」墨绿 × 琥珀金体系，**不换技术栈**
> （Vue 3 + Element Plus + 原生 CSS 变量，redesign-skill 明确要求不迁移框架）。

### 三个拨杆（taste-skill 第 1 节）

| 拨杆 | 取值 | 理由 |
|---|---|---|
| `DESIGN_VARIANCE` | **5** | 产品型工具，用户来办事不是来逛展；保留不对称 Hero，但列表/表单保持可预期的规整 |
| `MOTION_INTENSITY` | **3** | 动效必须「有理由」：只保留状态过渡与入场，不加滚动劫持/磁吸/无限循环 |
| `VISUAL_DENSITY` | **6** | 信息密度偏高（岗位列表、评分维度、历史记录），属于「仪表盘侧」而非「画廊侧」 |

> 注：taste-skill 自称「不适用于 dashboard / 数据表 / 多步产品 UI」。
> 因此本轮**只取它对排版、色彩、材质、交互态、AI 反模式的约束**，
> 不套用它的 landing page 版式范式（Sticky-Stack / Horizontal-Pan / 3 列特性卡等）。

---

## 二、审计发现的真问题（redesign-skill 的 Scan → Diagnose）

按 redesign-skill 的 7 项修复优先级，逐条扫描后确认以下问题**真实存在**（非风格偏好）：

| # | 问题 | 证据 | 影响面 |
|---|---|---|---|
| 1 | **小号衬线标题在非 Apple 设备退化为宋体** | `--font-serif` 的 CJK 首选 `Source Han Serif SC` / `Songti SC` 只在 Apple 存在；Windows 回退 SimSun、多数 Android 回退默认衬线。全站 **24 处 14~18px 的卡片/列表/弹窗标题**在用衬线 | 全站 |
| 2 | **暗色主题下写死浅色导致「反色破版」** | `JobsView` 的收藏按钮 `#fff1f2`、`.ddl-banner` 的 `#fef2f2/#991b1b/#dc2626`、`CareerView` 的 `.layer-tag.l1~l4` 与 `.s-tag`、`ResumeView` 的 `.parse-warning`、`HistoryView`/`InterviewView`/`ResumeHistoryView` 的 `#059669/#2563eb/#10b981` —— 这些值不随 `[data-theme='dark']` 切换 | 7 个文件 |
| 3 | **Hero 演示卡浮动标签遮挡卡片标题** | `.chip-a { top:12%; left:4% }` 相对 `.hero-visual`（整列约 460px）定位，而卡片只有 300px 居中；900~1250px 视口下标签直接压住「本轮准备度」。既有修复只隐藏了 <768px | 首页 |
| 4 | **登录页同一意图三处入口** | 表单行内「还没账号？立即注册」+ 卡片底部「还没有账号？立即注册」+ 顶栏「免费注册」 | 登录页 |
| 5 | **假精确数字** | 首页 Hero `100% 岗位匹配分析`、登录页 `100% 免费使用` | 首页 / 登录页 |
| 6 | **装饰性状态圆点** | 首页 Hero 徽标 `.badge-dot`（不表达任何真实状态） | 首页 |
| 7 | **四色并置违反单一强调色** | `CareerView` 四层标签用 蓝/绿/琥珀/紫 四色，紫色尤其典型 AI 味 | 生涯诊断页 |

---

## 三、重点改动区域与做法

### 3.1 排版层（全站，最高杠杆）

**目标风格**：**展示级衬线 + 界面级无衬线** 的两级字体策略。

- 新增三个字体角色（`styles/variables.css`）：
  - `--font-display` = 衬线，**只给 ≥24px 的页面级展示标题**（h1 / 登录页大标题）；
  - `--font-title` = 无衬线标题字族，靠 **字重 700 + 负字距** 建立层级，跨平台一致；
  - `--font-sans` = 正文。
- 全局规则：`h1` 走 `--font-display`，`h2/h3` 走 `--font-title`。
- 批量迁移 **36 处** 组件内联 `font-family`：小号标题 → `--font-title`，
  数字/得分 → `--font-mono`（设计系统本就规定「数字用等宽」，此前 `ApplicationView.stat-num`、
  `CareerView.advice-score` 误用了衬线）。
- 新增 `text-wrap: balance`（标题防孤字）与 `text-wrap: pretty`（段落）。

### 3.2 交互态层（全站）

审计发现大量可点元素**只有 hover、没有按下反馈**——移动端没有 hover，
「点了没反应」的体感主要来自这里。

- 全局 `button:not(:disabled):active` / `[role=button]:active` → `scale(0.98)`；
- `.btn-base:active` → `translateY(1px) scale(0.99)`；
- `html { scroll-behavior: smooth }`（此前锚点是瞬移）；
- 表格与 `.num-display` 统一 `font-variant-numeric: tabular-nums`（数字逐位跳动会让列错位）。

### 3.3 色彩层（修暗色破版）

7 个文件里的写死色统一改为语义令牌：`--c-danger` / `--c-success` / `--c-info` /
`--c-warning` / `--brand-primary-*`。`CareerView` 的四色标签改为**品牌色递进强度**
（l1→l4 由浅到深），既满足「单一强调色」，又天然适配暗色。

### 3.4 页面级修复

| 页面 | 改动 |
|---|---|
| **首页** | ① `.visual-stage` 包裹层让浮动标签相对**卡片**定位（结构性修复遮挡）；② 去掉装饰性 `.badge-dot`；③ `100% 岗位匹配分析` → `6 求职工具`（真实数字，与 `App.vue` 的 `toolNav` 一致） |
| **登录页** | ① 注册入口收敛为底部一处；② `100% 免费使用` → `免费 / 全部功能开放` |
| **招聘广场** | ① 分栏重排为「国内优先，海外独立」；② 来源 chips 按分栏过滤；③ 跨栏切换清空来源筛选；④ 修复暗色破版配色 |
| **简历分析** | 取件卡片的 App 唤起逻辑重写（详见 `docs/mobile-app-launch-fix.md`） |

### 3.5 刻意**没有**做的事

- **不换框架 / 不引入 Tailwind**：redesign-skill 明确要求「work with the existing tech stack」。
- **不引入 webfont**：CJK 字体动辄 5~20MB，与首屏性能预算冲突；
  改用「分级 + 回退可控」策略解决同一问题。
- **不做滚动劫持、磁吸、无限循环动效**：`MOTION_INTENSITY=3`，
  且 taste-skill 要求每个动效都能一句话说清「它传达了什么」。
- **不动 Hero 的衬线大字**：`h1` 保留衬线是既有品牌资产，且 52px 下宋体观感成立；
  本次只清理「小字号用衬线」这一真正的问题区。

---

## 四、防回退门禁

上面两类问题（写死色、小号衬线）**极易在后续迭代里被写回来**——新增一个状态标签、
复制一段旧样式就会复发。所以固化成测试门禁：`frontend/src/designGuards.test.ts`（5 条）。

| 守卫 | 规则 |
|---|---|
| 写死浅色背景 | `background: #fff*` / `#f**` / `rgba(255,255,255,*)` 一律失败（除非在允许清单里） |
| 写死深色文字 | `color: #0**` / `#1**` / `#2**` / `black` 一律失败 |
| 小号衬线标题 | `font-family: var(--font-serif)` 且同规则 `font-size < 24px` 失败 |
| 扫描范围自检 | 断言扫描到 >20 个文件且含 `App.vue`，避免路径写错导致守卫**静默失效** |
| 令牌存在性 | 断言 `--font-display` / `--font-title` 已定义 |

**允许清单**（每条都写明理由，没有理由的不许进）：白色 CTA 按钮、登录页深色插画区的
玻璃拟态叠层、暗色 `::selection` 前景色、简历报告导出用的独立 HTML 模板字符串。

> **门禁必须验证「会失败」**：注入 `.probe { background:#fef3c7; color:#1c1917 }` 与
> `font-family:var(--font-serif); font-size:15px` 后，3 条断言如期失败；
> 删除探针后 5 条全过。没验证过失败路径的守卫等于没有守卫。

## 五、验证

| 项 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 错误 |
| `npx vitest run` | **313 passed / 27 files**（含 appLaunch 17 条 + designGuards 5 条） |
| `npx vite build` | 通过（沙箱下需 `--outDir` 换目录，见备注） |
| 后端 `mvn test` | **883 tests，0 failures，BUILD SUCCESS** |
| `node scripts/check-window-consistency.mjs` | ✅ 时间窗一致性通过 |
| `node --test scripts/keepalive-worker.test.mjs` | ✅ 11/11 |
| 真实浏览器 | Edge + playwright-cli，本地 dev server 截图确认 Hero 遮挡已消除、登录页入口已收敛、控制台 0 error |
| 线上生效 | 推送后入口 chunk 已含「求职工具」、JobsView chunk 已含「全部国内」 |

> **备注（构建环境）**：本机沙箱会拦截 `vite build` 清空 `dist/` 的删除操作
> （报错栈落在 `node-safe-delete-shim`）。这是沙箱限制不是代码问题，
> 用 `npx vite build --outDir dist-verify --emptyOutDir=false` 可正常验证。

## 六、应用内更新日志

本项目约定「每次发布新增 `frontend/src/changelog.ts` 条目」，且有测试断言
`CHANGELOG[0].version === CURRENT_VERSION` 且最新版本必须有**用户可见**（`level: 'user'`）条目。
本轮已补 v1.39.0 条目（6 条 user + 5 条 tech），`CURRENT_VERSION` 同步升到 `1.39.0`。
