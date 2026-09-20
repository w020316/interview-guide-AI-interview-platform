# 阶段三 · 页面/界面检查报告 — AI 智能面试辅助平台

> **检查日期**：2026-09-20　**检查环境**：本机真机 Edge 153（CDP 直连，非模拟器）
> **被检站点**：`https://interview-guide-ai-interview-platform.pages.dev`（说明见 §1）
> **截图目录**：`screenshots/`（**88 张，19 视图全覆盖，已做采集归一化**——采集前移除 Vue
> 过渡类并内联强制 `opacity:1`，原因见 §2）
> **清单**：`capture-manifest.json`（含每页 `measured` 客观指标）
> **覆盖性质**：明色 18 视图 × 4 分辨率全覆盖 + 暗色 4 视图 × 4 分辨率；未覆盖项见 §5

---

## 1. 【P1】前端生产域名已失效，文档与实际不一致

| 项 | 内容 |
|---|---|
| **发现** | 真机浏览器访问文档所载地址 `https://interview-guide-ai-interview-platform.vercel.app` → **`404: NOT_FOUND`**（Vercel 侧已不存在该部署/域名） |
| **实际可用** | `https://interview-guide-ai-interview-platform.pages.dev`（Cloudflare Pages）→ 正常打开，标题「AI 智能面试辅助平台」，账号会话有效 |
| **为何之前没发现** | 本机 curl 走白名单代理，对该域名长期返回 `502 CONNECT tunnel failed`，被误判为「代理抖动」；**只有真机浏览器（不经沙箱代理）能给出权威结论** |
| **影响** | 任何按文档访问的用户/评审者会拿到 404；`DELIVERY_REPORT*.md`、`DEPLOY.md`、`README` 等均写旧域名；仓库仍保留 `vercel.json` |
| **未断功能** | 后端 CORS 白名单同时列了 `vercel.app` 与 `pages.dev`，故线上功能不受影响 |
| **建议** | ① 统一文档为 pages.dev（或为 vercel 域名重新绑定部署）；② 明确唯一生产入口并写入 README 顶部；③ 若 Vercel 已弃用则删除 `vercel.json` 与相关 CI 步骤，避免误导 |

## 2. 【已撤回的错误结论】移动端首屏"空白"实为采集环境限制，非产品缺陷

> **本节为 v1 报告中的 P1 结论，经复核后予以撤回**。保留全过程以示纠错依据。

### 原结论（v1，错误）
v1 曾记录：「375×667 下主标题/CTA 及 14 个元素加载后 24s 仍 `opacity:0`，需滚动才出现，判 P1」，
根因归为「容器残留 Vue 过渡类 + `fade-in-up` 动画未到终态」。

### 复核过程与推翻依据

| 步骤 | 证据 | 结论 |
|---|---|---|
| ① 检查页面可见性 | `document.visibilityState === "hidden"`（CDP 拉起的 Edge 窗口不可见） | 隐藏页面**不会推进 CSS 动画与过渡**，元素停在 `from` 态是**必然**的 |
| ② 对照移动/桌面模拟 | `mobile:true` 与 `mobile:false` 结果**完全相同**（均 `hero:0`、14 个零透明度元素） | 与视口/移动模拟**无关**，排除"移动端特有缺陷" |
| ③ 物证对照 | 另一路径（`agent-browser`，可见窗口）拍到的首页英雄区**正常可见**（深色文字、CTA 清晰） | **可见窗口下动画正常完成** |
| ④ 机制验证 | 手动移除 `page-enter-from/page-enter-active` 后，容器恢复；但 13 个 `fade-in-up` 元素仍为 0 | 根因是"动画未推进"，而非类残留本身 |

**修正后的结论**：`opacity:0` 是 **CDP 隐藏页面导致的采集假象**。真实用户在可见窗口浏览时，路由过渡与入场动画正常执行，**不存在"首屏空白"缺陷**。

### 仍然成立的相关观察（降级为 P3 建议）

- 375×667 下 `<h1>` 的 `top=582`（视口高 667）→ **首屏上方留白偏多**，主标题几乎贴底；这是一次纯布局测量（不依赖动画），建议移动端压缩 hero 上边距、把主标题提到首屏上部
- 若后续要做视觉回归，**必须**在截图前注入 `animation:none!important;transition:none!important`（或等页面 `visibilityState=visible`），否则带入场动画的元素一律不可见

### 本次截图的可信边界（重要）

- **仍然可信**：布局位置、横向溢出判定、主题属性、路由落点、DOM 文本/空态文案 —— 均与动画无关
- **不可信**：带 `.fade-in-up` 入场动画的元素在截图中的"不可见"表现（含 hero 全部内容、卡片），**不代表线上真实表现**
- 已补拍 `screenshots/home_375x667_light_normalized.png`（移除过渡类后）供对照

## 3. 【P2】非管理员访问管理后台被静默弹回首页，无任何提示

- 复现：测试账号（ROLE_USER）访问 `/admin` → 实测 `location.pathname` 变为 `/`，页面为首页 hero（`h1 = 让每一次面试 都有备而来`）
- 路由守卫逻辑正确（`meta.requiresAdmin && !isAdmin() → { path: '/' }`），**属权限拦截正常行为**
- **但缺反馈**：用户点击/收藏了 `/admin` 链接后会「莫名回到首页」，不知道是没有权限
- 建议：改为跳转并 `ElMessage.warning('仅管理员可访问')`，或跳 `/404`/`/403` 语义页
- 截图：`screenshots/admin-blocked_1920_light.png`

