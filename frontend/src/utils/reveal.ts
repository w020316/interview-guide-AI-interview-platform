/**
 * 滚动驱动揭示 + 数字滚动（v1.63.0）
 *
 * ── 为什么要这两个指令 ──────────────────────────────────────────────
 * 此前页面的入场动效是 `fade-in-up` 工具类：**页面加载即播放**。
 * 结果是首屏以下的区块在用户还没滚到时就演完了，滚下去看到的是静止画面——
 * 动效「花了钱但没被看见」，这是获奖级站点与普通站点最直观的差别之一。
 *
 * 这两个指令把动效的**触发点**从「加载完成」改为「进入视口」：
 * - `v-reveal`：元素进入视口时淡入上移，支持 stagger 延迟。
 * - `v-count-up`：数字从 0 滚到目标值（含 `+` 等后缀），只在进入视口时播一次。
 *
 * ── 两条硬约束 ────────────────────────────────────────────────────
 * 1. **尊重 `prefers-reduced-motion`**：命中时直接呈现终态，不做任何过渡。
 *    这不是可选项——前庭功能敏感的用户会因位移与缩放产生不适。
 * 2. **不用 `IntersectionObserver` 的降级路径**：环境不支持时直接显示终态，
 *    绝不让内容因动效失效而**永久不可见**（那比没有动效糟得多）。
 */

// ⚠️ 用 `ObjectDirective` 而不是 `Directive`：后者是「函数式指令 | 对象式指令」的联合类型，
// 联合上取 `mounted` / `unmounted` 会报 TS2339（单测里直接调钩子时暴露过）。
import type { ObjectDirective } from 'vue'

/** 揭示用的类名（在 `styles/variables.css` 里定义终态与过渡） */
export const REVEAL_CLASS = 'reveal'
/** 已揭示的类名 */
export const REVEALED_CLASS = 'is-revealed'

/** 每个元素各自持有 observer，卸载时必须断开，否则滚动时会持续空转 */
const observers = new WeakMap<HTMLElement, IntersectionObserver>()

/**
 * 待揭示元素 + 滚动兜底。
 *
 * ⚠️ 为什么 IO 之外还需要兜底（真机实测得出的结论）：
 * 元素从「视口下方」**瞬时**跳到「视口上方」时（锚点跳转 / `scrollTo` / End 键），
 * 它的 intersection ratio 始终是 0 —— **观察器根本不会回调**。
 * 于是「在回调里补一条『已被滚过头就揭示』」是无效的：那段代码压根不会被执行。
 * （实测：瞬时滚到底后 9 个元素里只有 5 个被揭示，另外 4 个永久停在 opacity:0。）
 * 所以必须另起一个滚动监听，主动检查「是否已越过视口上沿」。
 */
const pending = new Set<HTMLElement>()
let fallbackBound = false

/** 揭示元素并停止观察它 */
function revealNow(el: HTMLElement): void {
  el.classList.add(REVEALED_CLASS)
  pending.delete(el)
  observers.get(el)?.disconnect()
  observers.delete(el)
}

/** 绑定一次全局滚动兜底（rAF 节流，避免滚动时反复触发布局计算） */
function bindScrollFallback(): void {
  if (fallbackBound || typeof window === 'undefined') return
  fallbackBound = true

  let ticking = false
  const check = () => {
    ticking = false
    for (const el of [...pending]) {
      if (el.getBoundingClientRect().bottom < 0) revealNow(el)
    }
  }
  const onScroll = () => {
    if (ticking || pending.size === 0) return
    ticking = true
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(check)
    else check()
  }
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
}

/**
 * 用户是否要求「减少动效」。
 *
 * ⚠️ 与 {@link shouldSkipMotion} 是**两件事**，不要合并：
 * 本函数只关心用户偏好；`shouldSkipMotion` 还关心环境能力。
 * 数字滚动（`countUpElement`）**不需要** IntersectionObserver，
 * 若它误用后者，会在缺少观察器的环境里连数字都不滚——这是被测试抓到过的真实 bug。
 */
