/**
 * 岗位内容体检（v1.86.0）—— 纯本地规则，零网络、零 AI。
 *
 * 灵感来自 ai-job-search 的 posting integrity gate：岗位文本是第三方内容，
 * 在用户读它、AI 分析它之前，先把「肉眼看不见 / 明显不合常理」的部分摊开。
 *
 * ── 纪律：只陈列证据，不判断真伪 ──────────────────────────────
 * 我们无法核实任何一条岗位，也不能给「可信度分」—— 那属于臆造。
 * 因此这里只做三件事：
 *   1. 用明确定义的规则找出**具体文本**（不可见字符、指令式语句、收费话术…）；
 *   2. 把命中的原文片段（脱敏后）作为证据展示；
 *   3. 由用户自己决定是否继续投递。不拦截、不评分、不写「该岗位是骗局」。
 *
 * ── 为什么不可见字符先剥离再扫描 ──────────────────────────────
 * 零宽字符可以把「忽略以上指令」拆成肉眼与正则都识别不了的形态。
 * 因此所有规则都在**剥掉不可见字符后的副本**上运行，原文仅用于计数与展示。
 */

export type ScanSeverity = 'risk' | 'warn'
export type ScanLevel = 'clean' | 'attention' | 'risk'

export interface ScanSignal {
  /** 稳定标识（测试与 UI key 使用） */
  id: string
  severity: ScanSeverity
  /** 短标题，如「要求求职者付费」 */
  title: string
  /** 为什么值得核实（只陈述规则，不推断动机） */
  detail: string
  /** 命中的原文片段（已截断 / 联系方式已脱敏） */
  evidence: string
}

export interface ScanReport {
  level: ScanLevel
  signals: ScanSignal[]
  summary: string
}

export interface ScanInput {
  title?: string | null
  companyName?: string | null
  description?: string | null
  requirements?: string | null
  salary?: string | null
  applyUrl?: string | null
}

const ZERO_WIDTH = '\u200b\u200c\u200d\u2060\u2061\u2062\u2063\u2064'
const BIDI = '\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069'
const BOM = '\ufeff'
const SOFT_HYPHEN = '\u00ad'
const LINE_SEPARATORS = '\u2028\u2029'
/** 变体选择符：单个是 emoji 样式，连续出现才可能是夹带（每个承载一字节） */
const VARIATION_RUN = /[\uFE00-\uFE0F\u{E0100}-\u{E01EF}]{2,}/gu

const CLIP = 90

function clip(text: string, max = CLIP): string {
  const s = text.replace(/\s+/g, ' ').trim()
  return s.length > max ? `${s.slice(0, max)}…` : s
}

/** 判断是否 Unicode 标签字符（U+E0000–U+E007F）：渲染为空、对模型读作 ASCII */
function isTagChar(cp: number): boolean {
  return cp >= 0xe0000 && cp <= 0xe007f
}

/** 剥掉不可见字符，得到用于规则扫描的副本（不改变展示原文） */
export function stripInvisible(text: string): string {
  let out = ''
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0
    if (
      ZERO_WIDTH.includes(ch) ||
      BIDI.includes(ch) ||
      ch === BOM ||
      ch === SOFT_HYPHEN ||
      LINE_SEPARATORS.includes(ch) ||
      isTagChar(cp)
    ) {
      continue
    }
    out += ch
  }
  // 变体选择符只在扫描副本里清掉；展示时保留 emoji 原样
  return out.replace(/[\uFE00-\uFE0F\u{E0100}-\u{E01EF}]/gu, '')
}

interface InvisibleStats {
  zeroWidth: number
  bidi: number
  bom: number
  tags: number
  variationRuns: number
  total: number
}

function inspectInvisible(text: string): InvisibleStats {
  let zeroWidth = 0
  let bidi = 0
  let bom = 0
  let tags = 0
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0
    if (ZERO_WIDTH.includes(ch)) zeroWidth++
    else if (BIDI.includes(ch)) bidi++
    else if (ch === BOM) bom++
    else if (isTagChar(cp)) tags++
  }
  const variationRuns = (text.match(VARIATION_RUN) || []).length
  return { zeroWidth, bidi, bom, tags, variationRuns, total: zeroWidth + bidi + bom + tags + variationRuns }
}

