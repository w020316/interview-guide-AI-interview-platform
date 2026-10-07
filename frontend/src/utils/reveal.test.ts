import { describe, it, expect, vi } from 'vitest'
import {
  REVEAL_CLASS,
  REVEALED_CLASS,
  countUpElement,
  prefersReducedMotion,
  shouldSkipMotion,
  splitCountText,
  vCountUp,
  vReveal,
} from './reveal'

/**
 * 滚动驱动揭示与数字滚动测试（v1.63.0）
 *
 * 重点锁三件事：
 * 1. 数字文本的拆分规则（后缀必须保留，`∞` 这类无数字的不能被吃掉）；
 * 2. 缓动**不是线性**——这条断言能区分「真的做了缓出」与「随便插值」；
 * 3. 降级路径：环境不支持观察器或用户要求减少动效时，内容必须**直接可见**。
 *    （内容因动效失效而永久不可见，比没有动效糟得多。）
 */

describe('splitCountText', () => {
  it('拆出前导数字与后缀', () => {
    expect(splitCountText('4000+')).toEqual({ target: 4000, suffix: '+' })
    expect(splitCountText('4')).toEqual({ target: 4, suffix: '' })
    expect(splitCountText('12 件')).toEqual({ target: 12, suffix: ' 件' })
  })

  it('没有数字时 target 为 null，原样保留文本', () => {
    expect(splitCountText('∞')).toEqual({ target: null, suffix: '∞' })
    expect(splitCountText('—')).toEqual({ target: null, suffix: '—' })
    expect(splitCountText('')).toEqual({ target: null, suffix: '' })
  })

  it('容忍首尾空白', () => {
    expect(splitCountText('  4000+  ')).toEqual({ target: 4000, suffix: '+' })
  })
})

describe('shouldSkipMotion', () => {
  it('环境不支持 IntersectionObserver 时跳过（保证内容可见）', () => {
    expect(shouldSkipMotion({ hasIO: false })).toBe(true)
  })

  it('用户要求减少动效时跳过', () => {
    const matchMedia = (() => ({ matches: true })) as unknown as typeof window.matchMedia
    expect(shouldSkipMotion({ hasIO: true, matchMedia })).toBe(true)
  })

  it('默认环境（有观察器、无减少动效偏好）不跳过', () => {
    const matchMedia = (() => ({ matches: false })) as unknown as typeof window.matchMedia
    expect(shouldSkipMotion({ hasIO: true, matchMedia })).toBe(false)
  })
})

describe('prefersReducedMotion 与 shouldSkipMotion 是两件事', () => {
  it('无观察器时 shouldSkipMotion 为真，但 prefersReducedMotion 不受影响', () => {
    // 这条区分很重要：数字滚动不依赖观察器，若它误用 shouldSkipMotion，
    // 会在缺少观察器的环境里连数字都不滚（曾被单测抓到）
    expect(shouldSkipMotion({ hasIO: false, matchMedia: (() => ({ matches: false })) as unknown as typeof window.matchMedia })).toBe(true)
    expect(prefersReducedMotion({ matchMedia: (() => ({ matches: false })) as unknown as typeof window.matchMedia })).toBe(false)
  })

  it('matchMedia 抛错时不误判为「要减少动效」', () => {
    const throwing = (() => { throw new Error('nope') }) as unknown as typeof window.matchMedia
    expect(prefersReducedMotion({ matchMedia: throwing })).toBe(false)
  })
})

describe('countUpElement', () => {
  /** 手摇的 rAF：把回调排队，由测试推进时间后手动执行，避免真实计时 */
  function harness(text: string, duration = 100) {
    const el = document.createElement('div')
    el.textContent = text
    let now = 0
    const queue: Array<() => void> = []
    const raf = ((cb: FrameRequestCallback) => {
      queue.push(() => cb(now))
      return queue.length
    }) as unknown as typeof requestAnimationFrame
    countUpElement(el, duration, { raf, now: () => now })
    return { el, run: (t: number) => { now = t; queue.shift()?.() } }
  }

  it('起始显示 0 + 后缀，结束时回到原文', () => {
    const { el, run } = harness('4000+')
    expect(el.textContent).toBe('0+')
    run(100)
    expect(el.textContent).toBe('4000+')
  })

  it('缓动是缓出而非线性（半程应明显过半）', () => {
    const { el, run } = harness('4000+')
    run(50)
    // 线性会是 2000；缓出（easeOutCubic）在 t=0.5 时约 87.5%
    const shown = Number((el.textContent ?? '').replace('+', ''))
    expect(shown).toBeGreaterThan(3300)
    expect(shown).toBeLessThanOrEqual(4000)
  })

  it('无数字的文本保持原样，不产生动画', () => {
    const { el } = harness('∞')
    expect(el.textContent).toBe('∞')
  })

  it('目标为 0 时不滚动（避免「0 → 0」的空动画）', () => {
    const { el } = harness('0 次')
    expect(el.textContent).toBe('0 次')
  })

  it('取消后不再改写文本', () => {
    const el = document.createElement('div')
    el.textContent = '4000+'
    let now = 0
    const queue: Array<() => void> = []
    const raf = ((cb: FrameRequestCallback) => {
      queue.push(() => cb(now))
      return queue.length
    }) as unknown as typeof requestAnimationFrame
    const cancel = countUpElement(el, 100, { raf, now: () => now })
    cancel()
    now = 100
    queue.shift()?.()
    expect(el.textContent).toBe('4000+')
  })
})

