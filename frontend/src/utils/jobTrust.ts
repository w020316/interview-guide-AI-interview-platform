/**
 * 岗位数据可信度标注（竞品清单 #22）
 *
 * ── 为什么是「标注」而不是「评分」 ────────────────────────────────
 * 招聘广场聚合了十几个数据源：官方公开 API（RemoteOK / Himalayas / Jobicy …）、
 * 官方 RSS（WeWorkRemotely）、社区活源（V2EX 酷工作 / HN Who is hiring）、
 * 以及本项目维护的内置精选。它们的**可核验程度天差地别**——
 * 官方 API 每条都有直达申请链接，而 HN 的评论帖经常只有一段描述、没有申请入口。
 *
 * 用户点开一个岗位，无从判断「这条能不能直接投」。因此这里把**来源事实**显式标出来。
 *
 * ── 纪律：只陈述事实，不给「信任分」 ────────────────────────────
 * 本项目红线之一是「不编造」。给岗位打一个 0~100 的「可信度分」属于**臆造**——
 * 我们没有能力核实任何一条岗位的真伪，也没有任何一条数据支持「A 源比 B 源更可信」。
 * 因此本模块只做两件事：
 *   1. 按**明确定义的规则**把来源归类（来源类型是客观事实：它是 RSS 就是 RSS）；
 *   2. 如实转述已有字段（有没有申请链接、距截止还有几天）。
 *
 * ── 为什么放在前端纯函数而不是后端 ──────────────────────────────
 * 判据全部来自**前端已经拿到的字段**（platform / applyUrl / deadline）。
 * 后端下发一个 trustScore 会把展示逻辑耦合进 API 契约，且一旦有人拿它做排序，
 * 「来源歧视」就会被固化进产品——那是我们明确不想做的。
 *
 * 零网络、零依赖，纯输入输出 → 完全可单测。
 */

import { EMPTY } from './format'

/**
 * 来源类型（客观分类，非优劣排序）。
 *
 * - `official-api`：官方公开 JSON API，结构化程度最高，通常带直达申请链接
 * - `official-rss`：官方 RSS 订阅，结构化程度次之
 * - `community`：社区人工发帖（V2EX / Hacker News），信息由发帖人自述，可能缺申请入口
 * - `curated`：本项目维护的内置精选（人工整理，截止日期可控）
 * - `partner`：需配置数据服务的第三方招聘平台适配器
 * - `unknown`：未在清单中的新来源（**不猜**，如实标为「其他来源」）
 */
export type JobSourceKind =
  | 'official-api'
  | 'official-rss'
  | 'community'
  | 'curated'
  | 'partner'
  | 'unknown'

/** 来源类型的展示元数据（与 JobSourceKind 一一对应） */
export interface SourceKindMeta {
  /** 展示短标签（卡片角标用，控制在 4~7 字） */
  label: string
  /** 悬停说明：这一句必须能回答「它凭什么这样说」 */
  hint: string
}

/**
 * 来源类型 → 展示元数据。
 *
 * <p>措辞刻意避开「可信 / 不可信」这类判断词，只说**这个源是怎么来的**。
 */
export const SOURCE_KIND_META: Record<JobSourceKind, SourceKindMeta> = {
  'official-api': {
    label: '官方接口',
    hint: '来自该平台的官方公开数据接口，结构化程度高，通常提供直达申请入口',
  },
  'official-rss': {
    label: '官方订阅',
    hint: '来自该平台的官方 RSS 订阅源，由平台方发布',
  },
  community: {
    label: '社区发帖',
    hint: '来自社区用户自行发布的招聘帖，详情由发帖人提供，建议点开原始链接核实',
  },
  curated: {
    label: '平台精选',
    hint: '由本平台整理维护的精选岗位，截止日期经过人工确认',
  },
  partner: {
    label: '合作渠道',
    hint: '来自已接入的招聘平台数据服务',
  },
  unknown: {
    label: '其他来源',
    hint: '该来源尚未归类，岗位信息由数据方提供，建议点开原始链接核实',
  },
}

