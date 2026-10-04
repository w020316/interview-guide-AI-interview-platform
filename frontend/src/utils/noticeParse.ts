/**
 * 投递通知文本解析（第三批 E）—— **纯本地、零网络、零 AI**。
 *
 * <p><b>合规与确定性（R2）</b>：本模块不 import `api`、不发起任何请求、不调用 AI。
 * 解析仅靠本地正则/关键词，因此「解析阶段 Network 面板零 XHR」在实现上成立。
 *
 * <p><b>不猜假值</b>：任一项无法识别时返回 `null`，并给出 {@link NoticeConfidence}
 * 与命中的 {@link NoticeParse.matched} 规则说明；完全无法识别时 `confidence==='none'`，
 * 由调用方展示「未识别」，**绝不编造公司/岗位/时间**。
 *
 * <p>识别结果只进入**可编辑预览**，用户确认后才写入（不静默改状态）。
 */

/** 解析置信度：high(公司+岗位+阶段齐全) / low(识别到部分) / none(未识别) */
export type NoticeConfidence = 'high' | 'low' | 'none'

export interface NoticeParse {
  company: string | null
  /** 岗位名称 */
  title: string | null
  /** 归一化到投递台账状态（JobApplicationEntity.STATUS_*） */
  stage: string | null
  /** 阶段中文标签 */
  stageLabel: string | null
  /** 面试时间（`YYYY-MM-DDTHH:mm`）；无则不猜 */
  interviewAt: string | null
  confidence: NoticeConfidence
  /** 命中的规则说明（可解释，供预览展示） */
  matched: string[]
}

/**
 * 阶段规则（**顺序即优先级**，具体在前、宽泛在后，避免「已投递」吃掉「面试邀约」）。
 */
const STAGE_RULES: Array<{ status: string; label: string; re: RegExp }> = [
  { status: 'OFFER', label: '已拿 Offer', re: /(录用|录取|入职邀请|发放[^\n]{0,6}offer|恭喜[^\n]{0,8}通过|offer)/i },
  { status: 'INTERVIEW', label: '面试中', re: /(面试(邀约|邀请|通知|安排)|邀请[^\n]{0,6}面试|面试时间|参加面试|邀您面试|面试机会|interview)/i },
  { status: 'REJECTED', label: '已淘汰', re: /(遗憾|不合适|未通过|未能通过|感谢信|不予考虑|rejected|not\s+move\s+forward|will\s+not\s+be\s+moving)/i },
  { status: 'REPLIED', label: '有回复', re: /(已回复|有回复|回复了|hr[^\n]{0,6}回复|replied)/i },
  { status: 'VIEWED', label: '已查看', re: /(已查看|查看[^\n]{0,6}简历|简历[^\n]{0,6}被查看|viewed)/i },
  { status: 'APPLIED', label: '已投递', re: /(投递成功|申请成功|已投递|感谢[^\n]{0,8}投递|application\s+received|thank\s+you\s+for\s+applying|we\s+received\s+your\s+application)/i },
]

function emptyResult(): NoticeParse {
  return { company: null, title: null, stage: null, stageLabel: null, interviewAt: null, confidence: 'none', matched: [] }
}

function clean(s: string): string {
  return s.replace(/^[\s：:，,。.、\-—]+/, '').replace(/[\s：:，,。.、\-—]+$/, '').trim()
}

function detectStage(text: string): { status: string; label: string } | null {
  for (const r of STAGE_RULES) {
    if (r.re.test(text)) return { status: r.status, label: r.label }
  }
  return null
}

const ROLE_KEYWORD = /工程师|开发|架构师|经理|专员|实习生|设计师|分析师|算法|测试|前端|后端|全栈|运维|产品|运营/