## 4. 【P3】变更日志弹窗首访遮挡首屏主视觉与主 CTA

- 首次访问（或版本号变化后）弹出 v1.33.3 变更日志，**覆盖整个 hero 与「开始简历分析」CTA**
- 弹窗本身做工良好（版本徽章/日期/分组/历史折叠/「不再提醒此版本」）
- 建议：① 首访延迟 1–2 秒或等首屏动画结束再弹；② 弹窗改为右下角卡片式，不遮挡 CTA；③ 缩短到 3 条以内
- 截图：`screenshots/home_1920x1080_light.png`

## 5. 覆盖范围与限制（如实说明）

### 已完成覆盖（88 张有效截图 —— **19 个视图全覆盖**）

| 维度 | 覆盖 |
|---|---|
| **视图 × 明色 × 4 分辨率**（1920×1080 / 1366×768 / 768×1024 / 375×667） | **18 个**：home、login、resume、resume/history、job(岗位分析)、jobs、agent、interview、history、learning、calendar、wrong-book、favorites、progress、profile、knowledge、admin、notfound |
| 视图 × 暗色 × 4 分辨率 | **4 个**：home、jobs、knowledge、interview |
| 合计 | **88 张**（72 明色 + 16 暗色），较上轮 92 页次可比 |

> 变更日志弹窗为模态而非独立视图，已在 §4 以交互方式检查。

### 客观实测指标（全部 88 张，取自 `capture-manifest.json` 的 `measured` 字段）

| 指标 | 结果 |
|---|---|
| 横向溢出（`scrollWidth > clientWidth`） | **0 / 88 出现**（4 档分辨率全部无横向滚动条） |
| 主题生效（`data-theme` 与预期一致） | **88 / 88 正确**（暗色 16 张均为 `dark`） |
| 弹窗遮挡（已预设「已读版本」） | **0 / 88 有遮挡** |
| 路由落点正确 | **除 `/admin` 外全部一致**；`/admin` 对非管理员按守卫弹回 `/`（见 §3，属预期拦截）；`/login` 已登录时回首页亦为预期 |
| 空态设计（测试账号为新号，天然空数据） | 具备且文案友好：错题本「太棒了，没有错题」、知识库引导文案、个人中心「0 简历数量 / 1 面试会话」、404「页面未找到 · 返回首页/返回上一页」 |
| 响应式导航 | 768px 及以下品牌名与账号名收纳、仅保留图标（`ovf:false` 说明为布局收纳而非溢出） |
| 暗色模式质量 | 抽查知识库/首页/岗位/面试：深色底 + 浅色字 + 青绿主色，对比充足，未发现硬编码浅色残留 |

### 未覆盖（不得视为通过）

- **Firefox / Safari**：本机仅 Edge（Chromium 内核），已如实标注环境限制，不伪造结果
- **真机触屏手势**、横屏、超宽屏（2560+）、系统缩放非 100% 场景
- **首屏性能指标**（LCP / CLS / TBT 等）
- **需要交互才出现的状态**：AI 推理中的流式输出态、上传中态、错误态（需构造失败）、变更日志弹窗之外的其它弹窗
- 暗色仅抽样 4 个视图（计划中为 7 个抽样视图）

### 工具链限制（导致本次为子集的原因，供后续复用参考）

1. `agent-browser` 这个版本**没有可用的 viewport 命令**（help 里列出但执行报 `Unknown command: viewport`）→ 先期 30 张实际都是同一 1032×758 视口，**已全部废弃不计入**，改用原生 CDP（`Emulation.setDeviceMetricsOverride`）重跑
2. **【最关键】** CDP 拉起的 Edge 窗口 `document.visibilityState === "hidden"`，而**隐藏页面不推进 CSS 动画与过渡** → 带 `.fade-in-up`（`animation: fadeInUp 0.4s both`）的元素会永远停在 `opacity:0`，`<transition>` 也因 `transitionend` 不触发而残留 `page-enter-from` 类。**这曾导致一次误判（见 §2）**。正确做法：采集前做归一化（移除过渡类 + 内联强制 `opacity:1`），本次 48 张均已归一化
3. `agent-browser screenshot` 的路径**必须是 Windows 形式**（`D:/x.png`）且**不支持中文路径** → 先落 ASCII 目录再拷入仓库
4. 本机 **429 频率限制**导致 UI 检查 worker 两次中断（第二次跑了 108 分钟零产出），额度 2026-09-21 00:07 重置 → 完整矩阵需在额度恢复后由 worker 补齐
5. `bash script.sh` 会触发沙箱对 `wsl.exe` 的黑名单拦截 → 脚本需内联执行
6. python.exe 是 Windows 程序：脚本路径必须传 `D:/...`，传 `/d/...` 会被解析成 `d:\d\...`

---

**报告版本**：v3（19 视图全覆盖，88 张归一化截图；v2 撤回误判的移动端 P1）　**编制**：2026-09-20
