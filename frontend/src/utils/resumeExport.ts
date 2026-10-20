/**
 * 简历导出：独立 HTML 文档 + 浏览器打印为 PDF（v1.67.0）
 *
 * ── 为什么走「打印」而不是 jsPDF / html2canvas ────────────────────────
 * 产品评审与开源调研（reactive-resume / best-resume-ever）结论一致：
 * 简历必须保留**真文本层**，否则 ATS（简历解析系统）读到的是一张图片，等于把功能做废。
 * 浏览器 print-to-PDF 产出的是矢量文本，且零依赖、零内存成本
 * （后端 Render 免费层 512MB，不适合跑 Playwright / Flying Saucer 渲染）。
 *
 * ── 为什么导出文档必须自包含 ──────────────────────────────────────────
 * 用户会把导出的 HTML 存到本地、发给别人、或直接打印。
 * 它一旦脱离应用，就拿不到 `styles/variables.css` 的 CSS 变量，
 * 因此这里**必须内联具体色值** —— 本文件已登记在 `designGuards.test.ts`
 * 的 `HEX_ALLOWED_FILES` 中（理由同 `utils/reportPdf.ts`：脱离应用主题的独立文档）。
 *
 * ⚠️ 所有用户输入都必须经过 {@link esc} 转义：
 * 简历内容里出现 `</style>` 或 `<script>` 会直接破坏导出文档结构。
 */

import type { ResumeDoc, ResumeSectionKey } from './resumeDoc'
import {
  findTemplate,
  formatPeriod,
  sectionLabel,
  visibleSections,
} from './resumeDoc'

/** HTML 转义（导出文档的唯一注入防线） */
function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function bullets(list: string[]): string {
  if (!list.length) return ''
  return `<ul class="bullets">${list.map((b) => `<li>${esc(b)}</li>`).join('')}</ul>`
}

/** 单个区块的 HTML —— 与 `components/ResumeSectionBlock.vue` 的结构保持一致 */
function renderSection(key: ResumeSectionKey, doc: ResumeDoc, locale: 'zh' | 'en'): string {
  const title = `<h2 class="sec-title">${esc(sectionLabel(key, locale))}</h2>`
  let body = ''

  switch (key) {
    case 'summary':
      body = `<p class="summary">${esc(doc.summary)}</p>`
      break

    case 'experience':
      body = doc.experience
        .map((e) => {
          const head = `<div class="entry-head"><span class="entry-main">${esc(
            e.role || e.org
          )}</span><span class="entry-date">${esc(formatPeriod(e.start, e.end))}</span></div>`
          const sub =
            (e.role && e.org) || e.location
              ? `<div class="entry-sub">${e.role && e.org ? `<span>${esc(e.org)}</span>` : ''}${
                  e.location ? `<span>${esc(e.location)}</span>` : ''
                }</div>`
              : ''
          return `<div class="entry">${head}${sub}${bullets(e.bullets)}</div>`
        })
        .join('')
      break

    case 'projects':
      body = doc.projects
        .map((p) => {
          const head = `<div class="entry-head"><span class="entry-main">${esc(
            p.name
          )}${p.role ? ` · ${esc(p.role)}` : ''}</span><span class="entry-date">${esc(
            formatPeriod(p.start, p.end)
          )}</span></div>`
          const tech = p.tech.length
            ? `<div class="entry-sub"><span>${esc(p.tech.join(' / '))}</span></div>`
            : ''
          return `<div class="entry">${head}${tech}${bullets(p.bullets)}</div>`
        })
        .join('')
      break

    case 'education':
      body = doc.education
        .map((e) => {
          const head = `<div class="entry-head"><span class="entry-main">${esc(
            e.school
          )}</span><span class="entry-date">${esc(formatPeriod(e.start, e.end))}</span></div>`
          const deg = [e.degree, e.major].map((s) => s.trim()).filter(Boolean).join(' · ')
          const sub =
            deg || e.gpa
              ? `<div class="entry-sub">${deg ? `<span>${esc(deg)}</span>` : ''}${
                  e.gpa ? `<span>GPA ${esc(e.gpa)}</span>` : ''
                }</div>`
              : ''
          return `<div class="entry">${head}${sub}${bullets(e.highlights)}</div>`
        })
        .join('')
      break

    case 'skills':
      // 分类为空时不输出空标签列（与 ResumeSectionBlock 保持一致）
      body = doc.skills
        .map(
          (s) =>
            `<div class="skill-row">${
              s.category ? `<span class="skill-cat">${esc(s.category)}</span>` : ''
            }<span class="skill-items${s.category ? '' : ' is-full'}">${esc(
              s.items.join(' / ')
            )}</span></div>`
        )
        .join('')
      break

    case 'extras':
      body = `<ul class="bullets extras">${doc.extras
        .map(
          (x) =>
            `<li><span class="extra-title">${esc(x.title)}</span>${
              x.date ? `<span class="entry-date">${esc(x.date)}</span>` : ''
            }${x.desc ? `<span class="extra-desc">${esc(x.desc)}</span>` : ''}</li>`
        )
        .join('')}</ul>`
      break
  }

  return `<section class="doc-sec">${title}${body}</section>`
}

