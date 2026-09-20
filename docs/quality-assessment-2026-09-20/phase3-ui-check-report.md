# 阶段三 · 页面/界面检查报告 — AI 智能面试辅助平台

> **检查日期**：2026-09-20　**检查环境**：本机真机 Edge 153（CDP 直连，非模拟器）
> **被检站点**：`https://interview-guide-ai-interview-platform.pages.dev`（说明见 §1）
> **截图目录**：`screenshots/`（49 张）　**清单**：`capture-manifest.json`
> **覆盖性质**：**优先级子集**（非计划中的完整矩阵），限制见 §5

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

## 2. 【P1】移动端首屏空白 —— 主视觉与 CTA 在加载后长时间不可见

| 项 | 内容 |
|---|---|
| **等级** | **P1（影响移动端首屏可用性）** |
| **复现** | 视口 375×667，打开首页，**不做任何交互** |
| **实测（CDP 逐秒采样）** | `t=2s → opacity 0` … `t=24s → opacity 0`，全程 **14 个含文字元素**（主标题、副标题、CTA「开始简历分析/模拟面试」、准备度卡片、三张 feature 卡）保持 `opacity: 0` |
| **触发条件** | 执行一次 `window.scrollTo(0,600)` 后，同批元素 `opacity → 1`（滚动触发揭示） |
| **根因（计算样式取证）** | ① 容器 `.home` 上**残留 Vue 过渡类 `page-enter-from page-enter-active`** → `.page-enter-from{opacity:0}` 未清除；② 13 个 `fade-in-up` 元素计算样式为 `animation: fadeInUp 0.4s both`，`both` 填充使未开始/未完成时停留在 `from{opacity:0; transform:translateY(12px)}` |
| **叠加放大** | 375×667 下 `<h1>` 位于 **y=582**（视口高 667），主标题几乎在首屏最底部；配合上面的不可见状态 → **首屏几乎全白**（见 `home_375x667_light.png`） |
| **桌面端对比** | 1920×1080 首屏可见（元素天然进入可视区，揭示完成）→ 属**移动端特有**问题 |
| **截图证据** | `screenshots/home_375x667_light.png`、`screenshots/home_375x667_light_12s.png`（12 秒后仍同状态） |
| **建议** | ① 修正路由过渡类未清除问题（确保 `page-enter-active` 动画结束后移除 `page-enter-from`）；② 揭示动画给**兜底**：初始 `opacity` 不宜为 0 直到滚动才变（可用 `IntersectionObserver` + `rootMargin` 提前触发，或首屏元素不做入场动画）；③ 移动端压缩 hero 上边距，把主标题提到首屏上部；④ 为 `prefers-reduced-motion` 提供直接可见的回退 |
| **验证建议** | 真机手动确认一次（本次由 CDP 驱动导航，虽已逐秒采样，仍建议人工复核） |

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

### 已完成覆盖（48 张有效截图）

| 维度 | 覆盖 |
|---|---|
| 视图 × 明色 × 4 分辨率（1920×1080 / 1366×768 / 768×1024 / 375×667） | **8 个**：home、login、resume、jobs、knowledge、interview、agent、profile |
| 视图 × 暗色 × 4 分辨率 | **4 个**：home、jobs、knowledge、interview |

### 客观实测指标（全部 48 张，取自 `capture-manifest.json` 的 `measured` 字段）

| 指标 | 结果 |
|---|---|
| 横向溢出（`scrollWidth > clientWidth`） | **0 / 48 出现**（全部分辨率均无横向滚动条） |
| 主题生效（`data-theme` 与预期一致） | **48 / 48 正确**（暗色抽样 16 张均为 `dark`） |
| 弹窗遮挡（已预设「已读版本」） | **0 / 48 有遮挡** |
| 路由落点正确 | **48 / 48 与目标路由一致** |
| 控制台/路由异常导致的重定向 | 仅 `/admin`（见 §3，属预期拦截）；`/login` 已登录时回首页也为预期行为 |
| 空态设计（新账号天然空数据） | 具备且文案友好：错题本「太棒了，没有错题」、知识库引导文案、个人中心「0 简历数量 / 1 面试会话」等 |

### 未覆盖（不得视为通过）

- **其余 11 个视图**（resume-history、job-analysis、history、learning、calendar、wrong-book、favorites、progress、admin、404、变更日志弹窗独立态）的矩阵截图
- **Firefox / Safari**：本机仅 Edge（Chromium 内核），已如实标注环境限制
- **真机触屏手势**、横屏、超宽屏、系统缩放非 100% 场景
- **首屏性能指标**（LCP/CLS 等）

### 工具链限制（导致本次为子集的原因，供后续复用参考）

1. `agent-browser` 这个版本**没有可用的 viewport 命令**（help 里列出但执行报 `Unknown command: viewport`）→ 先期 30 张实际都是同一 1032×758 视口，**已全部废弃不计入**，改用原生 CDP（`Emulation.setDeviceMetricsOverride`）重跑
2. `agent-browser screenshot` 的路径**必须是 Windows 形式**（`D:/x.png`），且**不支持中文路径** → 先落 ASCII 目录再拷入仓库
3. 本机 **429 频率限制**导致 UI 检查 worker 两次中断（第二次跑了 108 分钟零产出），额度 2026-09-21 00:07 重置 → 完整矩阵需在额度恢复后由 worker 补齐
4. `bash script.sh` 会触发沙箱对 `wsl.exe` 的黑名单拦截 → 脚本需内联执行

---

**报告版本**：v1（优先级子集）　**编制**：2026-09-20
