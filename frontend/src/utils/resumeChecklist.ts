/**
 * 简历规则化清单条目（vNext 叙事转向 · 纯前端可算的确定性信号）。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * 产品实测证明：简历分析的总分对「同档内容的改进」不敏感，跨轮噪声 ≈ 信号
 * （同一份简历的分数在 65~75 间波动），以「分数涨跌」为主叙事不可靠。
 * 叙事转向后，主视觉是四个维度各自的问题清单。其中有三类信号**不依赖 AI、
 * 从简历原文即可确定性计算**，且比模型输出更稳定：
 *
 * 1. 量化数字计数：数字 + 单位 / 百分比 / 时间指标（如 `120ms`、`85%`、`Q3`、`3倍`；
 *    年份与「N 年经验」类资历年限排除，见 {@link countQuantifiedStats}）；
 * 2. 主导动词统计：强动词（主导/设计/推动/独立/负责/搭建/优化）vs 弱动词（参与/配合/协助/了解）；
 * 3. 技术栈模糊检测：「了解……等」「熟悉……等」「相关技术」这类含糊表述。
 *
 * 本模块是**纯函数**（不碰 DOM、不发请求、不依赖 Vue），全部可单测。
 * 条目必须携带「缺什么 + 怎么补」的可执行建议，禁止输出空泛的「建议增强」。
 *
 * ── 注入位置 ─────────────────────────────────────────────────────────
 * 条目注入「含金量」维度（后端维度名实际为「项目含金量」，用
 * {@link checklistForDimension} 按包含关系匹配）的清单**最前面**；
 * 简历原文不可用时（resumeText 为空，如旧数据未落库正文）返回空数组，
 * 调用方自动退化为仅展示 AI 建议——**无清单条目 ≠ 0 条问题**，空态走
 * `utils/format.ts` 的 EMPTY 口径，由调用方渲染。
 */

/** 条目命中的规则标识 */
export type ResumeChecklistRule = 'quantify' | 'weak-verb' | 'vague-tech'

/** 一条规则化清单条目：problem 说清「缺什么」，advice 说清「怎么补」 */
export interface ResumeChecklistItem {
  rule: ResumeChecklistRule
  /** 缺什么（可含命中计数等事实） */
  problem: string
  /** 怎么补（必须给出可照抄的改法示例，不允许空泛） */
  advice: string
}

/* ─────────────────────────────────────────────────────────────
   信号一：量化数字计数
   ───────────────────────────────────────────────────────────── */

/**
 * 数字 + 单位的量化指标。
 * 单位表刻意覆盖 性能（ms/秒）、规模（万/亿/GB/人/次）、占比（%）与时间（个月/周/天），
 * 并包含「个/条/款/种/页/份」等常见成果量词。命中 `Q1~Q4`（季度指标）的
 * 形态不带前置数字，由 {@link QUARTER_RE} 单独统计。
 *
 * ⚠️ 已知权衡（v1.73.0 QA 变异测试评审）：「N 个项目」这类**项目数**既是背景、
 * 也常是规模指标（「主导 3 个项目从 0 到 1」），收窄「个」的计数极易误杀真实的
 * 规模成果，本期**保留计数、不做排除**；若后续出现普遍「凑项目数」的刷法，
 * 再考虑结合动词强度做联合判定。TODO(vNext)：项目数背景 / 规模二象性。
 */
const QUANT_RE = new RegExp(
  String.raw`\d+(?:\.\d+)?\s*(?:%|％|ms|毫秒|秒|min\b|s\b|分钟|小时|天|日|周|个月|月|年|倍|万|亿|k\b|K\b|w\b|GB|MB|KB|TB|km\b|kg\b|人|次|家|款|台|页|篇|份|条|个|种|道|题|间|层|笔|单|公里)`,
  'g',
)

/** 季度指标：Q1~Q4（前后不能紧贴字母数字，避免误命中 `Q3D` 这类串） */
const QUARTER_RE = /(?<![A-Za-z0-9])Q[1-4](?![A-Za-z0-9])/g

/** 年份（19xx / 20xx + 年）：是时间锚点而非成果指标，不计入量化数据 */
const YEAR_RE = /\b(?:19|20)\d{2}\s*年/g