/** 按句/行切分，返回第一条命中规则的子句 */
function firstClause(text: string, re: RegExp): string | null {
  for (const clause of text.split(/[\n。！？；]/)) {
    const s = clause.trim()
    if (s && re.test(s)) return s
  }
  return null
}

/** 收费话术里常见的否定前置：「无需押金」「不收取任何费用」不应报风险 */
const NEGATED_FEE = /无需|不收|不收取|不缴|不缴纳|免费|无任何|没有任何|不会收取|不收费/

const FEE_RULES: Array<{ re: RegExp; label: string }> = [
  { re: /押金|保证金/, label: '押金 / 保证金' },
  { re: /培训费|培训贷|岗前培训费/, label: '培训费 / 培训贷' },
  { re: /体检费|服装费|工装费|报名费|资料费|建档费|入职手续费/, label: '入职相关费用' },
  // 只认与求职绑定的贷款话术；金融岗位本身写「分期产品」不应命中
  { re: /培训贷|贷款培训|先贷款|贷款上岗/, label: '贷款 / 培训贷' },
  { re: /(?:先|需|须|要)(?:交|缴纳|支付|收取)[^。\n]{0,8}(?:费|钱|元)/, label: '先交钱' },
]

const UNPAID_RULE =
  /(?:无薪|不带薪|免费)(?:试岗|试工|试用)|试岗(?:期间)?(?:不发|没有)工资|试用期(?:不缴|不交|无需缴)(?:纳)?社保|不(?:缴|纳)(?:纳)?社保/

const INJECTION_RULES: Array<{ re: RegExp; label: string }> = [
  { re: /忽略(?:以上|之前|前面|先前|上述)(?:所有|全部)?的?(?:指令|规则|要求|提示|说明)/, label: '忽略既有指令' },
  { re: /ignore\s+(?:all\s+)?(?:previous|above|prior|earlier)\s+(?:instructions?|prompts?)/i, label: 'ignore previous instructions' },
  // 「system prompt」在 AI 岗位描述里是正常术语，不列为可疑
  { re: /你现在是(?:一个|一名)?\s*(?:AI|人工智能|大模型|语言模型|助手|智能体)/i, label: '角色替换' },
  { re: /you\s+are\s+now\s+(?:an?\s+)?(?:ai|assistant|language\s+model)/i, label: '角色替换' },
  { re: /<\|(?:im_start|im_end|system|assistant)\|>|\[INST\]/i, label: '对话模板标记' },
  { re: /^\s*(?:assistant|system)\s*[:：]/im, label: '对话角色前缀' },
]

const MOBILE = /(?<!\d)(1[3-9]\d{9})(?!\d)/
const WECHAT = /(?:微信|weixin|vx|v信|威信)\s*[:：]?\s*([A-Za-z][-_A-Za-z0-9]{5,19})/i
const QQ = /(?:QQ)\s*[:：]?\s*(\d{5,12})/i

const SHORTENERS = new Set([
  't.cn',
  'bit.ly',
  'dwz.cn',
  'url.cn',
  'tinyurl.com',
  'is.gd',
  'suo.im',
  'rebrand.ly',
])

const SALARY_CONTEXT = /薪资|月薪|年薪|年包|工资|待遇|收入/

interface SalaryRange {
  minK: number
  maxK: number
}

function toK(value: number, unit: string): number {
  if (unit === '万') return value * 10
  if (unit === '元') return value / 1000
  return value
}

