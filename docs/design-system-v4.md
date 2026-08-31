# AI 面试助手 · 设计系统 v4

> 方向「沉着冲刺 Calm Momentum」：以求职者的**准备感与拿下 offer 的信心**为情感核心。
> 反"AI 生成化"的关键：**不对称编辑式构图 + 衬线大字标题 + 墨绿×琥珀金双主色 + 分数卡数据态 mono 数字 + 克制的动效编排**。

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

### 2.1 字族
| 角色 | 字族 |
|---|---|
| 展示/标题 | `Source Han Serif SC`（衬线，权威、编辑气质） |
| 正文/界面 | 系统无衬线 `PingFang SC / Microsoft YaHei` |
| **数字/得分** | `JetBrains Mono`（等宽，制造"评分面板"数据质感） |

### 2.2 字号阶梯（6 到 5xl）
xs12 / sm13 / base15 / md16 / lg18 / xl20 / 2xl24 / 3xl30 / 4xl36 / 5xl44。Hero 标题用 `clamp(36,6vw,56)`。

### 2.3 字重与行高
- 标题 `font-weight:700-800`，`letter-spacing:-0.01em~-1.5px`。
- 正文 `web 1.65`，`font-weight:400-500`。
- **对比原则**：数字/分数一律 mono + 大号 + 琥珀金，与衬线标题形成强对比（记忆点）。

---

## 3. 组件

### 3.1 按钮 BaseButton
- 变体：primary / gradient / ghost / success / **cta（白底反色）**。
- 尺寸 sm/md/lg；支持 loading / disabled / block / hoverable。
- v4：主按钮 hover **下沉加深**而非上浮；仅收藏型动作用轻微上浮，避免系统性浮动观感。

### 3.2 卡片 BaseCard
- 变体 default / outlined / elevated / **feature（顶部品牌条 hover 延展）**。
- v4：默认细描边 `--c-border`；hover 温和上浮 + 阴影升级；header 衬线标题 + 浅底。

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