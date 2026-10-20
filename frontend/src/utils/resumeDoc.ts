/**
 * 结构化简历数据模型与模板目录（v1.67.0）
 *
 * ── 设计原则（来源：产品评审 + 开源调研）────────────────────────────
 * 1. **内容 / 模板 / 渲染三层解耦**（借鉴 reactive-resume、best-resume-ever）：
 *    本文件只管「数据长什么样」，`components/ResumeDocPreview.vue` 只管「怎么画」，
 *    模板组件零业务逻辑、零请求 —— 同一份数据换一个 templateId 就换一套版式。
 * 2. **要点必须是 bullet 数组**（`bullets: string[]`），不是一整块富文本。
 *    这是**结构性约束**：防止编辑器退化成富文本编辑器（产品评审「明确不做」清单）。
 * 3. **模板 ID 与后端 `ResumeTemplateCatalog` 一一对应**：新增模板必须同时改两处，
 *    否则前端渲染会落到兜底模板（`fallbackTemplateId`）。
 *
 * ── 为什么这里可以出现 hex ────────────────────────────────────────────
 * 本文件**不出现任何颜色**。纸面配色全部由 `styles/variables.css` 的
 * `--c-paper*` 令牌承担；导出用的独立 HTML 文档在 `utils/resumeExport.ts`
 * （该文件已登记为 hex 例外，理由同 `utils/reportPdf.ts`）。
 */

/** 六个可渲染区块的稳定标识（与后端 ResumeTemplateCatalog.SEC_* 对齐） */
export type ResumeSectionKey =
  | 'summary'
  | 'experience'
  | 'projects'
  | 'education'
  | 'skills'
  | 'extras'

export interface ResumeLink {
  label: string
  url: string
}

export interface ResumeBasics {
  name: string
  title: string
  phone: string
  email: string
  location: string
  links: ResumeLink[]
}

export interface ResumeEducation {
  school: string
  degree: string
  major: string
  start: string
  end: string
  /** GPA / 排名（应届模板会显示，可选） */
  gpa: string
  highlights: string[]
}

export interface ResumeExperience {
  org: string
  role: string
  start: string
  end: string
  location: string
  bullets: string[]
}

export interface ResumeProject {
  name: string
  role: string
  start: string
  end: string
  tech: string[]
  bullets: string[]
}

export interface ResumeSkillGroup {
  category: string
  items: string[]
}

export interface ResumeExtra {
  title: string
  date: string
  desc: string
}

export interface ResumeDoc {
  basics: ResumeBasics
  summary: string
  education: ResumeEducation[]
  experience: ResumeExperience[]
  projects: ResumeProject[]
  skills: ResumeSkillGroup[]
  extras: ResumeExtra[]
}

/** 中文区块标题 */
const SECTION_LABELS_ZH: Record<ResumeSectionKey, string> = {
  summary: '个人简介',
  experience: '工作经历',
  projects: '项目经历',
  education: '教育背景',
  skills: '技能专长',
  extras: '证书与荣誉',
}

/** 英文区块标题（intl-en 模板用；直接用主流招聘系统的标准章节名，命中率最高） */
const SECTION_LABELS_EN: Record<ResumeSectionKey, string> = {
  summary: 'Summary',
  experience: 'Experience',
  projects: 'Projects',
  education: 'Education',
  skills: 'Skills',
  extras: 'Awards & Certifications',
}

export interface ResumeTemplateMeta {
  id: string
  name: string
  scene: string
  /**
   * 版式骨架。三种：
   * - `one-column`：单栏（ATS 最友好）
   * - `two-column`：双栏，主栏在左、侧栏在右
   * - `two-column-reverse`：双栏，侧栏在左、主栏在右（v1.79.0）
   *
   * ⚠️ 无论哪种，**DOM 顺序永远是「页眉 → 主栏 → 侧栏」**，
   * 视觉左右由 CSS 的 `order` 调换。这样 ATS 逐段读取时不会把两栏文字交错串行。
   */
  layout: 'one-column' | 'two-column' | 'two-column-reverse'
  atsLevel: 'HIGH' | 'MEDIUM'
  recommended: boolean
  description: string
  /** 区块渲染顺序（不同模板不同，如应届模板把教育前置） */
  sections: ResumeSectionKey[]
  /** 渲染修饰类（在 ResumeDocPreview 内使用） */
  cssClass: string
  /** 章节标题语言 */
  locale: 'zh' | 'en'
}

/**
 * 模板目录（前端侧）。
 *
 * ⚠️ 与后端 `ResumeTemplateCatalog.TEMPLATES` 必须保持一致：
 * id / name / scene / layout / atsLevel / recommended / sections 七项逐项对齐。
 * 后端通过 `GET /api/resume/templates` 下发元数据，本常量用于
 * ① 首屏渲染不等接口（避免空白闪烁）② 单测断言两侧一致。
 */
