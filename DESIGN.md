# DESIGN.md — AI 面试助手

> 给 **AI 编码智能体**读的设计契约（概念来源：Google Stitch 的 DESIGN.md；
> 本文件由 `VoltAgent/awesome-design-md` 的做法引入）。
>
> - `AGENTS.md` 回答「怎么建这个项目」；**本文件回答「这个项目该长什么样」**。
> - 人读的完整设计系统在 [`docs/design-system-v4.md`](docs/design-system-v4.md)；
>   本文件是它的**可执行摘要 + 硬约束**，冲突时以本文件的约束为准（约束更严）。
> - 令牌的唯一来源是 `frontend/src/styles/variables.css`。**不要在组件里写死颜色**。

---

## 0. Design Read（一句话定位）

**面向求职者的产品型工具**（表单 / 列表 / 仪表盘 / 对话），不是营销落地页。
气质：**沉着、可信、蓄势**。方向名「**沉着冲刺 Calm Momentum**」。
→ 取编辑式构图的**秩序感**，不取落地页的**夸张戏剧性**。

---

## 1. 色彩（单一强调 + 一个点缀）

| 角色 | 令牌 | 规则 |
|---|---|---|
| 主色 | `--brand-primary`（墨绿） | 主按钮、链接、强调文字、图标 |
| 点缀 | `--c-accent`（琥珀金） | **只在关键结果 / 正向反馈 / 主 CTA 亮点**，面积 **≤10%** |
| 中性 | `--c-bg` / `--c-surface` / `--c-border` / `--c-text*` | 暖灰（stone）一系，**不要混入冷灰** |
| 语义 | `--c-{success,warning,danger,info}[-light/-border]` | 状态一律走令牌 |

**禁止**
- ❌ 写死 hex / rgb（`designGuards.test.ts` 会失败）
- ❌ **引用未定义的令牌**，尤其是 `var(--token, #浅色)` 这种带写死 fallback 的写法。
  ⚠️ **这是本项目真实踩过的坑**（v1.63.1）：`--input-bg` / `--text-secondary` 全站从未定义，
  而用法写成 `var(--未定义, #f5f5f5)` —— 暗色下底色仍近白、文字却是 `--c-text`（暗色下=近白）
  → **文字直接看不见**。原有 hex 门禁拦不住，因为它会把整个 `var(...)` 连同 fallback 一起剥掉。
  **规则：引用任何令牌前先确认它在 `styles/variables.css` 里有定义；禁止用颜色 fallback 兜底。**
  现在 `designGuards.test.ts` 有一条专门的「变量定义完整性」守卫在扫这件事。
- ❌ 蓝 / 绿 / 琥珀 / 紫四色并置；紫色渐变「AI 味」
- ❌ 纯黑 `#000` 背景；渐变文字；neon 外发光
- ❌ 大面积琥珀金铺底
- ❌ **emoji 当功能图标**：emoji 配色不受主题控制，且与全站内联 SVG 图标体系割裂。
  一律用内联 SVG（`stroke="currentColor"` 或 `var(--brand-primary)`、`stroke-width:2`）

**允许写死的例外**（每条都要有理由）：深色品牌底上的白色 CTA、登录页深色插画区的玻璃拟态、
暗色 `::selection` 前景色、简历导出用的独立 HTML 模板字符串。

---

## 2. 排版（三级字族，靠字重而非字体族建立层级）

| 角色 | 令牌 | 用于 |
|---|---|---|
| 展示级 | `--font-display`（衬线） | **仅 ≥24px 的页面级标题**：`h1`、Hero、登录页大标题 |
| 界面级标题 | `--font-title`（无衬线） | 卡片 / 列表 / 弹窗标题（`h2`、`h3`、各类 `.xx-title`） |
| 正文 | `--font-sans` | 正文、表单、标签 |
| 数字 / 得分 | `--font-mono` + `tabular-nums` | 分数、统计、表格数据 |

**为什么**：`--font-display` 的 CJK 首选（思源宋体 / Songti SC）**只有 Apple 设备有**，
Windows 回退宋体、Android 回退默认衬线 —— 小字号下笔画发虚、观感陈旧。
（v1.39.0 真机审计结论，勿回退。）

**规则**
- 字号阶梯：`xs12 / sm13 / base15 / md16 / lg18 / xl20 / 2xl24 / 3xl30 / 4xl36 / 5xl44`；Hero 用 `clamp(36,6vw,56)`
- 标题 `700~800` + 负字距；正文行高 `1.65`
- 标题加 `text-wrap: balance`，段落加 `text-wrap: pretty`（防孤字）
- **数字不要用衬线**，一律 mono
- ❌ 不要在小字号（<24px）上用 `--font-serif`（门禁会失败）

