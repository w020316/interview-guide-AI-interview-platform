import { describe, it, expect } from 'vitest'
import {
  detectMobile,
  detectInAppBrowser,
  createHandoffWatcher,
  launchFallbackHint,
  inAppBrowserHint,
  isMobileBlockedWebEntry,
  desktopOnlyWebHint,
  copyResultHint,
  copyText,
  COPY_LINK_LABEL,
  HANDOFF_WINDOW_MS,
} from './appLaunch'

/**
 * 这些用例全部来自真机误报的复盘（v1.39.0）：
 * 原实现只看 `document.visibilityState`，在 iOS 系统确认框、
 * Android 部分 ROM、微信内置浏览器里都会误判「未安装 App」。
 */

describe('utils/appLaunch · detectMobile', () => {
  it('识别主流移动端 UA', () => {
    expect(detectMobile('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)')).toBe(true)
    expect(detectMobile('Mozilla/5.0 (Linux; Android 14; Pixel 8)')).toBe(true)
    expect(detectMobile('Mozilla/5.0 (Linux; Android 12; HarmonyOS; ABC)')).toBe(true)
  })

  it('桌面端 UA 不误判', () => {
    expect(detectMobile('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0')).toBe(false)
    expect(detectMobile('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe(false)
  })

  it('空 UA 安全返回 false', () => {
    expect(detectMobile('')).toBe(false)
  })
})

describe('utils/appLaunch · detectInAppBrowser', () => {
  it('识别会拦截 scheme 的强宿主', () => {
    expect(detectInAppBrowser('Mozilla/5.0 (iPhone) MicroMessenger/8.0.49')).toBe('wechat')
    expect(detectInAppBrowser('Mozilla/5.0 (Linux; Android 13) DingTalk/7.0.0')).toBe('dingtalk')
    expect(detectInAppBrowser('Mozilla/5.0 AlipayClient/10.3.0')).toBe('alipay')
    expect(detectInAppBrowser('Mozilla/5.0 Weibo/13.0')).toBe('weibo')
  })

  it('识别 QQ 宿主（形如 QQ/8.9.1）', () => {
    expect(detectInAppBrowser('Mozilla/5.0 (Linux; Android 13) QQ/8.9.1.1095')).toBe('qq')
  })

  it('不把「QQ 浏览器」误判为 QQ 宿主', () => {
    // MQQBrowser 是正常浏览器，scheme 可用，误判会导致用户被强行送去网页版
    expect(detectInAppBrowser('Mozilla/5.0 MQQBrowser/12.5 Mobile Safari/537.36')).toBe('')
  })

  it('普通浏览器返回空串', () => {
    expect(detectInAppBrowser('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Version/17.0 Safari/604.1')).toBe('')
    expect(detectInAppBrowser('')).toBe('')
  })
})

describe('utils/appLaunch · createHandoffWatcher', () => {
  /** 构造一个可手动派发事件的假 DOM 环境 */
  function fakeEnv(initialHidden = false) {
    const docListeners = new Map<string, Set<() => void>>()
    const winListeners = new Map<string, Set<() => void>>()
    const make = (store: Map<string, Set<() => void>>) => ({
      addEventListener: (t: string, h: () => void) => {
        if (!store.has(t)) store.set(t, new Set())
        store.get(t)!.add(h)
      },
      removeEventListener: (t: string, h: () => void) => {
        store.get(t)?.delete(h)
      },
    })
    const doc = { ...make(docListeners), hidden: initialHidden, visibilityState: 'visible' }
    const win = make(winListeners)
    const fire = (store: Map<string, Set<() => void>>, t: string) => {
      for (const h of [...(store.get(t) ?? [])]) h()
    }
    return {
      doc,
      win,
      fireDoc: (t: string) => fire(docListeners, t),
      fireWin: (t: string) => fire(winListeners, t),
      countDoc: (t: string) => docListeners.get(t)?.size ?? 0,
      countWin: (t: string) => winListeners.get(t)?.size ?? 0,
    }
  }

  it('visibilitychange 且页面入后台 → 判定已交棒（Android Chrome 主路径）', () => {
    const env = fakeEnv()
    const w = createHandoffWatcher({ doc: env.doc as never, win: env.win as never })
    expect(w.handedOff).toBe(false)
    env.doc.hidden = true
    env.fireDoc('visibilitychange')
    expect(w.handedOff).toBe(true)
  })

  it('只发 blur、不发 visibilitychange → 也判定已交棒（部分 ROM 只发 blur）', () => {
    const env = fakeEnv()
    const w = createHandoffWatcher({ doc: env.doc as never, win: env.win as never })
    env.fireWin('blur')
    expect(w.handedOff).toBe(true)
  })

  it('pagehide → 判定已交棒（iOS 挂起页面时的信号）', () => {
    const env = fakeEnv()
    const w = createHandoffWatcher({ doc: env.doc as never, win: env.win as never })
    env.fireDoc('pagehide')
    expect(w.handedOff).toBe(true)
  })

  it('页面仍可见、无任何切走信号 → 不判定已交棒（此时才提示兜底）', () => {
    const env = fakeEnv()
    const w = createHandoffWatcher({ doc: env.doc as never, win: env.win as never })
    env.fireDoc('visibilitychange') // hidden 仍为 false
    expect(w.handedOff).toBe(false)
  })

  it('dispose 后不再接收信号，且可重复调用', () => {
    const env = fakeEnv()
    const w = createHandoffWatcher({ doc: env.doc as never, win: env.win as never })
    expect(env.countDoc('visibilitychange')).toBe(1)
    w.dispose()
    w.dispose()
    expect(env.countDoc('visibilitychange')).toBe(0)
    expect(env.countWin('blur')).toBe(0)
    env.fireWin('blur')
    expect(w.handedOff).toBe(false)
  })

  it('无 DOM 环境（SSR / 单测）不抛错', () => {
    const w = createHandoffWatcher({ doc: null, win: null })
    expect(w.handedOff).toBe(false)
    expect(() => w.dispose()).not.toThrow()
  })
})

describe('utils/appLaunch · 文案', () => {
  it('判定窗口覆盖 iOS 系统确认框耗时（不再用 1.6s）', () => {
    expect(HANDOFF_WINDOW_MS).toBeGreaterThanOrEqual(2000)
  })

  it('兜底提示不断言「未检测到 App」', () => {
    const msg = launchFallbackHint('微信')
    expect(msg).toContain('微信')
    expect(msg).not.toContain('未检测到')
    expect(msg).not.toContain('未安装')
    // 必须给出可执行的下一步
    expect(msg).toContain('打开网页版')
  })

  it('应用内浏览器提示说明宿主并给出出路', () => {
    const msg = inAppBrowserHint('钉钉', 'wechat')
    expect(msg).toContain('微信')
    expect(msg).toContain('钉钉')
    expect(msg).not.toContain('未检测到')
  })

  it('文案不含 em-dash（设计系统禁令）', () => {
    for (const s of [launchFallbackHint('微信'), inAppBrowserHint('BOSS 直聘', 'qq')]) {
      expect(s).not.toMatch(/[—–]/)
    }
  })
})

/**
 * v1.44.0：微信「文件传输助手」网页版只在电脑浏览器可用。
 *
 * 真机复现：微信内置浏览器里点「微信」卡片 → 提示「已为你打开网页版入口」
 * → 落地页回「暂无法使用微信文件传输助手网页版，可尝试使用电脑端其他浏览器访问」
 * → 回到本页，卡片下方那条兜底链接还是同一个死链。
 * 产品承诺了一条走不通的路，比不给入口更差。
 */
describe('utils/appLaunch · 仅电脑可用的网页版入口', () => {
  it('移动端 + 声明仅电脑 → 拦截', () => {
    expect(isMobileBlockedWebEntry(true, true)).toBe(true)
  })

  it('桌面端不拦截（电脑上该网页版是正常入口）', () => {
    expect(isMobileBlockedWebEntry(true, false)).toBe(false)
  })

  it('普通来源在移动端不拦截（多数网页版手机上可用）', () => {
    expect(isMobileBlockedWebEntry(false, true)).toBe(false)
    expect(isMobileBlockedWebEntry(undefined, true)).toBe(false)
  })

  it('说明文案要讲清「走不通」并给出真的能走的两条路', () => {
    const msg = desktopOnlyWebHint('微信')
    expect(msg).toContain('微信')
    expect(msg).toContain('电脑')
    expect(msg).toContain('选择本机文件')
    expect(msg).toContain('复制链接')
    // 不能继续宣称「已为你打开」——那正是原缺陷的误导点
    expect(msg).not.toContain('已为你打开')
  })

  it('替代入口文案指向「复制链接」而不是「打开网页版」', () => {
    expect(COPY_LINK_LABEL).toContain('复制链接')
    expect(COPY_LINK_LABEL).not.toContain('打开网页版')
  })

  it('复制结果文案区分成功与失败，且失败时给出人工兜底', () => {
    expect(copyResultHint(true)).toContain('已复制')
    expect(copyResultHint(false)).toContain('手动复制')
  })

  it('文案不含 em-dash（设计系统禁令）', () => {
    for (const s of [desktopOnlyWebHint('微信'), copyResultHint(true), copyResultHint(false), COPY_LINK_LABEL]) {
      expect(s).not.toMatch(/[—–]/)
    }
  })
})

describe('utils/appLaunch · copyText', () => {
  it('优先走 navigator.clipboard', async () => {
    const written: string[] = []
    const orig = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (t: string) => { written.push(t) } },
    })
    try {
      expect(await copyText('https://example.com')).toBe(true)
      expect(written).toEqual(['https://example.com'])
    } finally {
      if (orig) Object.defineProperty(navigator, 'clipboard', orig)
      else delete (navigator as { clipboard?: unknown }).clipboard
    }
  })

  it('clipboard 抛错时不崩溃（回退到 execCommand 路径）', async () => {
    const orig = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async () => { throw new Error('NotAllowedError') } },
    })
    try {
      // jsdom 里 document.execCommand 未实现 → 回退也失败，但必须返回 false 而不是抛异常
      expect(typeof (await copyText('x'))).toBe('boolean')
    } finally {
      if (orig) Object.defineProperty(navigator, 'clipboard', orig)
      else delete (navigator as { clipboard?: unknown }).clipboard
    }
  })
})