export const RESUME_TEMPLATES: ResumeTemplateMeta[] = [
  {
    id: 'classic-single',
    name: '单栏·通用',
    scene: '通用岗位，技术 / 产品 / 运营都适用',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: true,
    description:
      '纯线性排版，无表格、无分栏，机器解析最稳。不确定选哪套时选它。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-classic',
    locale: 'zh',
  },
  {
    id: 'campus-single',
    name: '单栏·应届',
    scene: '校招 / 实习，教育背景前置',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '把教育背景提到最前，突出学校、专业、GPA 与主修课程，适合无正式工作经历的应届生。',
    sections: ['summary', 'education', 'experience', 'projects', 'skills', 'extras'],
    cssClass: 'tpl-campus',
    locale: 'zh',
  },
  {
    id: 'tech-two-col',
    name: '双栏·技术',
    scene: '研发岗，技能栈密集',
    layout: 'two-column',
    atsLevel: 'MEDIUM',
    recommended: false,
    description:
      '左主栏放经历与项目，右侧栏收拢技能、链接与证书。技能条目多时更省空间；投严格 ATS 的系统建议改用「单栏·通用」。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-tech',
    locale: 'zh',
  },
  {
    id: 'intl-en',
    name: '单栏·海外英文',
    scene: '外企 / 海外岗位，英文简历',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '按英文简历惯例：无照片、无性别年龄籍贯，Summary 前置，章节名用 Experience / Education / Skills 直接命中主流招聘系统。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-intl',
    locale: 'en',
  },
  {
    id: 'compact-one-page',
    name: '紧凑·一页',
    scene: '经历较多、需要压到一页',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '缩小字号与间距，把内容压进一页。正文字号低于常规阶梯，阅读舒适度换版面紧凑，正式投递前建议先看预览确认。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-compact',
    locale: 'zh',
  },
  /* ── v1.79.0 扩充：覆盖更多真实求职场景 ─────────────────────────────
   * 选型依据（参考 GitHub 主流简历库的模板分类，实现全部自研）：
   * Reactive Resume 把模板按 one-column / two-column / ATS-safe 三轴划分；
   * best-resume-ever 按「目标岗位」分模板族。这里取「岗位场景」这一轴，
   * 因为用户选模板时想的正是「我投什么岗」，而不是「我要几栏」。 */
  {
    id: 'sidebar-left',
    name: '左栏·侧边栏',
    scene: '技术 / 设计岗，技能与链接较多',
    layout: 'two-column-reverse',
    atsLevel: 'MEDIUM',
    recommended: false,
    description:
      '左侧栏集中放联系方式、技能与证书，右侧主栏留给经历与项目。辨识度高；投严格 ATS 的系统建议改用「单栏·通用」。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-sidebar-left',
    locale: 'zh',
  },
  {
    id: 'timeline',
    name: '时间线·经历',
    scene: '职场人，经历时间连贯',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '每条经历左侧带一条时间轴，按时间顺序读起来更连贯。时间轴只是装饰线，正文仍是纯文本，机器解析不受影响。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-timeline',
    locale: 'zh',
  },
  {
    id: 'academic-cv',
    name: '学术·科研',
    scene: '读研 / 科研 / 高校岗位',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '教育背景前置，突出学校、专业、GPA、主修课程与科研项目，适合继续深造或投科研、高校岗位。',
    sections: ['education', 'summary', 'projects', 'experience', 'skills', 'extras'],
    cssClass: 'tpl-academic',
    locale: 'zh',
  },
  {
    id: 'product-ops',
    name: '产品·运营',
    scene: '产品 / 运营 / 市场岗',
    layout: 'one-column',
    atsLevel: 'HIGH',
    recommended: false,
    description:
      '条目行距更松、要点前的标记更醒目，让量化结果一眼可见。适合强调增长、转化、效率的岗位。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-product',
    locale: 'zh',
  },
  {
    id: 'header-band',
    name: '顶部色带',
    scene: '设计 / 市场岗，想要一点辨识度',
    layout: 'one-column',
    atsLevel: 'MEDIUM',
    recommended: false,
    description:
      '姓名与联系方式放在顶部色带里，整页观感更完整。色带只是背景，文字仍是纯文本；但个别老旧解析系统会丢弃带背景的区域，投严格 ATS 建议改用「单栏·通用」。',
    sections: ['summary', 'experience', 'projects', 'education', 'skills', 'extras'],
    cssClass: 'tpl-band',
    locale: 'zh',
  },
]

/** 兜底模板：未知 / 旧模板 ID 一律回落到 ATS 最友好的一套，不报错 */
export const FALLBACK_TEMPLATE_ID = 'classic-single'