/**
 * 「N 年（经验 / 经历 / 从业 / 工龄）」类**资历背景**年限：如「3 年后端开发经验」
 * 「5 年 Java 经验」——数字 + 年在这里是背景信息，不是「成果有数字」的证据，
 * 不计入量化数据（否则目标用户——恰好缺量化成果的简历——的量化提醒永远不触发）。
 *
 * v1.74.0：锚点表去掉了 `开发` 与 `工作`。此前「稳定运行 3 年工作无故障」
 * 「近 3 年工作无重大事故」这类**成果语境**（数字 + 年描述的是稳定性/可靠性成果）
 * 会被 `工作` 这个词误判成资历背景而排除，若全文只有这一处量化，就会误报
 * 「全文 0 处量化数据」——这是把用户的真实成果说成没有，比漏报更伤信任。
 * 代价是「5 年 Java 开发」这种不带「经验 / 经历 / 从业 / 工龄」的写法会重新计入量化，
 * 取舍原则：**宁可多计，不可误报「0 处量化」**。
 *
 * 前瞻只用于识别模式；排除区间仅覆盖 `N 年` 本身（lookahead 不消耗字符），
 * 因此「3 年内完成 5 个版本迭代」中的「5 个」照常计入，不会被连带误杀；
 * 「2 年内 3 项开发经历」里的「3 项」同理（若前瞻消费字符，会把「3 项」一起吞掉）。
 */
const EXPERIENCE_RE = /\d+(?:\.\d+)?\s*年(?=的?[^。；\n]{0,8}(?:经验|经历|从业|工龄))/g

/**
 * 「N 年内 / N 年间 / N 年里」时长框架：是时间**范围**而非成果指标
 * （「3 年内完成 5 个版本迭代」中成果是「5 个版本」，不是「3 年」）。
 * 前瞻仅看紧跟的「内 / 间 / 里」，不会误伤「N 年经验」以外的正常年份表述。
 */
const DURATION_FRAME_RE = /\d+(?:\.\d+)?\s*年(?=[内间里])/g

/**
 * 统计简历原文里的量化数据处数。
 *
 * 两类「非成果类」数字会被排除：
 * - 年份（如「2023 年毕业」）：时间锚点，是背景信息而非「成果有数字」的证据；
 * - 资历年限（如「3 年后端开发经验」）：资历背景，不是规模 / 性能 / 占比指标；
 * - 时长框架（如「3 年内完成 5 个版本迭代」里的「3 年」）：时间范围本身，
 *   真成果（「5 个版本」）照常计入。
 *
 * @param text 简历原文（空串安全，返回 0）
 */
export function countQuantifiedStats(text: string): number {
  if (!text) return 0
  // 先收集「非成果类」数字的区间，量化匹配落在这些区间内的一律不计
  const excludeSpans: Array<[number, number]> = []
  for (const m of text.matchAll(YEAR_RE)) {
    excludeSpans.push([m.index!, m.index! + m[0].length])
  }
  for (const m of text.matchAll(EXPERIENCE_RE)) {
    excludeSpans.push([m.index!, m.index! + m[0].length])
  }
  for (const m of text.matchAll(DURATION_FRAME_RE)) {
    excludeSpans.push([m.index!, m.index! + m[0].length])
  }
  const inExcludeSpan = (idx: number) => excludeSpans.some(([s, e]) => idx >= s && idx < e)

  let count = 0
  for (const m of text.matchAll(QUANT_RE)) {
    if (!inExcludeSpan(m.index!)) count++
  }
  for (const m of text.matchAll(QUARTER_RE)) {
    if (!inExcludeSpan(m.index!)) count++
  }
  return count
}

/* ─────────────────────────────────────────────────────────────
   信号二：主导动词统计
   ───────────────────────────────────────────────────────────── */

/** 强动词：体现「你做了什么、推动了什么」 */
const STRONG_VERBS: readonly string[] = ['主导', '设计', '推动', '独立', '负责', '搭建', '优化']
/** 弱动词：体现「你在场」，但看不出你做了什么 */
const WEAK_VERBS: readonly string[] = ['参与', '配合', '协助', '了解']

/** 强 / 弱动词出现次数 */
export interface VerbStats {
  strong: number
  weak: number
}

/**
 * 统计强动词与弱动词的出现次数（简单包含计数，重复出现各计 1 次）。
 *
 * @param text 简历原文（空串安全，返回 `{ strong: 0, weak: 0 }`）
 */
export function countActionVerbs(text: string): VerbStats {
  const countAll = (verbs: readonly string[]): number =>
    verbs.reduce((n, v) => n + text.split(v).length - 1, 0)
  return { strong: countAll(STRONG_VERBS), weak: countAll(WEAK_VERBS) }
}