/** 解析「15-25K」「8千-1.2万」「30-40万/年」为月薪 K；解析不了返回 null */
export function parseSalaryRange(text: string | null | undefined, requireContext = true): SalaryRange | null {
  if (!text) return null
  const re = /(\d+(?:\.\d+)?)\s*([kK千万元]?)\s*[-~—–至到]\s*(\d+(?:\.\d+)?)\s*([kK千万元])/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    const start = Math.max(text.lastIndexOf('。', m.index), text.lastIndexOf('\n', m.index)) + 1
    const stopCandidates = [text.indexOf('。', m.index), text.indexOf('\n', m.index)].filter((i) => i !== -1)
    const end = stopCandidates.length ? Math.min(...stopCandidates) : text.length
    const clause = text.slice(start, end)
    const unit = m[4]
    // 「千 / K」是月薪的强信号，可单独成立；「万」也用于人数与金额，
    // 必须靠「薪资 / 年薪」这类上下文确认，否则「3-5 万用户」会被读成薪资
    const strongUnit = /[kK千]/.test(m[2] || '') || /[kK千]/.test(unit)
    if (requireContext && !strongUnit && !SALARY_CONTEXT.test(clause)) continue
    let min = toK(parseFloat(m[1]), m[2] || unit)
    let max = toK(parseFloat(m[3]), unit)
    if (/年薪|年包|每年|\/年/.test(clause)) {
      min /= 12
      max /= 12
    }
    if (!Number.isFinite(min) || !Number.isFinite(max) || min <= 0 || max <= 0) continue
    return { minK: Math.min(min, max), maxK: Math.max(min, max) }
  }
  return null
}

function maskContact(text: string): string {
  return text
    .replace(MOBILE, (p1) => `${p1.slice(0, 3)}****${p1.slice(7)}`)
    .replace(WECHAT, (_all, id: string) => `微信 ${id.slice(0, 2)}***`)
    .replace(QQ, (_all, id: string) => `QQ ${id.slice(0, 3)}***`)
}

function linkIssue(url: string): string | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return '链接格式不合法'
  }
  if (parsed.protocol !== 'https:') return '申请链接不是 HTTPS'
  if (/^\d{1,3}(?:\.\d{1,3}){3}$/.test(parsed.hostname)) return '申请链接指向 IP 地址'
  if (SHORTENERS.has(parsed.hostname.toLowerCase())) return '申请链接是短链，无法从地址判断去向'
  return null
}

const SEVERITY_RANK: Record<ScanSeverity, number> = { risk: 0, warn: 1 }

/** 同严重度内的稳定输出顺序（与规则的重要性无关，只为可断言） */
const SIGNAL_ORDER = [
  'fee-request',
  'unpaid-trial',
  'prompt-injection',
  'private-contact',
  'invisible-chars',
  'salary-mismatch',
  'link-check',
]

