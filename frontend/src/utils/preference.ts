/**
 * 岗位偏好四档（第三批 H）—— **单一事实来源**。
 *
 * <p>与后端 `job_favorite.preference` 严格对齐：`STRONG / ACCEPTABLE / BACKUP / EXCLUDED`；
 * **`null` = 未标记**（不得默认成任何一档，与「无数据 ≠ 0」同源）。
 * 分段控件 / 筛选 / 批量对比共用本模块，禁止各处写死枚举或标签。
 */

export const PREFERENCE_STRONG = 'STRONG'
export const PREFERENCE_ACCEPTABLE = 'ACCEPTABLE'
export const PREFERENCE_BACKUP = 'BACKUP'
export const PREFERENCE_EXCLUDED = 'EXCLUDED'

/** 四档枚举（与后端一致；不含「未标记」，未标记以 `null` 表达） */
export type JobPreference = 'STRONG' | 'ACCEPTABLE' | 'BACKUP' | 'EXCLUDED'

/** BaseTag 支持的变体（与 components/BaseTag.vue 的 prop 联合类型一致） */
export type TagVariant = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

export interface PreferenceOption {
  value: JobPreference
  label: string
  /** BaseTag 变体名 */
  variant: TagVariant
  /** 分段控件 title 说明 */
  hint: string
}

/** 四档（数组顺序即展示顺序） */
export const PREFERENCE_OPTIONS: PreferenceOption[] = [
  { value: PREFERENCE_STRONG, label: '强烈意向', variant: 'success', hint: '最想投递，优先准备' },
  { value: PREFERENCE_ACCEPTABLE, label: '可接受', variant: 'info', hint: '合适就投' },
  { value: PREFERENCE_BACKUP, label: '保底', variant: 'warning', hint: '兜底选择' },
  { value: PREFERENCE_EXCLUDED, label: '不考虑', variant: 'danger', hint: '排除，不再跟进' },
]

/** 未标记的中文标签（**不是任何一档**） */
export const PREFERENCE_UNSET_LABEL = '未标记'

/** 合法档位值集合 */
export const PREFERENCE_VALUES: JobPreference[] = PREFERENCE_OPTIONS.map((o) => o.value)

/**
 * 归一化档位：非法/空/非字符串 → `null`（未标记）。
 *
 * <p>**绝不默认成任何一档**——这正是后端 `NULL` 语义在前端的落点。
 */
export function normalizePreference(v: unknown): JobPreference | null {
  if (typeof v !== 'string') return null
  const s = v.trim().toUpperCase()
  return (PREFERENCE_VALUES as string[]).includes(s) ? (s as JobPreference) : null
}

/** 档位显示标签；未标记返回「未标记」 */
export function preferenceLabel(v: unknown): string {
  const p = normalizePreference(v)
  if (p === null) return PREFERENCE_UNSET_LABEL
  return PREFERENCE_OPTIONS.find((o) => o.value === p)!.label
}

/** 档位对应的标签变体（未标记为 info） */
export function preferenceVariant(v: unknown): TagVariant {
  const p = normalizePreference(v)
  if (p === null) return 'info'
  return PREFERENCE_OPTIONS.find((o) => o.value === p)!.variant
}

/** 筛选值：'ALL'（全部，含未标记）| 'UNSET'（仅未标记）| 四档之一 */
export type PreferenceFilter = 'ALL' | 'UNSET' | JobPreference

export interface PreferenceFilterOption {
  value: PreferenceFilter
  label: string
}

/** 筛选项（含「全部」与「未标记」，顺序固定） */
export const PREFERENCE_FILTERS: PreferenceFilterOption[] = [
  { value: 'ALL', label: '全部' },
  ...PREFERENCE_OPTIONS.map((o) => ({ value: o.value as PreferenceFilter, label: o.label })),
  { value: 'UNSET', label: PREFERENCE_UNSET_LABEL },
]

/**
 * 是否命中筛选：`ALL` 含未标记项（**未标记不得被筛掉，也不得被算成某档**）。
 */
export function matchesFilter(pref: unknown, filter: PreferenceFilter): boolean {
  if (filter === 'ALL') return true
  const p = normalizePreference(pref)
  if (filter === 'UNSET') return p === null
  return p === filter
}

/** 各档真实计数（含 `UNSET`）；返回的是**真实计数**，0 就显示 0（不是「无数据」） */
export function preferenceCounts(items: Array<{ preference?: unknown }>): Record<PreferenceFilter, number> {
  const out: Record<PreferenceFilter, number> = {
    ALL: items.length,
    STRONG: 0,
    ACCEPTABLE: 0,
    BACKUP: 0,
    EXCLUDED: 0,
    UNSET: 0,
  }
  for (const it of items) {
    const p = normalizePreference(it?.preference)
    if (p === null) out.UNSET += 1
    else out[p] += 1
  }
  return out
}

/** 批量对比可选数量范围 */
export const COMPARE_MIN = 2
export const COMPARE_MAX = 4

/** 是否允许批量对比（2–4 个） */
export function canCompare(selectedCount: number): boolean {
  return Number.isFinite(selectedCount) && selectedCount >= COMPARE_MIN && selectedCount <= COMPARE_MAX
}

/**
 * 在「勾选切换」时返回下一个选中集：
 * - 已选 → 取消；
 * - 未选且未达上限 → 追加；
 * - 未选且已达上限（{@link COMPARE_MAX}）→ 原样返回（由调用方提示「最多对比 4 个」）。
 */
export function toggleSelection(selected: Array<string | number>, id: string | number): Array<string | number> {
  if (selected.includes(id)) return selected.filter((x) => x !== id)
  if (selected.length >= COMPARE_MAX) return selected.slice()
  return [...selected, id]
}