export function findTemplate(id: string | null | undefined): ResumeTemplateMeta {
  return RESUME_TEMPLATES.find((t) => t.id === id) ?? RESUME_TEMPLATES[0]
}

/** 区块标题（按模板语言返回） */
export function sectionLabel(key: ResumeSectionKey, locale: 'zh' | 'en'): string {
  return locale === 'en' ? SECTION_LABELS_EN[key] : SECTION_LABELS_ZH[key]
}

/* ────────────────────────── 规范化 ────────────────────────── */

function str(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return ''
}

function strArray(v: unknown): string[] {
  if (!Array.isArray(v)) return []
  return v.map(str).filter((s) => s.trim() !== '')
}

function objArray<T>(v: unknown, map: (raw: Record<string, unknown>) => T): T[] {
  if (!Array.isArray(v)) return []
  return v
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map(map)
}

/** 空骨架：与后端 `ResumeDocumentService.emptySkeleton()` 结构完全一致 */
export function emptyResumeDoc(): ResumeDoc {
  return {
    basics: { name: '', title: '', phone: '', email: '', location: '', links: [] },
    summary: '',
    education: [],
    experience: [],
    projects: [],
    skills: [],
    extras: [],
  }
}

/**
 * 把「可能是任意形状的 JSON」规范化成 `ResumeDoc`。
 *
 * 为什么必须有这一层：后端存的是 JSON 文本，历史上可能有旧结构、
 * 或用户从别处粘贴进来的残缺数据。渲染组件**不应该**到处写 `?.` 判空 ——
 * 归一化收敛在这里，下游模板组件可以假定字段一定存在。
 */
export function normalizeResumeDoc(raw: unknown): ResumeDoc {
  const base = emptyResumeDoc()
  if (!raw || typeof raw !== 'object') return base
  const src = raw as Record<string, unknown>

  const basicsRaw =
    src.basics && typeof src.basics === 'object'
      ? (src.basics as Record<string, unknown>)
      : {}

  return {
    basics: {
      name: str(basicsRaw.name),
      title: str(basicsRaw.title),
      phone: str(basicsRaw.phone),
      email: str(basicsRaw.email),
      location: str(basicsRaw.location),
      links: objArray(basicsRaw.links, (l) => ({
        label: str(l.label),
        url: str(l.url),
      })).filter((l) => l.label !== '' || l.url !== ''),
    },
    summary: str(src.summary),
    education: objArray(src.education, (e) => ({
      school: str(e.school),
      degree: str(e.degree),
      major: str(e.major),
      start: str(e.start),
      end: str(e.end),
      gpa: str(e.gpa),
      highlights: strArray(e.highlights),
    })),
    experience: objArray(src.experience, (e) => ({
      org: str(e.org),
      role: str(e.role),
      start: str(e.start),
      end: str(e.end),
      location: str(e.location),
      bullets: strArray(e.bullets),
    })),
    projects: objArray(src.projects, (p) => ({
      name: str(p.name),
      role: str(p.role),
      start: str(p.start),
      end: str(p.end),
      tech: strArray(p.tech),
      bullets: strArray(p.bullets),
    })),
    skills: objArray(src.skills, (s) => ({
      category: str(s.category),
      items: strArray(s.items),
    })).filter((s) => s.category !== '' || s.items.length > 0),
    extras: objArray(src.extras, (x) => ({
      title: str(x.title),
      date: str(x.date),
      desc: str(x.desc),
    })).filter((x) => x.title !== ''),
  }
}

/* ────────────────────────── 空章节判定 ────────────────────────── */

/**
 * 该区块是否有可渲染内容。
 *
 * 验收标准要求「空字段时对应章节整段不渲染，不留空标题」，
 * 判定收敛在这里，模板组件只调本函数 —— 避免 5 套模板各写一套判空逻辑后漂移。
 */
export function hasSection(doc: ResumeDoc, key: ResumeSectionKey): boolean {
  switch (key) {
    case 'summary':
      return doc.summary.trim() !== ''
    case 'experience':
      // v1.67.0 QA 修正：此前只认 org 与 bullets，若用户只填了「职位」没填公司，
      // 整段经历会被隐藏 —— 那是内容丢失，不是空标题。判定必须覆盖所有可见字段。
      return doc.experience.some(
        (e) => e.org.trim() !== '' || e.role.trim() !== '' || e.bullets.length > 0
      )
    case 'projects':
      return doc.projects.some((p) => p.name.trim() !== '' || p.bullets.length > 0)
    case 'education':
      // 同理：只填了学历/专业/GPA 的条目也应显示
      return doc.education.some(
        (e) =>
          e.school.trim() !== '' ||
          e.degree.trim() !== '' ||
          e.major.trim() !== '' ||
          e.gpa.trim() !== '' ||
          e.highlights.length > 0
      )
    case 'skills':
      return doc.skills.length > 0
    case 'extras':
      return doc.extras.length > 0
    default:
      return false
  }
}