/**
 * 平台展示名 → 来源类型。
 *
 * <p><b>为什么用清单匹配而不是让后端下发</b>：平台名本身是稳定的展示名
 * （由各适配器的 {@code platform()} 返回），前端按「品牌关键词」匹配即可。
 * 新增一个同类源（比如又来一个官方 API）时，只需往对应清单里加一个词。
 *
 * <p><b>匹配顺序</b>：官方 API → 官方 RSS → 社区 → 精选 → 合作。
 * 注意关键词之间**不存在包含关系**（已核对：`v2ex` 不含于任何其他词，
 * `weworkremotely` 与 `remoteok` 不同），所以顺序不会改变结果；
 * 保留顺序只是为了让「更具体的源」优先。
 */
const KIND_KEYWORDS: ReadonlyArray<readonly [JobSourceKind, readonly string[]]> = [
  // 官方公开 JSON API
  ['official-api', ['RemoteOK', 'Remotive', 'Arbeitnow', 'Jobicy', 'Himalayas']],
  // 官方 RSS
  ['official-rss', ['WeWorkRemotely']],
  // 社区人工发帖
  ['community', ['V2EX', 'HN Who is hiring', 'Hacker News']],
  // 本项目内置精选（适配器名即「XX精选」）
  ['curated', ['内置精选', '行业精选', '服务精选', '社招精选', '精选频道', '热招速递', '秋招精选', '兼职专区']],
  // 需配置数据服务的第三方平台
  ['partner', ['第三方平台', '智联招聘', '前程无忧', 'BOSS直聘']],
]

/**
 * 判定一条岗位的来源类型（纯函数，永不抛错）。
 *
 * <p>未知来源**如实返回 `unknown`**，绝不默认成「可信」——默认值的选择本身就是一种断言。
 */
export function classifySource(platform: string | null | undefined): JobSourceKind {
  const name = (platform || '').trim()
  if (!name) return 'unknown'
  for (const [kind, keywords] of KIND_KEYWORDS) {
    if (keywords.some((k) => name.includes(k))) return kind
  }
  return 'unknown'
}

/** 来源类型的展示元数据（内含 label 与 hint） */
export function sourceKindMeta(platform: string | null | undefined): SourceKindMeta {
  return SOURCE_KIND_META[classifySource(platform)]
}

/**
 * 行为事实：这条岗位「能不能直接投」。
 *
 * <p>判据只有一个——**有没有申请链接**。这是客观事实，不做任何推断：
 * 有链接就如实说「有申请入口」，没有就说「需前往来源查看」，
 * **不写「该岗位可能已失效」这种猜测**。
 */
export interface ApplyFact {
  /** 是否有直达申请链接 */
  hasApplyUrl: boolean
  /** 展示用短标签 */
  label: string
  /** 悬停说明 */
  hint: string
}

/** 由申请链接推导「可申请性」事实 */
export function applyFact(applyUrl: string | null | undefined): ApplyFact {
  const has = !!(applyUrl && applyUrl.trim())
  return has
    ? {
        hasApplyUrl: true,
        label: '有申请入口',
        hint: '该岗位提供了直达的官方申请链接，点击「立即申请」可前往',
      }
    : {
        hasApplyUrl: false,
        label: '无直达入口',
        hint: '该岗位未提供申请链接，需前往来源渠道自行查找——这不代表岗位失效',
      }
}

/** 一条岗位的可信度标注（供卡片角标与详情弹窗使用） */
export interface JobTrust {
  kind: JobSourceKind
  /** 来源类型短标签，如「官方接口」 */
  kindLabel: string
  /** 来源类型说明（悬停用） */
  kindHint: string
  hasApplyUrl: boolean
  /** 可申请性短标签 */
  applyLabel: string
  /** 可申请性说明 */
  applyHint: string
  /**
   * 是否需要提醒用户「去原始链接核实」。
   *
   * <p>仅当**两个条件同时成立**：来源是社区发帖（详情由发帖人自述）
   * **且**没有直达申请链接。任一条件不满足就不提示——避免在任何地方
   * 都挂一句「请核实」，那样等于没说。
   */
  needsVerify: boolean
}

