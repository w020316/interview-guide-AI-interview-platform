# 阶段五：设计与体验优化 — 样式规范文档（v1.33 修订）

> 在既有 `docs/design-system-v4.md` 基础上修订，聚焦本轮修复引入/固化的规则。
> 适用范围：interview-guide 前端（Vue 3 + Element Plus + CSS 自定义属性）。
> 设计原则参考来源见文末（均为公开资料，2025-09 检索核实）。

## 一、色彩系统

### 1.1 三层 Token 架构（本轮确立的强制规则）

```
原始值（primitives）        语义变量（semantic）              组件样式
#0f766e 等  ──────────→  --brand-primary / --c-surface  ──────────→  组件只允许引用语义变量
                          [data-theme='dark'] 整组切换
```

**规则 R1（组件禁止硬编码色值）**：任何组件/视图的 `background/color/border` 只允许引用 `--brand-*`、`--c-*` 语义变量。本轮清除了最后一批硬编码（navbar 白底、`--input-bg`/`--tag-text` 未定义变量引用、tag-source 绿色），后续新增样式一律走语义层。
**规则 R2（新变量必须成对定义）**：新增语义变量必须在 `variables.css` 的 light 块与 `[data-theme='dark']` 块同时定义（如本轮 `--c-navbar`）。

### 1.2 品牌色与语义色标准

| 用途 | 变量 | 亮色值 | 暗色值 |
|---|---|---|---|
| 主色（按钮/高亮/激活） | `--brand-primary` | `#0f766e` 深墨绿 | `#14b8a6` 提亮 teal |
| 主色浅底（tag/chip 底色） | `--brand-primary-50` | `#f0fdfa` | `#0e2e2b` |
| 页面底/卡片面/边框 | `--c-bg` / `--c-surface` / `--c-border` | 暖米白系 | 暖深棕系（禁纯黑） |
| 导航栏背景（本轮新增） | `--c-navbar` | `rgba(255,255,255,.95)` | `rgba(23,20,18,.95)` |
| 成功/警告/危险 | `--c-success/warning/danger` + `-light` | 语义色 | 暗色整组切换 |
| 文字四级 | `--c-text` → `--c-text-quaternary` | stone-900→400 | 亮色反转 |

**规则 R3（状态语义映射）**：成功=success、进行中=info、失败=error、中性=default；状态文案必须中文（U3：ONGOING→进行中、FAILED→生成失败）。

### 1.3 暗色模式规则（本轮核心修订）

- **R4 导航栏与页面同主题**：header 是暗色割裂的重灾区（I1），导航背景、边框、文字必须全部走语义变量。
- **R5 表面色阶替代纯白**：暗色下输入框/chip/卡片一律用 `--c-surface` / `--c-surface-elevated`，禁止 `#fafafa/#fff` fallback（I2 白底白字的根因）。
- **R6 对比度红线**：正文文字与其背景对比度 ≥ 4.5:1（WCAG 1.4.3）；暗色底避免纯白文字，优先 `#f5f5f4` 级软白。新配色组合提测前须在明暗两态各截图一次。

## 二、排版规范

| 层级 | 字体/字号/字重 | 用途 |
|---|---|---|
| H1 | var(--font-serif, 宋体系) 40-48px / 700 | 首页主标题 |
| H2 | 28px / 600 | 区块标题 |
| H3 | 20px / 600 | 卡片标题 |
| 正文 | var(--font-sans) 14-15px / 400 | 主体内容 |
| 辅助 | 12-13px / 400-500，`--c-text-secondary` | 说明文字、导航（移动端标签 10px） |

行高：正文 1.6，标题 1.3。中文界面禁止出现英文状态词（R7，源自 U3）。

## 三、组件规范（本轮修订项）

| 组件 | 规则 |
|---|---|
| **按钮** | 主按钮 `--brand-primary` 底白字；描边按钮 `--c-border` + `--c-text`；danger 态用 `--c-danger`。loading 态由组件库统一 |
| **标签 tag** | 底 `--brand-primary-50`、字 `--brand-primary`、圆角 full；"来源"类标签用 success 语义对（I3） |
| **推荐/快捷入口 chip** | 底 `--c-surface-elevated`、字 `--c-text`、边 `--c-border`，hover 走品牌色（I2） |
| **状态徽章** | success/info/error/default 四态，全部语义变量（U3） |
| **错误反馈** | 后端业务故障统一 503 + 中文可重试文案（"AI 服务暂时不可用，请稍后重试"）；同一失败只允许一种文案呈现（U1/U4） |
| **空状态** | 图标 + 一句话说明 + 主行动 CTA（现有模式，固化为标准） |

## 四、响应式布局规则

| 断点 | 布局规则 |
|---|---|
| ≥1024px | 完整导航（图标+文字横排）、多列网格 |
| 768-1023px | 单列网格，导航完整 |
| ≤768px | 导航图标+10px 文字竖排（I5）；hero 装饰性浮动元素隐藏（I4，装饰不得遮挡内容）；栅格塌缩为单列 |
| ≤480px | 按钮/输入全宽，字号 12-13px |

**规则 R8（装饰不得遮挡内容）**：绝对定位的装饰元素在窄断点必须隐藏或改为文档流排列；新增绝对定位装饰时必须同时写 ≤768px 行为。

**规则 R9（登录态一致性）**：已登录访问 /login 一律重定向首页（路由守卫负责，视图层不再出现双状态）。

## 五、落地核对清单（新增/修改样式时逐项过）

1. 无硬编码色值（R1）
2. 新变量明暗成对（R2）
3. 状态文案中文（R3/R7）
4. 明暗两态截图各一张，文字对比度目测 ≥4.5:1（R6）
5. 375px 截图一张，确认无遮挡/无横向溢出（R8）
6. 错误提示走统一业务文案通道（U1）

## 六、设计参考来源（联网检索，2026-09-13 核实）

- [Zeroheight — Implementing dark mode with design tokens](https://zeroheight.com/learn/implementing-dark-mode-with-design-tokens/)：语义 token 层承载主题切换、朴素反色为何失败——与 R1/R2 直接对应
- [GitLab Pajamas Design System — Design tokens usage](https://design.gitlab.com/product-foundations/design-tokens-using)：语义 token 支持色彩模式的工业实践
- [Muz.li — Dark Mode Design Systems Guide](https://muz.li/blog/dark-mode-design-systems-a-complete-guide-to-patterns-tokens-and-hierarchy/)：暗色表面层级（surface elevation）——对应 R5
- [W3C — WCAG 1.4.3 Contrast (Minimum)](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html)：4.5:1 对比度红线的权威出处——对应 R6
- [DubBot — Dark Mode Best Practices for Accessibility](https://dubbot.com/dubblog/2023/dark-mode-a11y.html)：暗色用软深灰而非纯黑、维持 4.5:1——对应 1.2 节暗色取值
- [Penpot — The developer's guide to design tokens and CSS variables](https://penpot.app/blog/the-developers-guide-to-design-tokens-and-css-variables/)：明暗平行 token 集的落地写法
- [Figma Forum — 3-tiered token system](https://forum.figma.com/ask-the-community-7/approaches-to-custom-dark-mode-palette-with-a-3-tiered-token-system-36407)：Core→Semantic→Component 三层结构——对应 1.1 架构