export function prefersReducedMotion(env?: { matchMedia?: typeof window.matchMedia }): boolean {
  const mm = env?.matchMedia ?? (typeof window !== 'undefined' ? window.matchMedia : undefined)
  if (!mm) return false
  try {
    return mm('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

/** 是否应当跳过动效（用户偏好减少动效，或环境不支持观察器） */
export function shouldSkipMotion(env?: { matchMedia?: typeof window.matchMedia; hasIO?: boolean }): boolean {
  const hasIO = env?.hasIO ?? typeof IntersectionObserver !== 'undefined'
  if (!hasIO) return true
  return prefersReducedMotion(env)
}

/**
 * 把一段展示文本拆成「可滚动的数字」与「保持不动的后缀」。
 *
 * 只处理**前导数字**，其余原样保留：
 * `'4000+'` → `{ target: 4000, suffix: '+' }`；`'∞'` → `{ target: null, suffix: '∞' }`。
 * 返回 `target: null` 表示这段文本没有可滚动的数字，调用方应原样显示。
 */
export function splitCountText(text: string): { target: number | null; suffix: string } {
  const m = /^(\d+)([\s\S]*)$/.exec((text ?? '').trim())
  if (!m) return { target: null, suffix: text ?? '' }
  return { target: Number(m[1]), suffix: m[2] }
}

/** 缓出曲线：先快后慢，收尾稳，比线性更有「重量感」 */
function easeOutCubic(t: number): number {
  return 1 - Math.pow(1 - t, 3)
}

/**
 * 把元素文本里的数字从 0 滚到目标值。
 *
 * 返回一个取消函数（组件卸载时调用）。目标为 0 或文本无数字时不做动画。
 * 动画用 `requestAnimationFrame`，并在每一帧写入整数——避免小数抖动。
 */
export function countUpElement(
  el: HTMLElement,
  durationMs = 1100,
  env?: { raf?: typeof requestAnimationFrame; now?: () => number },
): () => void {
  const original = el.textContent ?? ''
  const { target, suffix } = splitCountText(original)
  // ⚠️ 这里只判「用户是否要求减少动效」——数字滚动不需要 IntersectionObserver，
  // 用 shouldSkipMotion() 会让它在无观察器的环境下静默失效。
  if (target === null || target <= 0 || prefersReducedMotion()) {
    el.textContent = original
    return () => {}
  }

  const raf = env?.raf ?? (typeof requestAnimationFrame !== 'undefined' ? requestAnimationFrame : null)
  const now = env?.now ?? (() => Date.now())
  if (!raf) {
    el.textContent = original
    return () => {}
  }

  let cancelled = false
  const start = now()

  const tick = () => {
    if (cancelled) return
    const elapsed = now() - start
    const progress = Math.min(1, elapsed / durationMs)
    el.textContent = `${Math.round(target * easeOutCubic(progress))}${suffix}`
    if (progress < 1) raf(tick)
    else el.textContent = original
  }

  el.textContent = `0${suffix}`
  raf(tick)

  return () => {
    cancelled = true
    el.textContent = original
  }
}

/** 取消函数表，供卸载时清理 */
const countUpCleanups = new WeakMap<HTMLElement, () => void>()

/**
 * `v-reveal`：进入视口时揭示。
 *
 * 用法：`<div v-reveal>` 或 `<div v-reveal="120">`（120 = stagger 延迟毫秒）。
 */
export const vReveal: ObjectDirective<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    el.classList.add(REVEAL_CLASS)
    const delay = typeof binding.value === 'number' ? binding.value : 0
    if (delay > 0) el.style.transitionDelay = `${delay}ms`

    if (shouldSkipMotion()) {
      el.classList.add(REVEALED_CLASS)
      return
    }

    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          // 进入视口即揭示；「已被滚过头」由滚动兜底负责（见 pending 的说明：
          // 瞬时位移时本回调不会被触发，所以不能只依赖这里）。
          if (!entry.isIntersecting) continue
          revealNow(el)
        }
      },
      // 下方留 12% 余量：元素「探进」视口一点就触发，而不是等完全进入，
      // 否则用户会觉得内容「卡一下才出现」。
      { rootMargin: '0px 0px -12% 0px', threshold: 0.12 },
    )
    io.observe(el)
    observers.set(el, io)
    pending.add(el)
    bindScrollFallback()
  },
  unmounted(el) {
    observers.get(el)?.disconnect()
    observers.delete(el)
    pending.delete(el)
  },
}

/**
 * `v-count-up`：进入视口时把文本里的数字从 0 滚到目标值。
 *
 * 用法：`<div v-count-up>4000+</div>`（后缀 `+` 会保留）。
 */
export const vCountUp: ObjectDirective<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    const duration = typeof binding.value === 'number' ? binding.value : 1100

    if (shouldSkipMotion()) return

    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          countUpCleanups.set(el, countUpElement(el, duration))
          io.unobserve(el)
        }
      },
      { threshold: 0.4 },
    )
    io.observe(el)
    observers.set(el, io)
  },
  unmounted(el) {
    observers.get(el)?.disconnect()
    observers.delete(el)
    countUpCleanups.get(el)?.()
    countUpCleanups.delete(el)
  },
}