describe('指令降级路径', () => {
  it('无 IntersectionObserver 时 v-reveal 立即呈现终态（内容不会永久不可见）', () => {
    // jsdom 默认没有 IntersectionObserver，正对应降级分支
    const el = document.createElement('div')
    ;(vReveal.mounted as (el: HTMLElement, binding: unknown) => void)(el, { value: 120 })
    expect(el.classList.contains(REVEAL_CLASS)).toBe(true)
    expect(el.classList.contains(REVEALED_CLASS)).toBe(true)
    expect(el.style.transitionDelay).toBe('120ms')
  })

  it('无 IntersectionObserver 时 v-count-up 不改写文本', () => {
    const el = document.createElement('div')
    el.textContent = '4000+'
    ;(vCountUp.mounted as (el: HTMLElement, binding: unknown) => void)(el, { value: 100 })
    expect(el.textContent).toBe('4000+')
  })

  it('卸载时断开观察器（不残留）', () => {
    const el = document.createElement('div')
    ;(vReveal.mounted as (el: HTMLElement, binding: unknown) => void)(el, { value: undefined })
    expect(() => (vReveal.unmounted as (el: HTMLElement) => void)(el)).not.toThrow()
  })
})

describe('v-reveal 的揭示条件（含「被滚过头」补丁）', () => {
  /** 捕获回调的假观察器，便于手动投递 entry */
  class MockIO {
    static last: MockIO | null = null
    cb: IntersectionObserverCallback
    constructor(cb: IntersectionObserverCallback) {
      this.cb = cb
      MockIO.last = this
    }
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() { return [] }
  }

  /** 构造一条 entry；只需用到 isIntersecting 与 boundingClientRect.bottom */
  function entry(isIntersecting: boolean, bottom: number): IntersectionObserverEntry {
    return { isIntersecting, boundingClientRect: { bottom } } as unknown as IntersectionObserverEntry
  }

  function mountReveal(): HTMLElement {
    vi.stubGlobal('IntersectionObserver', MockIO as unknown as typeof IntersectionObserver)
    const el = document.createElement('div')
    ;(vReveal.mounted as (el: HTMLElement, binding: unknown) => void)(el, { value: undefined })
    return el
  }

  it('进入视口时揭示', () => {
    const el = mountReveal()
    MockIO.last!.cb([entry(true, 400)], MockIO.last as unknown as IntersectionObserver)
    expect(el.classList.contains(REVEALED_CLASS)).toBe(true)
    vi.unstubAllGlobals()
  })

  it('IO 回调不负责「滚过头」的条目（那种情况观察器根本不会回调）', () => {
    // 瞬时位移时 ratio 恒为 0 → 回调不会被触发 → 不能把兜底逻辑写在回调里
    const el = mountReveal()
    MockIO.last!.cb([entry(false, -120)], MockIO.last as unknown as IntersectionObserver)
    expect(el.classList.contains(REVEALED_CLASS)).toBe(false)
    vi.unstubAllGlobals()
  })

  it('瞬时跳转把元素甩到视口上方时，滚动兜底会揭示它', async () => {
    const el = mountReveal()
    // 模拟元素已被甩到视口上方（真机缺陷：此时 IO 不会回调，元素会永久空白）
    el.getBoundingClientRect = () => ({ bottom: -120, top: -400 }) as DOMRect
    window.dispatchEvent(new Event('scroll'))
    // 兜底走 rAF 节流，等两帧确保 check 已执行
    await new Promise((r) => requestAnimationFrame(() => r(null)))
    await new Promise((r) => requestAnimationFrame(() => r(null)))
    expect(el.classList.contains(REVEALED_CLASS)).toBe(true)
    vi.unstubAllGlobals()
  })

  it('仍在视口下方（尚未滚到）时不揭示', () => {
    const el = mountReveal()
    MockIO.last!.cb([entry(false, 1400)], MockIO.last as unknown as IntersectionObserver)
    expect(el.classList.contains(REVEALED_CLASS)).toBe(false)
    vi.unstubAllGlobals()
  })
})

describe('reduced-motion 下不注册观察器', () => {
  it('用户要求减少动效时，v-count-up 完全不启动', () => {
    const matchMedia = vi.fn(() => ({ matches: true }))
    vi.stubGlobal('matchMedia', matchMedia)
    vi.stubGlobal('IntersectionObserver', class {
      observe() { throw new Error('不应注册观察器') }
      unobserve() {}
      disconnect() {}
    })
    const el = document.createElement('div')
    el.textContent = '4000+'
    expect(() =>
      (vCountUp.mounted as (el: HTMLElement, binding: unknown) => void)(el, { value: 100 }),
    ).not.toThrow()
    expect(el.textContent).toBe('4000+')
    vi.unstubAllGlobals()
  })
})