/**
 * 汇总一条岗位的可信度标注。
 *
 * @param platform 岗位来源平台展示名（`JobPostingEntity.platform`）
 * @param applyUrl 申请链接；空串/null 视为无
 */
export function jobTrust(
  platform: string | null | undefined,
  applyUrl: string | null | undefined,
): JobTrust {
  const kind = classifySource(platform)
  const meta = SOURCE_KIND_META[kind]
  const apply = applyFact(applyUrl)
  return {
    kind,
    kindLabel: meta.label,
    kindHint: meta.hint,
    hasApplyUrl: apply.hasApplyUrl,
    applyLabel: apply.label,
    applyHint: apply.hint,
    needsVerify: kind === 'community' && !apply.hasApplyUrl,
  }
}

/**
 * 岗位新鲜度（如实转述，不评分）。
 *
 * @param deadline 截止日期 `yyyy-MM-dd`；null → 「长期有效」
 * @param today 注入「今天」以便单测（默认取系统时间）
 * @returns 短标签 + 是否需要醒目提示（≤7 天标为 urgent）
 */
export interface Freshness {
  /** 展示标签 */
  label: string
  /** 是否临近截止（≤7 天），供 UI 决定是否用强调色 */
  urgent: boolean
  /** 距截止天数；无截止日期时为 null（**不是 0**） */
  daysLeft: number | null
}

/**
 * 计算岗位新鲜度。
 *
 * <p>与 `JobsView.vue` 既有的 `deadlineText()` 口径保持一致（同样按当天 23:59:59 取整，
 * 同样把已过期标为「已截止」），但**额外返回结构化字段**供标注入口使用。
 */
export function jobFreshness(
  deadline: string | null | undefined,
  today: Date = new Date(),
): Freshness {
  if (!deadline || !deadline.trim()) {
    return { label: '长期有效', urgent: false, daysLeft: null }
  }
  const d = new Date(deadline + 'T23:59:59')
  if (Number.isNaN(d.getTime())) {
    // 日期不可解析：如实说「日期未知」，不猜成「长期有效」
    return { label: EMPTY, urgent: false, daysLeft: null }
  }
  const diff = Math.ceil((d.getTime() - today.getTime()) / 86400000)
  if (diff < 0) return { label: '已截止', urgent: false, daysLeft: diff }
  if (diff <= 7) return { label: `仅剩 ${diff} 天`, urgent: true, daysLeft: diff }
  if (diff <= 30) return { label: `${diff} 天后截止`, urgent: false, daysLeft: diff }
  return { label: `截止 ${deadline}`, urgent: false, daysLeft: diff }
}

/**
 * 来源类型汇总标签（列表页批量展示用）。
 *
 * <p>给出一句话说明「这个列表里的岗位来自哪些类型的源」，让用户对整屏数据
 * 的可核验程度有个整体预期，而不是逐条去猜。
 *
 * @param platforms 当前列表中出现的全部来源平台名
 */
export function sourceMixLabel(platforms: ReadonlyArray<string | null | undefined>): string {
  const kinds = new Set(platforms.map((p) => classifySource(p)))
  if (kinds.size === 0) return ''
  const labels = [...kinds]
    // 按 SOURCE_KIND_META 的固定顺序输出，保证同输入同输出（可断言）
    .sort((a, b) => KIND_ORDER.indexOf(a) - KIND_ORDER.indexOf(b))
    .map((k) => SOURCE_KIND_META[k].label)
  return `本页来源：${labels.join(' · ')}`
}

/** 来源类型的稳定输出顺序（用于 sourceMixLabel 排序） */
const KIND_ORDER: readonly JobSourceKind[] = [
  'curated',
  'official-api',
  'official-rss',
  'partner',
  'community',
  'unknown',
]