/** 导出文档的内联样式（与预览组件视觉一致；尺寸用 mm 以便打印精确） */
const EXPORT_CSS = `
@page { size: A4; margin: 0; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: #ffffff; }
body {
  font-family: -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei",
    "Source Han Sans SC", "Noto Sans CJK SC", sans-serif;
  color: #1c1917;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}
.resume-doc {
  width: 210mm;
  min-height: 297mm;
  padding: 14mm 16mm;
  background: #ffffff;
  --fs-name: 26px; --fs-sec: 13px; --fs-body: 12.5px; --fs-date: 12px;
  --lh: 1.58; --gap-sec: 24px; --gap-title: 10px; --gap-entry: 16px;
  --gap-list: 8px; --gap-li: 4px;
}
.doc-head { margin-bottom: 20px; break-inside: avoid; }
.doc-name {
  margin: 0; font-size: var(--fs-name); font-weight: 700; line-height: 1.15;
  letter-spacing: 0.01em; color: #1c1917;
}
.doc-title { margin: 4px 0 0; font-size: var(--fs-body); font-weight: 600; color: #0f766e; }
.doc-contact, .doc-links {
  display: flex; flex-wrap: wrap; gap: 4px 14px; margin: 8px 0 0; padding: 0;
  list-style: none; font-size: var(--fs-date); color: #57534e;
}
.doc-contact li + li::before {
  content: ""; display: inline-block; width: 1px; height: 10px;
  margin-right: 14px; vertical-align: -1px; background: #e0ddd9;
}
.doc-links li { color: #0f766e; }
.doc-sec { margin-bottom: var(--gap-sec); break-inside: avoid; page-break-inside: avoid; }
.sec-title {
  margin: 0 0 var(--gap-title); font-size: var(--fs-sec); font-weight: 700;
  line-height: 1.3; letter-spacing: 0.04em; color: #0f766e;
  break-after: avoid; page-break-after: avoid;
}
.sec-title::after { content: ""; display: block; height: 1px; margin-top: 6px; background: #e0ddd9; }
.summary { margin: 0; font-size: var(--fs-body); line-height: var(--lh); color: #1c1917; }
.entry { margin-bottom: var(--gap-entry); break-inside: avoid; page-break-inside: avoid; }
.entry:last-child { margin-bottom: 0; }
.entry-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
.entry-main { font-size: var(--fs-body); font-weight: 700; color: #1c1917; }
.entry-date { flex-shrink: 0; font-size: var(--fs-date); font-variant-numeric: tabular-nums; color: #57534e; }
.entry-sub { display: flex; flex-wrap: wrap; gap: 4px 10px; margin-top: 2px; font-size: var(--fs-date); color: #57534e; }
.bullets { margin: var(--gap-list) 0 0; padding-left: 16px; font-size: var(--fs-body); line-height: var(--lh); color: #1c1917; }
.bullets li { margin-bottom: var(--gap-li); orphans: 2; widows: 2; }
.bullets li:last-child { margin-bottom: 0; }
.skill-row { display: grid; grid-template-columns: minmax(64px, auto) 1fr; gap: 10px; margin-bottom: var(--gap-li); font-size: var(--fs-body); line-height: var(--lh); }
.skill-cat { font-weight: 700; }
.skill-items.is-full { grid-column: 1 / -1; }
.extras { list-style: none; padding-left: 0; }
.extras li { display: flex; flex-wrap: wrap; gap: 4px 8px; align-items: baseline; }
.extra-title { font-weight: 700; }
.extra-desc { color: #57534e; }

/* T2 单栏·应届 */
.tpl-campus { --gap-sec: 22px; }

/* T3 双栏·技术：左主栏 + 216px 侧栏；DOM 顺序仍是「页眉 → 左栏 → 右栏」 */
.tpl-tech {
  --fs-name: 24px; --fs-sec: 12.5px; --fs-body: 12.5px; --fs-date: 11.5px;
  --lh: 1.55; --gap-sec: 20px; --gap-title: 8px; --gap-entry: 14px; --gap-list: 6px;
}
.tpl-tech .doc-body { display: grid; grid-template-columns: 1fr 216px; gap: 32px; align-items: start; }
.tpl-tech .doc-side { padding-left: 20px; border-left: 1px solid #e0ddd9; }
.tpl-tech .doc-side .sec-title { font-size: 11.5px; }

/* T4 单栏·海外英文 */
.tpl-intl { --fs-name: 25px; --fs-sec: 11.5px; --fs-body: 13px; --fs-date: 12px; --lh: 1.55; padding: 16mm 18mm; }
.tpl-intl .doc-head { text-align: center; margin-bottom: 24px; }
.tpl-intl .doc-name {
  font-family: Georgia, "Times New Roman", serif;
  letter-spacing: 0.1em; text-transform: uppercase;
}
.tpl-intl .doc-contact, .tpl-intl .doc-links { justify-content: center; }
.tpl-intl .doc-contact li + li::before { display: none; }
.tpl-intl .doc-contact li + li { margin-left: 6px; }
.tpl-intl .sec-title { text-transform: uppercase; letter-spacing: 0.12em; }

/* T5 紧凑·一页 */
.tpl-compact {
  --fs-name: 22px; --fs-sec: 12.5px; --fs-body: 11.5px; --fs-date: 11px;
  --lh: 1.45; --gap-sec: 16px; --gap-title: 6px; --gap-entry: 12px;
  --gap-list: 4px; --gap-li: 2px; padding: 12mm 14mm;
}

/* ── v1.79.0 扩充的五套 ── */

/* T6 左栏·侧边栏：侧栏在左（DOM 顺序仍是主栏在前，用 order 调换） */
.tpl-sidebar-left { --fs-name: 25px; --gap-sec: 22px; }
.tpl-sidebar-left .doc-body {
  display: grid; grid-template-columns: 216px 1fr; gap: 32px; align-items: start;
}
.tpl-sidebar-left .doc-main { min-width: 0; }
.tpl-sidebar-left .doc-side {
  order: -1; min-width: 0; padding: 16px 14px; border: none; border-radius: 4px;
  background: #f1f7f6;
}

/* T7 时间线·经历：主栏左侧竖轴 + 圆点（纯装饰） */
.tpl-timeline { --gap-entry: 18px; }
.tpl-timeline .doc-main { padding-left: 20px; border-left: 1px solid #e0ddd9; }
.tpl-timeline .entry { position: relative; }
.tpl-timeline .entry::before {
  content: ""; position: absolute; top: 5px; left: -24px;
  width: 7px; height: 7px; border-radius: 50%; background: #0f766e;
}

/* T8 学术·科研：教育前置，行距更松 */
.tpl-academic { --fs-body: 13px; --lh: 1.65; --gap-sec: 26px; }

/* T9 产品·运营：行距松、要点标记醒目 */
.tpl-product { --lh: 1.7; --gap-li: 6px; --gap-entry: 18px; }
.tpl-product .bullets { padding-left: 14px; }
.tpl-product .bullets li::marker { color: #0f766e; font-weight: 700; }

/* T10 顶部色带：页眉做成浅色色块 */
.tpl-band { --gap-sec: 22px; }
.tpl-band .doc-head {
  margin-bottom: 22px; padding: 16px 18px;
  border-left: 3px solid #0f766e; border-radius: 4px; background: #eff6f5;
}

@media print {
  .resume-doc { width: auto; min-height: auto; }
}
`

