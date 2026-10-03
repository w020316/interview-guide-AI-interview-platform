/**
 * 展示格式化工具（v1.46.x UX 修复）
 *
 * 本项目规范：「无数据」的展示全站统一为 em dash（—）。
 * 此前 `—` / `-` / `0` 三种写法并存，其中 `0` 最危险——它把
 * 「没有数据」和「真的得了 0 分」表达成同一个样子（详见第六轮 UX 报告 P1）。
 *
 * 统一出口，后续所有「无数据」占位都从这里取。
 */

/** 全站统一的「无数据」占位符 */
export const EMPTY = '—'

/**
 * 把任意值格式化为展示文本：
 * - `null` / `undefined` / 空字符串 → {@link EMPTY}
 * - 其余（含数字 `0`，它是有效值）→ `String(v)`
 */
export function formatEmpty(v: unknown): string {
  if (v === null || v === undefined) return EMPTY
  if (typeof v === 'string' && v.trim() === '') return EMPTY
  return String(v)
}
