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

/** 取值 → 中文标签（与后端 RecruitType.label()、JobsView 分栏口径保持一致） */
export const RECRUIT_TYPE_LABELS: Record<string, string> = {
  AUTUMN: '秋招',
  SPRING: '春招',
  SOCIAL: '社招',
  INTERN: '实习',
  PART_TIME: '兼职',
  TARGETED: '定向专项',
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

/**
 * 招聘广场分栏值 → `GET /api/jobs` 的 `recruitType` 查询参数（v1.44.0，P3-03）。
 *
 * <h2>为什么必须归一化</h2>
 *
 * <p>`recruitTabs` 里有三个**不是真实招聘类型**的值：
 * <ul>
 *   <li>`''`（全部国内）—— 表示「不限招聘类型」；</li>
 *   <li>`OVERSEAS`（海外远程）—— 虚拟值，应转成 `overseas=true`，库里没有该类型；</li>
 *   <li>`FAVORITE`（我的收藏）—— 虚拟值，走 `/api/jobs/favorite`，不发列表查询。</li>
 * </ul>
 *
 * <p>后端 `RecruitType` 已对 `recruitType` 做**严格校验**（非法取值返回 400）。
 * 若把上面三个虚拟值之一当作 `recruitType` 发出，会被判成 400。
 * 此前 `fetchJobs` 只处理了 `OVERSEAS`，`FAVORITE` 之所以没炸，纯粹是因为
 * 收藏分栏下刷新/分页/筛选栏靠 UI 隐藏而没有被触发 —— 这是**靠 UI 维持的不变式**，
 * 一旦按钮位置变动就会退化为线上 400。
 *
 * <p>抽成纯函数后，这个「虚拟值不外泄」的约束可以在单测里被锁定，不再依赖界面结构。
 *
 * @param tabValue 分栏值（可能是真实类型、空串或虚拟值）
 * @returns 真实的招聘类型 code；空串与两个虚拟值一律返回 `undefined`（即「不改写参数」）
 */
export function toQueryRecruitType(tabValue: string | null | undefined): string | undefined {
  if (!tabValue) return undefined
  if (tabValue === 'OVERSEAS' || tabValue === 'FAVORITE') return undefined
  return tabValue
}