/**
 * 构建完整的独立 HTML 文档。
 * 单页自包含：无外部 CSS / JS / 字体请求，离线可用、可直接打印。
 */
export function buildResumeHtml(doc: ResumeDoc, templateId: string): string {
  const tpl = findTemplate(templateId)
  const sections = visibleSections(doc, tpl)
  const b = doc.basics

  const contacts = [b.phone, b.email, b.location]
    .map((s) => s.trim())
    .filter(Boolean)
    .map((c) => `<li>${esc(c)}</li>`)
    .join('')

  const links = b.links.length
    ? `<ul class="doc-links">${b.links
        .map((l) => `<li>${esc(l.label || l.url)}</li>`)
        .join('')}</ul>`
    : ''

  const head = `<header class="doc-head">
  <h1 class="doc-name">${esc(b.name || '你的姓名')}</h1>
  ${b.title ? `<p class="doc-title">${esc(b.title)}</p>` : ''}
  ${contacts ? `<ul class="doc-contact">${contacts}</ul>` : ''}
  ${links}
</header>`

  const SIDE: ResumeSectionKey[] = ['skills', 'extras']
  /** 双栏（含反向）：只要不是单栏就要分出侧栏 */
  const isTwoCol = tpl.layout !== 'one-column'
  const mainKeys = isTwoCol ? sections.filter((k) => !SIDE.includes(k)) : sections
  const sideKeys = isTwoCol ? sections.filter((k) => SIDE.includes(k)) : []

  const mainHtml = mainKeys.map((k) => renderSection(k, doc, tpl.locale)).join('')
  const sideHtml = sideKeys.map((k) => renderSection(k, doc, tpl.locale)).join('')

  const body = isTwoCol
    ? `<div class="doc-body"><div class="doc-main">${mainHtml}</div><aside class="doc-side">${sideHtml}</aside></div>`
    : `<div class="doc-body"><div class="doc-main">${mainHtml}</div></div>`

  const title = b.name ? `${b.name} - 简历` : '我的简历'

  return `<!DOCTYPE html>
<html lang="${tpl.locale === 'en' ? 'en' : 'zh-CN'}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>${EXPORT_CSS}</style>
</head>
<body>
<article class="resume-doc ${tpl.cssClass}">
${head}
${body}
</article>
</body>
</html>`
}