export function scanJobPosting(job: ScanInput): ScanReport {
  const body = [job.description, job.requirements].filter(Boolean).join('\n')
  const visibleBody = stripInvisible(body)
  // 计数必须看原文：先剥再数永远是 0（本文件第一版就栽在这里）
  const rawAllText = [job.title, job.companyName, job.description, job.requirements].filter(Boolean).join('\n')
  const visibleAllText = stripInvisible(rawAllText)
  const signals: ScanSignal[] = []

  // ── 收费 / 押金 ──
  for (const rule of FEE_RULES) {
    const clause = firstClause(visibleBody, rule.re)
    if (!clause || NEGATED_FEE.test(clause)) continue
    signals.push({
      id: 'fee-request',
      severity: 'risk',
      title: '要求求职者付费',
      detail: '正规招聘不向求职者收取押金、培训费、体检费等；遇到先交钱再入职，建议直接放弃并向平台举报。',
      evidence: maskContact(clip(clause)),
    })
    break
  }

  // ── 无薪试岗 / 不缴社保 ──
  const unpaid = firstClause(visibleBody, UNPAID_RULE)
  if (unpaid) {
    signals.push({
      id: 'unpaid-trial',
      severity: 'risk',
      title: '无薪试岗 / 不缴社保',
      detail: '无薪试岗与试用期不缴纳社保不符合劳动法，建议要求书面劳动合同，并以合同约定为准。',
      evidence: clip(unpaid),
    })
  }

  // ── 指令式语句 ──
  for (const rule of INJECTION_RULES) {
    const clause = firstClause(visibleAllText, rule.re)
    if (!clause) continue
    signals.push({
      id: 'prompt-injection',
      severity: 'risk',
      title: 'AI 指令式语句',
      detail:
        '岗位文本里出现了针对 AI 的指令，而不是写给求职者的内容。本站分析不会执行它；建议对照企业官网核对原文。',
      evidence: clip(clause),
    })
    break
  }

  // ── 绕过平台的私下联系 ──
  const contact = firstClause(visibleBody, MOBILE) || firstClause(visibleBody, WECHAT) || firstClause(visibleBody, QQ)
  if (contact) {
    signals.push({
      id: 'private-contact',
      severity: 'warn',
      title: '绕过平台的私下联系',
      detail: '正文直接留了个人手机号 / 微信 / QQ；正规流程通常通过官方渠道沟通，联系前先核验对方身份。',
      evidence: maskContact(clip(contact)),
    })
  }

  // ── 不可见字符 ──
  const hidden = inspectInvisible(rawAllText)
  if (hidden.total > 0) {
    const tampering = hidden.bidi > 0 || hidden.tags > 0 || hidden.variationRuns > 0
    const parts: string[] = []
    if (hidden.zeroWidth) parts.push(`零宽字符 ×${hidden.zeroWidth}`)
    if (hidden.bidi) parts.push(`Bidi 控制符 ×${hidden.bidi}`)
    if (hidden.bom) parts.push(`BOM ×${hidden.bom}`)
    if (hidden.tags) parts.push(`Unicode Tag 字符 ×${hidden.tags}`)
    if (hidden.variationRuns) parts.push(`变体选择符连续段 ×${hidden.variationRuns}`)
    signals.push({
      id: 'invisible-chars',
      severity: tampering ? 'risk' : 'warn',
      title: '正文含不可见字符',
      detail:
        '这些字符肉眼看不见，常被用来夹带隐藏内容或拆分关键词；描述与要求的展示原文未改动，建议对照企业官网核对。',
      evidence: parts.join(' · '),
    })
  }

  // ── 薪资前后不一致 ──
  const fieldRange = parseSalaryRange(job.salary, false)
  const textRange = parseSalaryRange(visibleBody)
  if (fieldRange && textRange) {
    const overlap =
      Math.min(fieldRange.maxK, textRange.maxK) >= Math.max(fieldRange.minK, textRange.minK)
    if (!overlap) {
      const clause = firstClause(visibleBody, SALARY_CONTEXT) || visibleBody
      signals.push({
        id: 'salary-mismatch',
        severity: 'warn',
        title: '薪资前后不一致',
        detail: '岗位字段里的薪资与正文写的区间没有重叠，签合同前请确认以哪个为准。',
        evidence: `岗位字段「${clip(job.salary || '', 30)}」 · 正文「${clip(clause, 50)}」`,
      })
    }
  }

  // ── 申请链接 ──
  const url = (job.applyUrl || '').trim()
  if (url) {
    const issue = linkIssue(url)
    if (issue) {
      signals.push({
        id: 'link-check',
        severity: 'warn',
        title: '申请链接需要核实',
        detail: '申请入口是投递时最容易出错的一环，先确认域名属于该企业再点击。',
        evidence: `${issue}：${clip(url, 60)}`,
      })
    }
  }

  signals.sort((a, b) => {
    const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]
    return bySeverity !== 0 ? bySeverity : SIGNAL_ORDER.indexOf(a.id) - SIGNAL_ORDER.indexOf(b.id)
  })

  const hasRisk = signals.some((s) => s.severity === 'risk')
  const level: ScanLevel = hasRisk ? 'risk' : signals.length ? 'attention' : 'clean'
  const summary =
    level === 'clean'
      ? '未发现常见的可疑表述'
      : level === 'risk'
        ? `发现 ${signals.length} 处风险表述`
        : `发现 ${signals.length} 处需要留意`

  return { level, signals, summary }
}

/** 固定顺序的「无信号」说明，供 UI 在 clean 时展示，避免各页面各写一句 */
export const SCAN_DISCLAIMER = '只做文本规则检查，不判断岗位真伪；投递前请通过企业官网核实。'
