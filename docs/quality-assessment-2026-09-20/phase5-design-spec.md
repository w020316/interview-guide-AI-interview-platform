# 阶段五 · 设计与体验优化规范 — AI 智能面试辅助平台

> **编制日期**：2026-09-20　**依据**：仓库现有设计令牌（`src/styles/variables.css`，design-system v4）
> + 阶段三 88 张真机截图实测 + 阶段四 UX 评估
> **交付形式**：样式规范文档 + 落地检查清单（不要求 Figma 源文件）
> **原则**：本规范**不新增设计语言**，只把已有令牌固化为可执行规则，并给出必须整改的反模式

---

## 1. 设计令牌总览（全部为仓库既有事实值）

### 1.1 色彩系统 —— 品牌主色（深墨绿）

| 令牌 | 亮色值 | 暗色值 | 用途 |
|---|---|---|---|
| `--brand-primary` | `#0f766e` (teal-700) | `#14b8a6` | 主按钮、选中态、链接、强调图标 |
| `--brand-primary-hover` | `#115e59` | `#2dd4bf` | hover |
| `--brand-primary-active` | `#134e4a` | `#0d9488` | 按下/激活 |
| `--brand-primary-light` | `#f0fdfa` | `#12322e` | 浅色底（tag/提示块） |
| `--brand-primary-50/100/200` | `#f0fdfa` / `#ccfbf1` / `#99f6e4` | `#0e2e2b` / `#16423d` / `#1e554f` | 分层浅底 |
| `--brand-gradient` | `linear-gradient(135deg,#0f766e,#134e4a)` | 暗色另有定义 | Hero、主 CTA |
| `--brand-glow` | `0 4px 14px rgba(15,118,110,.15)` | 暗色另调 | 品牌色发光 |

### 1.2 强调色 —— 赭石橙（点缀，非主色）

| 令牌 | 亮色 | 用途 |
|---|---|---|
| `--brand-accent` | `#c2410c` | 强调点缀（徽章、关键数字） |
| `--c-accent` | `#b45309` | 文字强调（`--text-accent`） |
| `--c-accent-soft` / `--c-accent-line` | `#fff7ed` / `#fde68a` | 强调底色 / 分隔线 |

> **规则**：强调色占比 ≤ 10%，仅用于"斩获感"数字与徽章。**禁止**用强调色做整块背景或长文本色。

### 1.3 明暗模式

- 切换机制：`<html data-theme="light|dark">` + Element Plus `.dark` class（`src/theme.ts`）
- 持久化：`localStorage['interview-theme']`；无值时跟随系统 `prefers-color-scheme`
- 暗色变量覆盖集中在 `variables.css` 的 `[data-theme='dark']` 块
- **实测结论（阶段三 88 张）**：主题生效 88/88，暗色对比充足，**未发现硬编码浅色残留**

### 1.4 字体与字号

| 令牌 | 值 | 用途 |
|---|---|---|
| `--font-serif` | Source Han Serif SC / Noto Serif SC / Songti SC | 品牌标题、Hero |
| `--font-sans` | -apple-system, Segoe UI, PingFang SC… | 正文默认（`body`） |
| `--font-mono` | JetBrains Mono, ui-monospace | 代码/API 路径 |
| 字号阶梯 | `xs 12 / sm 13 / base 15 / md 16 / lg 18 / xl 20 / 2xl 24 / 3xl 30 / 4xl 36 / 5xl 44` | — |

**规则**：正文一律 `--font-size-base(15px)`；页面标题 `2xl~3xl`；Hero `4xl/5xl` 且用 serif。
**禁止**在组件内写裸 `px` 字号。

### 1.5 间距 / 圆角 / 阴影

| 类别 | 令牌 | 值 |
|---|---|---|
| 间距 | `xs/sm/md/lg/xl/2xl` | 4 / 8 / 16 / 24 / 40 / 64 px |
| 圆角 | `xs/sm/md/lg/xl/2xl/full` | 3 / 5 / 8 / 12 / 16 / 24 / 9999 px |
| 阴影 | `xs→2xl` | 6 级递进；`--shadow-brand` 为品牌色阴影 |

**规则**：卡片圆角统一 `--radius-lg(12px)`；按钮 `--radius-md(8px)`；徽章 `--radius-full`。
**禁止**出现令牌之外的魔数（如 `border-radius: 10px`）。

---

## 2. 组件规范（仓库既有组件）

### 2.1 `BaseButton`（`src/components/BaseButton.vue`）

