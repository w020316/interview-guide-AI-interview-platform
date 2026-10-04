/**
 * 今日待办（第三批 B）纯函数层。
 *
 * <p>后端 `GET /api/todo/today` 已完成 read-time 聚合（**零 AI**）；本模块只做
 * **归一化 / 排序 / 计数**，把「哪一组有内容 / 如何排序 / 计数如何聚合」这类判定
 * 收敛到可单测的纯函数里，不写进组件模板。
 *
 * <p><b>R3「无数据 ≠ 0」</b>：后端对「源不可用」用 `count=null`、对「确实没有待办」
 * 用 `count=0` 严格区分。本模块据此保证：
 * <ul>
 *   <li>空组（无 items）一律不进入展示列表，因此 DOM 永远**不会出现「0 条」**；</li>
 *   <li>`available=false` 的组进入 {@link TodoView.failures}，由组件渲染局部失败态而非 0。</li>
 * </ul>
 */

/** 后端原始响应（字段全部按 unknown 处理，避免契约漂移导致运行时崩溃） */
export interface TodoTodayResponse {
  generatedAt?: unknown
  groups?: unknown
  partialFailures?: unknown
}

/** 归一化后的单条待办 */
export interface TodoItem {
  id: string | number | null
  title: string
  subtitle: string
  reason: string
  /** 跳转路径（只允许使用既有 path，见 R4） */
  route: string
  routeQuery: Record<string, string | number> | null
}

/** 归一化后的分组 */
export interface TodoGroup {
  key: string
  label: string
  /** 待办条数；`null` 表示数据源不可用（**绝不是 0**） */
  count: number | null
  available: boolean
  items: TodoItem[]
}

/** 某组数据源不可用时的失败信息 */
export interface TodoFailure {
  key: string
  label: string
  message: string
}

/** 组件渲染用的视图模型 */
export interface TodoView {
  /** 仅含有内容的组，按固定优先级排序 */
  groups: TodoGroup[]
  /** 局部失败（源不可用）的组 */
  failures: TodoFailure[]
  /** 是否存在任一待办 */
  hasAny: boolean
  /** 是否有任一数据源可用（全部不可用 = 整块失败态，而非空态） */
  anyAvailable: boolean
  /** 已知待办总数；无任何已知来源时为 `null`（**不臆造 0**） */
  total: number | null
}

/** 展示顺序（与后端组标识一致） */
export const TODO_GROUP_ORDER = ['FOLLOW_UP', 'UPCOMING', 'DEADLINE', 'PLANNED'] as const

const DEFAULT_LABELS: Record<string, string> = {
  FOLLOW_UP: '待跟进投递',
  UPCOMING: '近期日程',
  DEADLINE: '临近截止',
  PLANNED: '待投递',
}

function asString(v: unknown, fallback = ''): string {
  if (typeof v === 'string') return v
  if (v == null) return fallback
  return String(v)
}

function normalizeRouteQuery(v: unknown): Record<string, string | number> | null {
  if (v == null || typeof v !== 'object' || Array.isArray(v)) return null
  const out: Record<string, string | number> = {}
  for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
    if (typeof val === 'string' || typeof val === 'number') out[k] = val
    else if (val != null) out[k] = String(val)
  }
  return Object.keys(out).length ? out : null
}

function normalizeItem(raw: unknown): TodoItem | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const title = asString(r.title).trim()
  const route = asString(r.route).trim()
  // 既无标题又无落点的项无法安全展示/跳转，直接丢弃，避免渲染空壳
  if (!title || !route) return null
  const id = typeof r.id === 'string' || typeof r.id === 'number' ? r.id : null
  return {
    id,
    title,
    subtitle: asString(r.subtitle).trim(),
    reason: asString(r.reason).trim(),
    route,
    routeQuery: normalizeRouteQuery(r.routeQuery),
  }
}

/**
 * 把后端原始响应归一化为「组列表 + 失败列表」（容错，不抛异常）。
 */
export function normalizeTodoResponse(raw: TodoTodayResponse | null | undefined): {
  groups: TodoGroup[]
  failures: TodoFailure[]
} {
  const rawGroups = Array.isArray(raw?.groups) ? (raw?.groups as unknown[]) : []
  const groups: TodoGroup[] = []
  for (const g of rawGroups) {
    if (g == null || typeof g !== 'object') continue
    const rg = g as Record<string, unknown>
    const key = asString(rg.key).trim()
    if (!key) continue
    const label = asString(rg.label).trim() || DEFAULT_LABELS[key] || key
    const available = rg.available !== false
    const items = (Array.isArray(rg.items) ? (rg.items as unknown[]) : [])
      .map(normalizeItem)
      .filter((x): x is TodoItem => x !== null)
    // count：源不可用恒为 null；可用时以**真实 items 数**为准（不采信可能过期的后端 count）
    const count = available ? items.length : null
    groups.push({ key, label, count, available, items })
  }

  const rawFailures = Array.isArray(raw?.partialFailures) ? (raw?.partialFailures as unknown[]) : []
  const failures: TodoFailure[] = []
  for (const f of rawFailures) {
    if (f == null || typeof f !== 'object') continue
    const rf = f as Record<string, unknown>
    const key = asString(rf.group).trim()
    if (!key) continue
    const label = groups.find((g) => g.key === key)?.label || DEFAULT_LABELS[key] || key
    failures.push({
      key,
      label,
      message: asString(rf.message).trim() || `「${label}」数据暂时取不到`,
    })
  }
  // 兜底：available=false 但后端未给 partialFailures 时补齐，保证失败态不漏
  for (const g of groups) {
    if (!g.available && !failures.some((f) => f.key === g.key)) {
      failures.push({ key: g.key, label: g.label, message: `「${g.label}」数据暂时取不到` })
    }
  }
  return { groups, failures }
}

function orderIndex(key: string): number {
  const i = (TODO_GROUP_ORDER as readonly string[]).indexOf(key)
  return i === -1 ? TODO_GROUP_ORDER.length : i
}

/** 组排序：按固定优先级（未知组排末尾，保持稳定）。 */
export function sortGroups(groups: TodoGroup[]): TodoGroup[] {
  return groups
    .map((g, i) => ({ g, i }))
    .sort((a, b) => orderIndex(a.g.key) - orderIndex(b.g.key) || a.i - b.i)
    .map((x) => x.g)
}

/**
 * 组内计数文案：空组或源不可用返回**空串**——**绝不产出「0 条」**。
 */
export function groupCountText(group: TodoGroup): string {
  if (!group.available || group.items.length === 0) return ''
  return `${group.items.length} 条`
}

/** 已知待办总数：无任何可用来源 → `null`（不臆造 0）。 */
export function aggregateCount(groups: TodoGroup[]): number | null {
  const usable = groups.filter((g) => g.available)
  if (!usable.length) return null
  return usable.reduce((sum, g) => sum + g.items.length, 0)
}

/** 构建待办视图模型（组件渲染的唯一入口）。 */
export function buildTodoView(raw: TodoTodayResponse | null | undefined): TodoView {
  const { groups, failures } = normalizeTodoResponse(raw)
  const sorted = sortGroups(groups)
  const active = sorted.filter((g) => g.available && g.items.length > 0)
  return {
    groups: active,
    failures,
    hasAny: active.length > 0,
    anyAvailable: groups.some((g) => g.available),
    total: aggregateCount(groups),
  }
}
