# AI 面试助手 · 设计系统 v4

> 方向「沉着冲刺 Calm Momentum」：以求职者的**准备感与拿下 offer 的信心**为情感核心。
> 反"AI 生成化"的关键：**不对称编辑式构图 + 展示级衬线大字标题（≥24px 才用，见 2.1）+ 墨绿×琥珀金双主色 + 分数卡数据态 mono 数字 + 克制的动效编排**。

---

## 1. 色彩系统

### 1.1 主色——沉着墨绿（品牌情绪：专业、可靠、蓄势）
| Token | 值 | 用途 |
|---|---|---|
| `--brand-primary` | `#0f766e` | 主按钮、链接、强调文字、图标主色 |
| `--brand-primary-hover` | `#115e59` | 主按钮 hover |
| `--brand-primary-active` | `#134e4a` | 主按钮 active / pressed |
| `--brand-primary-50` | `#f0fdfa` | 浅底（选中态、标签底） |
| `--brand-primary-100` | `#ccfbf1` | 浅描边、hover 边框 |
| `--brand-primary-200` | `#99f6e4` | 中浅描边 |

### 1.2 点缀——琥珀金（品牌情绪：成就、荣誉、好消息）
只在**关键结果/正向反馈/主 CTA 亮点**使用，维持 ≤10% 面积，突出"斩获感"。
| Token | 值 | 用途 |
|---|---|---|
| `--c-accent` | `#b45309` | 强调短语、得分、正向徽记 |
| `--c-accent-hover` | `#92400e` | 强调 hover |
| `--c-accent-soft` | `#fff7ed` | 强调浅底 |
| `--c-accent-line` | `#fde68a` | 强调浅描边 |

### 1.3 中性色（暖调）
沿用 stone 暖灰：`--c-bg:#fafaf9` / `--c-surface:#fff` / `--c-border:#e7e5e4` / 文字四级（`#1c1917→#a8a29e`）。

### 1.4 组合规则
- **墨绿为主、琥珀金为辅**：琥珀金禁止大面积铺底。
- **浅底 + 强色字**：是 v3 既定纪律，v4 沿用，避免渐变光晕。
- 阴影采用暖黑色 `rgba(28,25,23,…)`，保持温度。

---

## 2. 排版

### 2.1 字族（v1.39.0 分级修订）

**⚠️ v1.39.0 变更**：原策略是「标题一律衬线」。真机审计发现该策略在非 Apple 设备上会退化：
`--font-serif` 的 CJK 首选 `Source Han Serif SC` / `Songti SC` **只在 Apple 设备存在**，
Windows 回退 `SimSun`（宋体）、多数 Android 回退默认衬线。全站有 **24 处 14~18px 的
卡片/列表/弹窗标题**在用衬线，小字号宋体笔画发虚、观感陈旧。

现改为**三级字族**，层级由「字重 + 负字距 + 颜色」建立，而不是靠字体族切换：

| 角色 | 令牌 | 值 | 使用范围 |
|---|---|---|---|
| **展示级** | `--font-display` | `Source Han Serif SC / Songti SC / serif`（= `--font-serif` 别名） | **仅 ≥24px 的页面级标题**：`h1`、登录页大标题、Hero 大字 |
| **界面级标题** | `--font-title` | 系统无衬线（与 `--font-sans` 同栈） | 卡片 / 列表 / 弹窗 / 面板标题（`h2`、`h3` 及各类 `.xx-title`） |
| **正文** | `--font-sans` | `PingFang SC / Microsoft YaHei / …` | 正文、表单、标签 |
| **数字/得分** | `--font-mono` | `JetBrains Mono`（等宽 + `tabular-nums`） | 分数、统计、表格数据 |

- **数字一律 mono，不要用衬线**（v1.39.0 前 `ApplicationView.stat-num`、`CareerView.advice-score` 误用衬线）。
- 全局规则：`h1 → --font-display`，`h2, h3 → --font-title`（weight 700 + `letter-spacing:-0.012em`）。
- `--font-serif` 保留为 `--font-display` 的别名，仅为兼容历史写法；**新代码请直接用 `--font-display`**。
- **防回退门禁**：`frontend/src/designGuards.test.ts` 会在「`var(--font-serif)` 且同规则
  `font-size < 24px`」时失败。要写小号衬线请先想清楚为什么。

