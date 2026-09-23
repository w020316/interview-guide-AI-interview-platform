# 移动端「已安装 App 却提示无软件」的根因与修复（v1.39.0）

> 现象（用户真机截图）：简历分析 → 「从其他平台导入」→ 点「微信」，
> 手机里明明装了微信，却弹出「未检测到「微信」App，可点下方「打开网页版」…」，
> 用户以为功能坏了。

## 一、根因

原实现（`ResumeView.vue` 的 `openExtractSource`）用**一条信号 + 1.6 秒定时**判定唤起成败：

```ts
window.location.href = s.scheme
window.setTimeout(() => {
  const stillVisible = document.visibilityState === 'visible'
  if (stillVisible && Date.now() - startedAt < 3200) {
    ElMessage.info(`未检测到「${s.name}」App，...`)   // ← 误报
  }
}, 1600)
```

这条判定在真机上有 **三个独立缺陷**，任一都足以造成误报：

### 1. 单信号不可靠 —— `visibilityState` 不一定会变

App 被拉起时，页面**不一定**进入 hidden：

| 场景 | 实际行为 |
|---|---|
| **iOS Safari** | 先弹「在"微信"中打开？」**系统确认框**。用户还没点，页面仍是 `visible` |
| **Android 部分 ROM / 浏览器** | 唤起后 WebView 不更新 `visibilityState`，但会派发 `blur` |
| **页面被系统挂起** | `visibilitychange` 可能根本不触发，而 `pagehide` 会触发 |

### 2. 1.6 秒太早 —— 窗口没覆盖「弹框 → 用户点确认」

iOS 的确认框本身就要用户交互，冷启动的 App 更久。1.6s 时用户手指还在屏幕上。

### 3. 应用内浏览器里 scheme 会被宿主拦截

在**微信 / 钉钉 / 支付宝 / 微博 / QQ** 的内置 WebView 里跳 `weixin://`、`dingtalk://`
这类 scheme 会被宿主直接吞掉，页面永远 `visible` → **必然误报**。
（在微信里点「微信」尤其无意义，用户本来就在微信里。）

### 4. 措辞与兜底设计放大了伤害

- 文案「**未检测到** XX App」是对「用户没装」的断言，而浏览器**没有能力**做这个检测，
  只能知道「这次没交棒成功」。
- 兜底链接 `v-if="failedKey === s.key"` —— **只在判定失败后才出现**。
  于是「判定误报」直接等价于「用户没有出路」，卡死。

## 二、修复

### 2.1 抽出可测试模块 `src/utils/appLaunch.ts`

| 导出 | 作用 |
|---|---|
| `detectMobile(ua?)` | 移动端 UA 判定（改为可注入 UA，便于测试） |
| `detectInAppBrowser(ua?)` | 识别会拦截 scheme 的宿主；**不把「QQ 浏览器」(MQQBrowser) 误判成 QQ 宿主** |
| `createHandoffWatcher(env?)` | 监听 **`visibilitychange` + `pagehide` + `blur`** 三个信号，任一命中即判定已交棒 |
| `HANDOFF_WINDOW_MS = 2500` | 判定窗口，覆盖 iOS 系统确认框 |
| `launchFallbackHint(name)` | 中性文案，**不断言「未检测到」** |
| `inAppBrowserHint(name, host)` | 应用内浏览器专用说明 |

**关键点：只监听「切走」信号，不监听「切回」。**
用户从 App 切回来是正常流程，把「切回」当成「没唤起」是另一个反向误报。

### 2.2 分三条路径处理（`ResumeView.vue`）

```
桌面端 / 无 scheme        → 直接新标签打开网页版
应用内浏览器（微信等）      → 说明原因 + 直接打开网页版（不再走必然误报的跳转判定）
普通移动端浏览器           → 跳 scheme → 三信号监听 → 全程无切走信号才提示兜底
```

### 2.3 网页版入口改为**常驻**

模板里的兜底链接去掉了 `v-if`：

- 默认态：安静的次级文本链接「打开网页版 →」；
- 唤起未交棒成功的那张卡片：升级为实色徽标「未自动跳转？点此打开网页版 →」并高亮。

这样**任何一次误判都只是「多了一个入口」，不会变成死路**。
这是本次修复里最容易被忽略、但对用户最重要的一条：
检测能力有边界，产品必须保证边界之外仍有出路。

### 2.4 顺带的交互补强

- 新增 `launchingKey`：唤起中图标轻微呼吸，避免用户以为点击没生效而反复点；
- `.ex-main:active { transform: scale(0.985) }`：补上按下反馈（原先完全没有）。

## 三、测试

`src/utils/appLaunch.test.ts`，**17 条**，逐条对应上面每个真机场景：

- iOS / Android / HarmonyOS UA 识别；桌面 UA 不误判；空 UA 安全返回；
- 微信 / 钉钉 / 支付宝 / 微博 / QQ 宿主识别；
- **「QQ 浏览器」不被误判为 QQ 宿主**（误判会让用户被强行送去网页版）；
- `visibilitychange(hidden)` / 仅 `blur` / `pagehide` 三条路径都能判定已交棒；
- 页面始终可见且无信号时**不**判定已交棒（此时才提示兜底）；
- `dispose()` 幂等且真正解绑；无 DOM 环境（SSR/单测）不抛错；
- 文案断言：**不含「未检测到」「未安装」**，且必须给出「打开网页版」这条出路；
- 文案不含 em-dash（设计系统禁令）。

## 四、遗留与边界

- 浏览器**无法**检测 App 是否安装，这是能力边界而非实现缺陷。
  因此产品层面的正确做法是「保证兜底通道永远可达」，本修复即按此设计。
- 各 App 的 scheme 有效性由 App 方维护（如 `bosszp://` 裸 scheme 在部分版本可能无效）。
  若后续发现某个 scheme 长期无效，应在其 `EXTRACT_SOURCES` 条目上直接标注并弱化，
  而不是靠用户点了才发现。
