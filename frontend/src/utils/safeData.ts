/**
 * 接口返回值「形状兜底」（v1.76.1）
 *
 * ── 为什么需要它 ────────────────────────────────────────────
 * 线上出现过「整页空白，控制台一条报错都没有」。链条是：
 *   1. `/api/jobs/meta` 的 data 从对象变成数组（或某一字段变成 null）；
 *   2. 页面里写的是 `meta.value = res || { ...默认值 }` —— **空数组是 truthy**，
 *      兜底分支根本不会执行；
 *   3. 模板读 `meta.sources.length` 抛 TypeError → 整棵路由子树渲染失败；
 *   4. 全局 errorHandler 在生产环境**吞掉**了异常 → 用户只看到一个白屏。
 *
 * 结论：`x || default` 只能兜 null / undefined，兜不住「类型变了」。
 * 形状校验必须和「缺省值」分开做，并且在**赋值的那一刻**做，不能指望模板里每个字段都写防御。
 */

/** 只接受真数组；null / undefined / 对象 / 字符串一律回落到空数组 */
export function asArray<T>(v: unknown): T[] {
  return Array.isArray(v) ? (v as T[]) : []
}

/**
 * 只接受普通对象（排除 null 与数组），与 defaults 做一次浅合并。
 * 注意：它不校验**字段类型**（`{sources: null}` 仍会原样合并），
 * 所以数组字段请再包一层 `asArray`（见 JobsView 的 normalizeMeta）。
 */
export function asObject<T extends object>(v: unknown, defaults: T): T {
  if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
    return { ...defaults, ...(v as Partial<T>) }
  }
  return { ...defaults }
}

/** 有限数字兜底：NaN / Infinity / 字符串数字都按「没拿到」处理 */
export function asFiniteNumber(v: unknown, fallback = 0): number {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : fallback
}