### 2.2 字号阶梯（6 到 5xl）
xs12 / sm13 / base15 / md16 / lg18 / xl20 / 2xl24 / 3xl30 / 4xl36 / 5xl44。Hero 标题用 `clamp(36,6vw,56)`。

### 2.3 字重与行高
- 标题 `font-weight:700-800`，`letter-spacing:-0.01em~-1.5px`。
- 正文 `web 1.65`，`font-weight:400-500`。
- **对比原则**：数字/分数一律 mono + 大号 + 琥珀金，与标题形成强对比（记忆点）。
- v1.39.0 增补：标题加 `text-wrap: balance`（防孤字），段落加 `text-wrap: pretty`。

---

## 3. 组件

### 3.1 按钮 BaseButton
- 变体：primary / gradient / ghost / success / **cta（白底反色）**。
- 尺寸 sm/md/lg；支持 loading / disabled / block / hoverable。
- v4：主按钮 hover **下沉加深**而非上浮；仅收藏型动作用轻微上浮，避免系统性浮动观感。

### 3.2 卡片 BaseCard
- 变体 default / outlined / elevated / **feature（顶部品牌条 hover 延展）**。
- v4：默认细描边 `--c-border`；hover 温和上浮 + 阴影升级；header 用 `--font-title`（无衬线标题字族，见 2.1）+ 浅底。

### 3.3 配色纪律（v1.39.0 增补）

- **禁止写死颜色 hex**：一律用语义令牌（`--c-danger` / `--c-danger-light` / `--c-success` /
  `--c-info` / `--c-warning` / `--brand-primary-*` / `--c-text*`）。
  写死的值不随 `[data-theme='dark']` 切换，暗色下会变成「浅底深字贴在深色页面上」。
  v1.39.0 在 7 个文件里清出 11 处这类问题。
- **单一强调色**：墨绿为主、琥珀金点缀（≤10% 面积）。不要出现蓝/绿/琥珀/紫四色并置。
- **允许写死的例外**（都要写明理由）：深色品牌底上的白色 CTA、登录页深色插画区的
  玻璃拟态叠层、暗色 `::selection` 前景色、简历报告导出用的独立 HTML 模板字符串。
- **防回退门禁**：`frontend/src/designGuards.test.ts` 覆盖以上两条（写死浅底/深字、小号衬线）。

### 3.3 标签 BaseTag
- 六语义变体 + sm/md/lg；浅底强色字；可 close。

### 3.4 徽记/评分条（新）
- 得分数字：`mono + text(2xl-3xl) + var(--c-accent)`。
- 进度/评分：极浅墨绿底 `--brand-primary-50` + 实色填充 + 圆角 3px。

---

## 4. 响应式规则
- 断点：**≥1024 桌面 / 768-1023 平板 / ≤767 移动**。
- 栅格：特色/步骤用 `repeat(3,1fr)` → 平板 `repeat(2,1fr)` → 移动 `1fr`。
- 移动端 `nav-menu` 横向滚动 + 仅图标；Hero 按钮纵排全宽。
- 触控目标 ≥44px；`prefers-reduced-motion` 关闭动效。

---

## 5. 动效编排
- 页面切换：淡入上下位移 8px（已实现）。
- Hero 入口：badge→标题→副标题→按钮→数据条**分层 fadeInUp，staggered delay**。
- 数据条数字轻微上浮入场，但克制、不循环闪烁。

---

## 6. 落地说明
- Tokens 全部收敛在 `frontend/src/styles/variables.css`（v4 增量）。
- 配色/字体/间距/圆角/阴影 token 与本文档 **1:1 对齐**，可直接映射到 Figma 变量。
- 本仓库未内嵌 Figma 源文件；设计稿关系可依据 v4 tokens + HomeView 实现复刻为 Figma 组件库。