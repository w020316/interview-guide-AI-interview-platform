/**
 * 招聘类型的展示标签（前端统一口径）。
 *
 * 背景：管理后台「招聘类型分布」原先内联了一份映射，但漏了 `PART_TIME`，
 * 于是图例里只有它显示原始英文枚举，其余都是「社招 / 秋招 / 实习…」，格外扎眼
 *（2026-09-23 第二轮 UX 测试 P3-F）。抽成独立模块 + 导出取值清单后，
 * 可以用测试断言「后端所有取值都有中文标签」，避免以后后端新增枚举值时前端再次漏配。
 */

/** 后端 `recruitType` 的全部取值（与 JobPostingEntity / 种子数据保持一致） */
export const RECRUIT_TYPE_CODES = [
  'AUTUMN',
  'SPRING',
  'SOCIAL',
  'INTERN',
  'PART_TIME',
  'TARGETED',
] as const

/** 取值 → 中文标签 */
export const RECRUIT_TYPE_LABELS: Record<string, string> = {
  AUTUMN: '秋招',
  SPRING: '春招',
  SOCIAL: '社招',
  INTERN: '实习',
  PART_TIME: '兼职',
  TARGETED: '定向',
}

/**
 * 取招聘类型的中文标签；未知取值**回退为原值**，空值回退为「—」。
 *
 * 注意：未配映射时回退为原值是**刻意**的 —— 这样新增枚举值漏配时，
 * 界面上会立刻看到原始英文（而不是一片空白），便于发现。
 */
export function recruitTypeLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return RECRUIT_TYPE_LABELS[code] || code
}