/** 模板实际会渲染的区块（已剔除空区块，且保持模板定义的顺序） */
export function visibleSections(
  doc: ResumeDoc,
  template: ResumeTemplateMeta
): ResumeSectionKey[] {
  return template.sections.filter((key) => hasSection(doc, key))
}

/** 简历是否有任何内容（用于空态判定：决定显示引导还是预览） */
export function isDocEmpty(doc: ResumeDoc): boolean {
  return (
    doc.basics.name.trim() === '' &&
    doc.summary.trim() === '' &&
    doc.education.length === 0 &&
    doc.experience.length === 0 &&
    doc.projects.length === 0 &&
    doc.skills.length === 0 &&
    doc.extras.length === 0
  )
}

/* ────────────────────────── Markdown 导出 ────────────────────────── */

/**
 * 导出为 Markdown。
 *
 * ⚠️ **刻意不含任何模板信息**：验收标准要求「切换 5 次模板后导出 MD 逐字节一致」。
 * 模板只影响版式，不影响内容 —— 这条约束同时也是「数据零丢失」的回归判据。
 */
export function toMarkdown(doc: ResumeDoc): string {
  const lines: string[] = []
  const b = doc.basics

  if (b.name.trim()) lines.push(`# ${b.name.trim()}`)
  const contact = [b.title, b.phone, b.email, b.location]
    .map((s) => s.trim())
    .filter(Boolean)
  if (contact.length) lines.push(contact.join(' · '))
  if (b.links.length) {
    lines.push(b.links.map((l) => `[${l.label || l.url}](${l.url})`).join(' · '))
  }
  lines.push('')

  if (doc.summary.trim()) {
    lines.push('## 个人简介', '', doc.summary.trim(), '')
  }

  const expBullets = (bullets: string[]) =>
    bullets.length ? bullets.map((x) => `- ${x}`).join('\n') : ''

  if (doc.experience.length) {
    lines.push('## 工作经历', '')
    for (const e of doc.experience) {
      const head = [e.role, e.org].filter(Boolean).join(' · ')
      const period = [e.start, e.end].filter(Boolean).join(' - ')
      lines.push(`### ${head}${period ? `（${period}）` : ''}`)
      if (e.location.trim()) lines.push(e.location.trim())
      const bl = expBullets(e.bullets)
      if (bl) lines.push('', bl)
      lines.push('')
    }
  }

  if (doc.projects.length) {
    lines.push('## 项目经历', '')
    for (const p of doc.projects) {
      const period = [p.start, p.end].filter(Boolean).join(' - ')
      lines.push(`### ${p.name}${p.role ? ` · ${p.role}` : ''}${period ? `（${period}）` : ''}`)
      if (p.tech.length) lines.push(`技术栈：${p.tech.join(' / ')}`)
      const bl = expBullets(p.bullets)
      if (bl) lines.push('', bl)
      lines.push('')
    }
  }

  if (doc.education.length) {
    lines.push('## 教育背景', '')
    for (const e of doc.education) {
      const period = [e.start, e.end].filter(Boolean).join(' - ')
      const deg = [e.degree, e.major].filter(Boolean).join(' · ')
      lines.push(`### ${e.school}${deg ? ` · ${deg}` : ''}${period ? `（${period}）` : ''}`)
      if (e.gpa.trim()) lines.push(`GPA：${e.gpa.trim()}`)
      if (e.highlights.length) lines.push('', expBullets(e.highlights))
      lines.push('')
    }
  }

  if (doc.skills.length) {
    lines.push('## 技能专长', '')
    for (const s of doc.skills) {
      lines.push(`- **${s.category}**：${s.items.join(' / ')}`)
    }
    lines.push('')
  }

  if (doc.extras.length) {
    lines.push('## 证书与荣誉', '')
    for (const x of doc.extras) {
      const date = x.date.trim() ? `（${x.date.trim()}）` : ''
      lines.push(`- **${x.title}**${date}${x.desc.trim() ? `：${x.desc.trim()}` : ''}`)
    }
    lines.push('')
  }

  // 结尾统一收敛为单个换行，避免不同分支拼出不同尾空白
  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}

/** 日期区间展示：两端都空则不显示，只填一端时也不留孤零零的分隔符 */
export function formatPeriod(start: string, end: string): string {
  const s = start.trim()
  const e = end.trim()
  if (!s && !e) return ''
  if (s && e) return `${s} - ${e}`
  return s || e
}
