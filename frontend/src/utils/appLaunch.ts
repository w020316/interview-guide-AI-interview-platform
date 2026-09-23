/**
 * 移动端 App scheme 唤起（v1.39.0）
 *
 * ── 为什么单独抽出来 ────────────────────────────────────────────────
 * 原实现内联在 ResumeView.vue 里，判定只有一条信号：
 *
 *   setTimeout(() => {
 *     if (document.visibilityState === 'visible') 判定为「未安装 App」
 *   }, 1600)
 *
 * 这条判定在真机上大面积误报，用户明明装了微信/钉钉，却被提示「未检测到 App」，
 * 且因为兜底链接只在「失败」时才出现，用户直接卡死。三个独立原因：
 *
 * 1. **单信号不可靠**。App 被拉起时页面不一定变 hidden：
 *    - iOS Safari 会先弹「在"XX"中打开？」系统确认框，用户还没点，页面仍是 visible；
 *    - Android 部分 ROM/浏览器唤起后 WebView 不更新 visibilityState；
 *    - 页面被系统挂起时 `visibilitychange` 可能根本不触发，但 `pagehide`/`blur` 会。
 *    → 因此改为监听 `visibilitychange` + `pagehide` + `blur` 三个信号，任一命中即算唤起成功。
 *
 * 2. **1.6s 太早**。iOS 的确认框、冷启动的 App 都需要用户交互/更长时间，
 *    判定窗口必须覆盖「弹框 → 用户点确认」这段。
 *
 * 3. **应用内浏览器（微信 / 钉钉 / QQ 等 WebView）里 scheme 常被宿主拦截**，
 *    此时页面永远 visible，按原逻辑必然误报「未安装」。这类环境应当直接给网页版。
 *
 * ── 措辞原则 ──────────────────────────────────────────────────────
 * 检测不出「有没有装」，只能检测「有没有成功交棒」。所以文案不能断言用户没装，
 * 只能说「已尝试打开，若没跳转请用兜底通道」。这是能力边界，不是话术修饰。
 */

/** 移动端 UA 判定（与既有 detectMobile 行为保持一致，仅改为可传入 UA 以便测试） */
export function detectMobile(ua?: string): boolean {
  const s = ua ?? (typeof navigator === 'undefined' ? '' : navigator.userAgent)
  if (!s) return false
  return /Android|iPhone|iPad|iPod|HarmonyOS|Mobile|Windows Phone/i.test(s)
}

/**
 * 应用内浏览器（宿主 WebView）识别。
 *
 * 返回宿主标识，无法识别时返回空串。**只识别会拦截 scheme 的强宿主**：
 * 微信、钉钉、支付宝、微博。QQ 单独用 `QQ/` 前缀匹配，
 * 避免把「QQ 浏览器」（MQQBrowser，是正常浏览器、scheme 可用）误判成 QQ 宿主。
 */
export function detectInAppBrowser(ua?: string): string {
  const s = ua ?? (typeof navigator === 'undefined' ? '' : navigator.userAgent)
  if (!s) return ''
  if (/MicroMessenger/i.test(s)) return 'wechat'
  if (/DingTalk/i.test(s)) return 'dingtalk'
  if (/AlipayClient/i.test(s)) return 'alipay'
  if (/Weibo/i.test(s)) return 'weibo'
  // QQ 宿主：UA 形如 "QQ/8.9.1.xxxx"；QQ 浏览器是 MQQBrowser，不匹配
  if (/(^|[\s;])QQ\/[\d.]+/i.test(s)) return 'qq'
  return ''
}

/**
 * 唤起结果判定窗口（毫秒）。
 *
 * 为什么是 2500：iOS 的「在 XX 中打开？」确认框 + 用户点击通常 1~2s，
 * 冷启动 App 更久。原值 1600 会在用户还没点确认时就判失败。
 */
export const HANDOFF_WINDOW_MS = 2500

/** 交棒监听器：只要出现任一「页面被切走」信号，即认为 scheme 已被系统接收 */
export interface HandoffWatcher {
  /** 是否已交棒（App 被拉起） */
  readonly handedOff: boolean
  /** 主动结束监听（幂等） */
  dispose(): void
}

/** 最小化的 DOM 依赖，便于在 node 环境（vitest）里注入假实现 */
export interface HandoffEnv {
  doc?: Pick<Document, 'addEventListener' | 'removeEventListener' | 'hidden' | 'visibilityState'> | null
  win?: Pick<Window, 'addEventListener' | 'removeEventListener'> | null
}

/**
 * 创建交棒监听器。
 *
 * 监听三个信号，任一命中即标记为已交棒：
 * - `visibilitychange` 且 `document.hidden === true`（App 顶到前台，页面入后台）
 * - `pagehide`（页面被挂起/卸载）
 * - `window.blur`（焦点被 App 抢走；部分浏览器只发这个）
 *
 * 注意：**不监听 focus/visibility 恢复**——用户从 App 切回来是正常流程，
 * 不能把「切回来」当成「没唤起」。
 */
export function createHandoffWatcher(env: HandoffEnv = {}): HandoffWatcher {
  const doc = env.doc ?? (typeof document === 'undefined' ? null : document)
  const win = env.win ?? (typeof window === 'undefined' ? null : window)

  let handedOff = false
  let disposed = false

  const markHandedOff = () => {
    handedOff = true
  }
  const onVisibilityChange = () => {
    if (doc && doc.hidden) markHandedOff()
  }

  if (doc?.addEventListener) {
    doc.addEventListener('visibilitychange', onVisibilityChange)
    doc.addEventListener('pagehide', markHandedOff)
  }
  if (win?.addEventListener) {
    win.addEventListener('blur', markHandedOff)
    win.addEventListener('pagehide', markHandedOff)
  }

  return {
    get handedOff() {
      return handedOff
    },
    dispose() {
      if (disposed) return
      disposed = true
      if (doc?.removeEventListener) {
        doc.removeEventListener('visibilitychange', onVisibilityChange)
        doc.removeEventListener('pagehide', markHandedOff)
      }
      if (win?.removeEventListener) {
        win.removeEventListener('blur', markHandedOff)
        win.removeEventListener('pagehide', markHandedOff)
      }
    },
  }
}

/**
 * 唤起未成功时的提示文案。
 *
 * 刻意**不写「未检测到 XX App」**：浏览器没有能力检测 App 是否安装，
 * 只能知道「这次没交棒成功」。断言用户没装，在真机上大面积不成立，
 * 且会让用户以为功能坏了而放弃。
 */
export function launchFallbackHint(name: string): string {
  return `已尝试打开「${name}」。若没有自动跳转，可点下方「打开网页版」，或改用「选择本机文件」`
}

/** 应用内浏览器里的提示：宿主会拦截 scheme，直接引导走网页版 */
export function inAppBrowserHint(name: string, host: string): string {
  const hostName = host === 'wechat' ? '微信' : host === 'qq' ? 'QQ' : host === 'dingtalk' ? '钉钉' : host
  return `当前在「${hostName}」内置浏览器中，系统会拦截直接唤起「${name}」。已为你打开网页版入口`
}