/** 兜底：通过隐藏 iframe 触发打印，规避弹窗拦截（与 utils/reportPdf.ts 同一模式） */
function printViaFrame(html: string): void {
  const frame = document.createElement('iframe')
  frame.style.position = 'fixed'
  frame.style.right = '0'
  frame.style.bottom = '0'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  frame.setAttribute('aria-hidden', 'true')
  document.body.appendChild(frame)
  const doc = frame.contentDocument
  if (!doc) {
    frame.remove()
    return
  }
  doc.open()
  doc.write(html)
  doc.close()
  setTimeout(() => {
    frame.contentWindow?.focus()
    frame.contentWindow?.print()
    setTimeout(() => frame.remove(), 1000)
  }, 350)
}

/**
 * 导出为 PDF：打开打印窗口并触发打印，用户在系统打印对话框里选「另存为 PDF」。
 *
 * 产品文案必须说清「另存为 PDF」这一步 —— 浏览器不能静默落盘，
 * 不写清楚会让用户以为「点了没反应」。
 */
export function exportResumeToPdf(doc: ResumeDoc, templateId: string): void {
  const html = buildResumeHtml(doc, templateId)
  const win = window.open('', '_blank', 'width=900,height=1000')
  if (!win) {
    printViaFrame(html)
    return
  }
  win.document.open()
  win.document.write(html)
  win.document.close()
  setTimeout(() => {
    win.focus()
    win.print()
  }, 350)
}

/** 触发浏览器下载（Blob + 临时 a 标签） */
function download(filename: string, content: string, mime: string): void {
  const blob = new Blob([content], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 在部分浏览器会让下载中断，延后释放
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 文件名安全化：去掉路径分隔符与不可见字符，避免 Windows 下保存失败 */
function safeFileName(name: string, fallback: string): string {
  const base = (name || '').trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').slice(0, 60)
  return base || fallback
}

/** 下载独立 HTML（可离线打开、可直接打印） */
export function downloadResumeHtml(doc: ResumeDoc, templateId: string): void {
  const html = buildResumeHtml(doc, templateId)
  const name = safeFileName(doc.basics.name, '我的')
  download(`${name}-简历.html`, html, 'text/html')
}

/** 下载 Markdown（内容层导出，不含任何模板信息） */
export function downloadResumeMarkdown(doc: ResumeDoc, markdown: string): void {
  const name = safeFileName(doc.basics.name, '我的')
  download(`${name}-简历.md`, markdown, 'text/markdown')
}