| 属性 | 取值 | 适用 |
|---|---|---|
| `variant` | `primary` / `gradient` / `ghost` / `success` / `cta` | 页面主操作用 `primary`；Hero 主 CTA 用 `gradient`；次级动作用 `ghost` |
| `size` | `sm` / `md` / `lg` | 列表行内 `sm`；表单 `md`；页面主 CTA `lg` |
| `disabled` | — | 必须继承主题的 `opacity(.5)` 与 `cursor:not-allowed` |

### 2.2 `BaseCard`（`src/components/BaseCard.vue`）

| variant | 用途 |
|---|---|
| `default` | 普通内容容器 |
| `outlined` | 强调边界（表格/表单区） |
| `elevated` | 需要浮起的卡片（`is-flat` 可压平） |
| `feature` | 首页特性卡（带 `::before` 装饰条 + hover 抬升） |

> `is-hoverable` 仅用于**可点击**的卡片；信息型卡片不得加 hover 抬升，避免误示可点。

### 2.3 其他

`BaseInput` / `BaseTextarea`（`disabled` 统一 `opacity .5`）、
`BaseTag`（`opacity .6` 为弱化态）、`FavoriteToggle`、`ChangelogDialog`。

**统一原则**：所有交互组件的 `disabled`、`:focus-visible`、hover 反馈必须走令牌，禁止各处自定义。

---

## 3. 必须整改的反模式（来自本轮实测）

| # | 反模式 | 实测依据 | 整改要求 | 优先级 |
|---|---|---|---|---|
| 1 | **文档与生产入口不一致** | 文档写 `vercel.app`（真机 404），实际 `pages.dev` | README 顶部声明唯一生产入口；废弃域名与 `vercel.json` 清理 | **P1** |
| 2 | **权限拦截无反馈** | 非管理员访问 `/admin` 在 4 档分辨率一致静默弹回 `/` | 守卫加 `ElMessage.warning('仅管理员可访问')` 或跳 403 语义页 | P2 |
| 3 | **首访弹窗遮挡主 CTA** | 变更日志弹窗覆盖 hero 与「开始简历分析」 | 延迟 1–2s 弹出，或改右下角卡片；条目 ≤ 3 条 | P2 |
| 4 | **移动端首屏上方留白偏多** | 375×667 下 `<h1>` top=582/667 | 压缩 hero 上边距，主标题上提到首屏上部 | P3 |
| 5 | **长内容页中部大片留白** | 知识库页 1920×1080 中部空白明显（见 `knowledge_1920x1080_dark.png`） | 内容区设 `max-width` 并纵向居中分布，或补空态引导卡 | P3 |
| 6 | **入场动画依赖可见性** | `fadeInUp(both)` 在不可见页面不推进（采集侧已归一化规避） | 为视觉稿/自动化截图提供 `animation:none` 归一化；真实用户侧无需改 | P3 |

> 反模式 1/2/3 同时是阶段四 UX 报告的改进项，**以本节为准执行**。

---

## 4. 落地检查清单（可执行，纳入 PR 自检）

- [ ] 颜色/间距/圆角/阴影**全部**取自令牌，无裸 hex 与魔数
- [ ] 明暗两种主题下均自测（至少覆盖改动页面）
- [ ] 4 档分辨率（1920/1366/768/375）无横向滚动条（`scrollWidth <= clientWidth`）
- [ ] 768px 及以下导航正常收纳（品牌名/账号名可隐，导航可达）
- [ ] 交互元素具备 `:focus-visible`、`disabled` 态；可点击卡片方可加 hover 抬升
- [ ] 空数据 / 加载中 / 错误 三态齐备，文案面向用户（不暴露技术细节）
- [ ] 视觉回归截图前注入 `animation:none!important;transition:none!important`（否则动画元素不可见，产生假缺陷）

---

## 5. 与上轮对比与遗留

| 项 | 上轮 | 本轮 |
|---|---|---|
| 设计规范文档 | 有（v4 设计系统落地） | 本规范对齐 v4 令牌并补「反模式 + 检查清单」 |
| 组件化程度 | BaseButton/BaseCard/BaseInput/BaseTag/BaseTextarea 已抽 | 保持；本轮未新增组件（按总规则不做反向剥离） |
| 遗留 | — | 反模式 2/3/4/5 待改（P2×2、P3×2）；反模式 1 属文档（P1） |

---

**报告版本**：v1　**编制**：2026-09-20