/* ─────────────────────────────────────────────────────────────
   信号三：技术栈模糊检测
   ───────────────────────────────────────────────────────────── */

/**
 * 含糊表述特征：句中出现「了解 / 熟悉 …… 等」的开放式罗列，
 * 或「相关技术」这种不落到具体技术的收尾。
 */
const VAGUE_PATTERNS: readonly RegExp[] = [
  /了解[^。；;！!\n]{0,16}等/,
  /熟悉[^。；;！!\n]{0,16}等/,
  /相关技术/,
]

/**
 * 是否命中技术栈含糊表述。
 *
 * @param text 简历原文（空串安全，返回 false）
 */
export function hasVagueTechStatement(text: string): boolean {
  return VAGUE_PATTERNS.some((re) => re.test(text))
}

/* ─────────────────────────────────────────────────────────────
   清单条目生成
   ───────────────────────────────────────────────────────────── */

/**
 * 从简历原文生成规则化清单条目（确定性输出：同一段原文永远得到同一份清单）。
 *
 * <p>条目顺序：量化数据 → 弱动词 → 技术栈含糊。空文本 / 纯空白文本返回空数组
 * （没有内容就不产生条目，避免对空输入输出「0 处量化数据」这类无意义告警）。
 *
 * @param resumeText 简历原文；不可用时传 `''`
 */
export function buildResumeChecklist(resumeText: string): ResumeChecklistItem[] {
  const text = typeof resumeText === 'string' ? resumeText : ''
  if (!text.trim()) return []

  const items: ResumeChecklistItem[] = []

  const quantified = countQuantifiedStats(text)
  if (quantified === 0) {
    items.push({
      rule: 'quantify',
      problem: '全文 0 处量化数据',
      advice:
        '给核心成果补上性能 / 规模 / 占比类指标，例如「接口 P99 响应从 800ms 优化到 120ms」' +
        '「日承载 5 万次请求」「转化率提升 15%」——有数字的成果才可被验证。',
    })
  }

  const { strong, weak } = countActionVerbs(text)
  if (weak > strong) {
    items.push({
      rule: 'weak-verb',
      problem: `弱动词占主导：参与 / 配合 / 协助 / 了解 共 ${weak} 处，主导 / 设计 / 推动等仅 ${strong} 处`,
      advice:
        '把「参与 / 配合 / 协助」换成你实际做的事——写了什么、推动了什么、独立交付了什么。' +
        '例如「参与了订单系统开发」改为「独立完成订单支付模块，覆盖 3 种支付渠道，上线后支付成功率提升至 99.9%」。',
    })
  }

  if (hasVagueTechStatement(text)) {
    items.push({
      rule: 'vague-tech',
      problem: '技术栈表述含糊：出现「了解……等」「熟悉……等」「相关技术」这类说法',
      advice:
        '把模糊罗列改成「具体技术 + 用它做过什么 + 量级」，例如「了解 Kafka 等消息队列」' +
        '改为「用 Kafka 做过订单事件削峰，峰值日消费 2000 万条」。写不出使用场景的技术直接删掉，避免面试被追问时露怯。',
    })
  }

  return items
}

/**
 * 「含金量」维度的判定关键词。后端维度名实际为「项目含金量」，
 * 故按包含关系匹配而不是全等。
 */
export const GOLD_DIMENSION_KEYWORD = '含金量'

/**
 * 该维度名是否为「含金量」维度（唯一注入规则化条目的维度）。
 *
 * @param name 维度名（后端 `dimensions[].name`）
 */
export function isGoldDimension(name: string): boolean {
  return name.replace(/\s/g, '').includes(GOLD_DIMENSION_KEYWORD)
}

/**
 * 取某维度应注入的规则化清单条目：只有「含金量」维度返回条目，其余维度恒为空数组。
 *
 * <p>把「注入到哪个维度」的判定收敛在这里（而不是散落在各视图），
 * 与 `utils/reportView.ts::canRenderDimensions` 的单一来源原则同向。
 *
 * @param name 维度名
 * @param resumeText 简历原文；不可用时传 `''`（返回空数组，调用方退化为仅 AI 建议）
 */
export function checklistForDimension(name: string, resumeText: string): ResumeChecklistItem[] {
  return isGoldDimension(name) ? buildResumeChecklist(resumeText) : []
}