---

## 3. 布局与留白

- 容器有 `max-width`，不贴边
- 断点：**≥1024 桌面 / 768–1023 平板 / ≤767 移动**（3 档布局）；触控目标 **≥44px**
- **内容驱动子断点（须登记，否则算漂移）**：`1180`（导航收紧间距、隐藏品牌副标题）、`960`（导航收进汉堡）、`640`（紧凑收敛）、`480`（极窄微调）
  - 门禁：`designGuards.test.ts`「断点白名单」——`@media` 的 `max-width` 只取 `{480, 640, 768, 960, 1023, 1180}`，`min-width` 只取 `{1024}`；新增断点必须先登记
- 用 **CSS Grid**，不要用 flex 百分比算列
- 全屏区块用 `min-height: 100dvh`（**不要** `100vh`，iOS Safari 会跳）
- 区块留白**下略大于上**；避免所有 section 等距堆叠的均质感
- 区块头默认**左对齐**（与 Hero 形成一条阅读轴线）

**禁止的版式（AI 生成指纹，逐条都是真实踩过的）**
- ❌ **三等分卡片行**（`repeat(3,1fr)` 三张等大卡）→ 改用不对称网格 / zig-zag / 主卡跨行
- ❌ **装饰性圆形数字徽章**（01/02/03 彩色圆点）→ 改用发丝线 + 等宽小字序号
  （⚠️ 序号若承载**真实顺序语义**可保留，要换的是「装饰性圆形」这个形态）
- ❌ 卡片用装饰元素填空白 → 要么补**真实内容**，要么把标签锚到底部（`margin-top:auto`）
- ❌ 一切居中对称、`height:100vh`、无 max-width、统一圆角、卡片高度不齐

---

## 4. 动效（**触发点必须是「进入视口」，不是「加载完成」**）

| 指令 / 类 | 用途 |
|---|---|
| `v-reveal` / `v-reveal="120"` | 进入视口时淡入上移；数字参数 = stagger 毫秒 |
| `v-count-up` / `v-count-up="1400"` | 数字从 0 滚到目标值（保留 `+` 等后缀） |
| `.reveal` / `.is-revealed` | 上面两个指令依赖的类（定义在 `variables.css`） |
| `.ambient-glow` | 极轻的径向环境光，给平面背景制造纵深 |

**硬约束**
- ✅ **必须尊重 `prefers-reduced-motion`**：命中时直接呈现终态（`variables.css` 已有全局兜底）
- ✅ **降级必须让内容可见**：无 `IntersectionObserver` 时立即显示，**绝不让内容永久停在 `opacity:0`**
- ✅ 只动 `transform` / `opacity`（GPU 加速），**不要动 `top/left/width/height`**
- ✅ 缓动用 `cubic-bezier(0.22,1,0.36,1)`（先快后慢、收尾稳），不要线性
- ⚠️ **瞬时位移必须兜底**：锚点跳转 / `scrollTo` / End 键会让元素从「视口下方」一步跳到「视口上方」，
  此时 intersection ratio 恒为 0，**观察器根本不会回调** —— 只靠 IO 会让这些元素永久空白。
  实现见 `frontend/src/utils/reveal.ts` 的滚动兜底。
- ⚠️ 隐藏态**必须由 JS 加类才生效**；不要写进模板（脚本失败时内容就永久不可见了）

---

## 5. 交互态（每个可点元素都要有）

- hover：背景/描边变化或轻微位移（**主按钮下沉加深，不上浮**）
- **active：`scale(0.98)`** —— 移动端没有 hover，「点了没反应」的体感就来自这里
- `:focus-visible`：可见焦点环（可访问性要求，非可选）
- 过渡 `200–300ms`；`html { scroll-behavior: smooth }`
- 加载态用**骨架屏**而非通用转圈；**空态**要有引导内容；错误态用**常驻可关闭**的行内提示，不要 toast 一闪而过

---

## 5.5 图标 / 空态 / 容器 / 标题字号 / 键盘可达（v1.63.1 补的契约盲区）

这几项此前**没有规范**，导致各页面各写各的、逐渐漂移。现在定死：

**图标**
- 一律**内联 SVG**，禁止 emoji 当功能图标（emoji 配色不随主题、与 SVG 体系割裂）。
- 统一 `stroke-width: 2` + `stroke-linecap/linejoin: round`；颜色用 `currentColor` 或 `var(--brand-primary)`。
- 装饰性图标加 `aria-hidden="true"`。

**空态**
- **一句话空态**（如「还没有投递记录」+ 一个 CTA）：**可以居中**，这是通用惯例。
- **内容块型空态**（标题 + 说明 + 建议网格，如智能体欢迎区）：**左对齐** ——
  居中标题配左对齐网格会互相打架。