function detectTitle(text: string): string | null {
  // ① 书名号/引号内、且含岗位关键词的短语（如「Java 后端工程师」）
  const bracket = /[「『“"]([^「」『』“”、\n]{2,30})[」』”"]/.exec(text)
  if (bracket && ROLE_KEYWORD.test(bracket[1])) return clean(bracket[1])

  // ② 显式标注：岗位：xxx / 职位：xxx
  const labeled = /(?:岗位|职位)\s*[:：]\s*([^\n，。；;|]{2,40})/.exec(text)
  if (labeled) return clean(labeled[1])

  // ③ 「应聘/申请/投递 ... 岗位」
  const applied = /(?:应聘|申请|投递)(?:的)?\s*([^\n，。；;|「」]{2,30}?)(?:岗位|职位)/.exec(text)
  if (applied) return clean(applied[1])

  // ④ 英文：... for the Backend Engineer interview at ...
  //    必须紧跟 interview/position/role 等词，否则惰性捕获会把「interview」也吞进来
  const en = /\bfor\s+(?:the\s+)?([A-Za-z][A-Za-z0-9+#./\- ]{1,40}?)\s+(?:interview|position|role|opening|opportunity)\b/i.exec(text)
  if (en) return clean(en[1])

  // ⑤ 英文：... the position of Senior Data Engineer at ...
  const posOf = /\bposition\s+of\s+([A-Za-z][A-Za-z0-9+#./\- ]{1,40}?)\s+(?:at|with|role|on)\b/i.exec(text)
  if (posOf) return clean(posOf[1])

  // ⑥ 兜底：含岗位关键词的中文短语
  const role = /([\u4e00-\u9fa5A-Za-z0-9+/ ]{2,30}?(?:工程师|开发|架构师|经理|专员|实习生|设计师|分析师|算法|测试|前端|后端|全栈|运维|产品|运营))/.exec(text)
  if (role) return clean(role[1])

  return null
}

function detectCompany(text: string): string | null {
  // ① 中文书名号/方括号包裹的公司名：如 【字节跳动】
  const bracket = /[【\[]([^【】\[\]\n]{2,30})[】\]]/.exec(text)
  if (bracket) {
    const name = clean(bracket[1])
    if (name && !ROLE_KEYWORD.test(name)) return name
  }

  // ② 带后缀的中文公司名
  const zh = /([\u4e00-\u9fa5A-Za-z0-9]{2,20}?(?:有限公司|股份有限公司|集团有限公司|集团|网络科技|信息技术|软件公司))/.exec(text)
  if (zh) return clean(zh[1])

  // ③ 英文：at Acme Corp.（仅取连续首字母大写的词段，避免把后面的时间/单词一起吞掉）
  const en = /\bat\s+([A-Z][A-Za-z0-9&.\-]*(?:\s+[A-Z][A-Za-z0-9&.\-]*){0,3})/.exec(text)
  if (en) return clean(en[1].replace(/[.,。]+$/, ''))

  // ④ 显式：来自/公司：xxx
  const labeled = /(?:来自|公司)\s*[:：]?\s*([^\n，。；;：:]{2,30})/.exec(text)
  if (labeled) return clean(labeled[1])

  return null
}

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** 解析日期（`yyyy-M-D` / `yyyy/M/D` / `yyyy年M月D日` / `M月D日`）；无法识别返回 null */
function detectDate(text: string): { y: number; mo: number; d: number } | null {
  const full = /(\d{4})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*日?/.exec(text)
  if (full) {
    const y = Number(full[1])
    const mo = Number(full[2])
    const d = Number(full[3])
    if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) return { y, mo, d }
  }
  const md = /(\d{1,2})\s*月\s*(\d{1,2})\s*日/.exec(text)
  if (md) {
    const mo = Number(md[1])
    const d = Number(md[2])
    if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) return { y: new Date().getFullYear(), mo, d }
  }
  return null
}

/** 归一时刻（处理 AM/PM，越界返回 null）。 */
function normalizeHour(hh: number, mm: number, marker: string | null): { hh: number; mm: number } | null {
  let h = hh
  if (marker === 'PM' && h < 12) h += 12
  if (marker === 'AM' && h === 12) h = 0
  if (h < 0 || h > 23 || mm < 0 || mm > 59) return null
  return { hh: h, mm }
}

/**
 * 解析**明确时刻**（`H:MM` / `H:MM AM|PM` / `H时` / `H时M分`）。
 *
 * <p>只认「明确写出的时刻」——没有时刻就返回 null，**绝不**用 00:00 之类补齐。
 */
function detectTime(text: string): { hh: number; mm: number } | null {
  const m = /(?:(AM|PM)\s*)?(\d{1,2})\s*[:：]\s*(\d{1,2})(?:\s*(AM|PM))?/i.exec(text)
  if (m) {
    return normalizeHour(Number(m[2]), Number(m[3]), (m[1] || m[4] || '').toUpperCase() || null)
  }
  const cn = /(\d{1,2})\s*时\s*(?:(半)|(\d{1,2})\s*分)?/.exec(text)
  if (cn) {
    const mm = cn[2] === '半' ? 30 : cn[3] ? Number(cn[3]) : 0
    return normalizeHour(Number(cn[1]), mm, null)
  }
  return null
}

/**
 * 解析面试时间为 `YYYY-MM-DDTHH:mm`；纯本地。
 *
 * <p>**必须同时具备「日期」与「明确时刻」**，否则返回 null——宁可判为未识别让用户手填，
 * 也不虚构一个时间（与「不猜假值」红线一致）。
 */
function detectInterviewAt(text: string): string | null {
  // 归一全角冒号与中文时段词，便于统一正则
  const t = text.replace(/：/g, ':').replace(/下午/g, 'PM ').replace(/上午/g, 'AM ').replace(/晚上/g, 'PM ')
  const date = detectDate(t)
  const time = detectTime(t)
  if (!date || !time) return null
  return `${date.y}-${pad2(date.mo)}-${pad2(date.d)}T${pad2(time.hh)}:${pad2(time.mm)}`
}

/**
 * 解析一段通知文本（邮件/站内信/短信均可）。
 *
 * @returns 结构化的 {@link NoticeParse}；无法识别时各项为 `null` 且 `confidence==='none'`
 */
export function parseNotice(raw: string): NoticeParse {
  const text = typeof raw === 'string' ? raw : ''
  if (!text.trim()) return emptyResult()

  const matched: string[] = []
  const stageHit = detectStage(text)
  const company = detectCompany(text)
  const title = detectTitle(text)
  const interviewAt = detectInterviewAt(text)

  if (stageHit) matched.push(`阶段：${stageHit.label}`)
  if (company) matched.push(`公司：${company}`)
  if (title) matched.push(`岗位：${title}`)
  if (interviewAt) matched.push(`时间：${interviewAt.replace('T', ' ')}`)

  const fieldCount = [company, title, stageHit].filter(Boolean).length
  const confidence: NoticeConfidence =
    company && title && stageHit ? 'high' : fieldCount >= 1 || interviewAt ? 'low' : 'none'

  return {
    company,
    title,
    stage: stageHit?.status ?? null,
    stageLabel: stageHit?.label ?? null,
    interviewAt,
    confidence,
    matched,
  }
}

/** 是否识别到任何可用字段（供 UI 决定是否可进入预览） */
export function hasAnyField(p: NoticeParse): boolean {
  return p.confidence !== 'none' && Boolean(p.company || p.title || p.stage || p.interviewAt)
}