- 空态图标统一用全局类 **`.empty-icon-wrap`**（44×44 圆角方块 + `--brand-primary-50` 底 +
  `--brand-primary-100` 描边 + 品牌色图标，与首页 `.feature-icon-wrap` 同一形态）；
  **不要自建 `*-empty-icon` / `*-empty__icon` 类** —— 已加门禁（`designGuards.test.ts`，v1.66.13）。
- **已登记例外**：**错误态**（如加载失败、请求失败）空态图标用 `.empty-icon-wrap.is-warn`
  （`--c-danger-light` 底 / `--c-danger-border` 描边 / `--c-danger` 图标）；
  **中性空态（无数据）一律品牌色**，不得用 warning / success 等非品牌色。

**容器宽度与页面标题字号**
- **两档容器宽度，不要各页随手取值**（v1.63.2 收敛，此前 900 / 980 / 1080 / 1180 四种并存）：
  - **1080px** —— 列表 / 看板 / 日历 / 对话等「宽」页面（`jobs-page` / `agent-page` / `calendar-page`）
  - **900px** —— 阅读 / 编辑列（`ResumeView`）。**这是刻意的例外**：
    正文行长需控制在 65~75 字符内，窄列反而更易读，不是漂移。
- 页面级标题**统一 `font-size: 28px`**（此前 26 与 28px 混用）。

**切换控件的三种 idiom（v1.64.1 定死，别再造第四种）**

| 语义 | 形态 | 类名 | 用在哪 |
|---|---|---|---|
| **模式切换**（互斥、无计数） | 分段控件：`inline-flex` 底槽 + `--c-bg-alt` 底 + 4px padding，选中项白底 | `.tab-switch` | `ResumeView`、`JobAnalysisView`、`KnowledgeView` |
| **带计数的筛选** | 独立胶囊按钮：`flex` + `gap`，选中项品牌色填充 | `.filter-chips` | `JobsView` 招聘类型行 |
| **登录 / 注册** | 下划线 tab：`flex` + `border-bottom`，选中项下划线 | `.tab-switch`（登录页内） | `LoginView` |

⚠️ **为什么前两种不合并**：它们**语义不同**，长得一样反而错。
`ResumeView` 的模式切换与 `JobsView` 的筛选行此前**共用 `.tab-switch` 这一个类名却各写一套样式**，
看代码像「同一个组件被写歪了」—— 实际是命名掩盖了语义差异。v1.64.1 已按语义改名。

**可点区域的键盘可达性**
- 任何 `@click` 的非 `<button>` 元素都必须补
  `role="button"` + `tabindex="0"` + `@keydown.enter` + `@keydown.space.prevent` + `:focus-visible` 焦点环。
- **这是可访问性要求，不是可选。**

## 6. 文案

- ❌ 假精确数字（`100%`、`99.99%`）；❌ AI 套话（「赋能」「一站式解决方案」「开启新篇章」…）
- ❌ 「Oops!」式卖萌错误文案；✅ 直接说清发生了什么、下一步做什么
- ✅ **不确定就说不知道**：上游没给的字段留空 / 显示 `—`，**绝不推算**（`EMPTY` 见 `utils/format.ts`）
- ⚠️ `formatEmpty(0)` 返回 `'0'` —— **数字 0 是有效值，不是空值**
- ✅ 数据不足时**判空不判 0**（如 `grade=null`、样本为 0 时平均值为 `null` 而非 `0`）

---

## 7. 地图（v1.62.0 起的合规约束）

- 底图白名单：**腾讯 / 高德 / 百度 / 天地图**。严禁 Google / Apple / Bing 海外版 / Mapbox / OSM 直连海外瓦片。
- 只做**官方深链跳转**，不内嵌 JS SDK（零 Key 零依赖）；详见 skill `cn-map-deeplink`。
- **不做第三方站点登录态**（技术上不可实现，且撞「不自动登录第三方平台」红线）。

---

## 8. 改完必须做的验证

1. `npx vue-tsc --noEmit` → 0 error
2. `npx vitest run` → 全绿（含 `designGuards.test.ts` 与 `changelog.test.ts` 两个门禁）
3. `npx vite build` → 成功
4. **真实浏览器走查**：桌面 1440 与移动 **390** 各一次，断言
   `document.documentElement.scrollWidth <= window.innerWidth`（横向溢出）
5. 版本号**三处同步**：`changelog.ts` 的 `CURRENT_VERSION` + 首条条目 / `application.yml` / `pom.xml`

> **代码审计查不出布局遮挡与「看起来改完了其实没改完」——必须看真实浏览器截图。**
