import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

// html: false 禁止 HTML 标签通过，linkify 自动识别链接
const md = new MarkdownIt({ html: false, linkify: true })

// DOMPurify 消毒：阻止 javascript: 协议等 XSS 向量
function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    FORBID_TAGS: ['style', 'iframe'],
    FORBID_ATTR: ['onerror', 'onload'],
  })
}

/**
 * 规范化大模型常输出的“密集中文 Markdown”，使其能被 markdown-it 正确渲染。
 *
 * 痛点：模型常见输出为 `##标题`（标题后缺空格）且标题/列表/段落间没有空行，
 * 而 CommonMark 要求：标题标记后必须有空格、块级元素前必须有空行才能识别，
 * 否则会在 `render` 后变成整段粘连文字。
 */
export function normalizeMarkdown(text: string): string {
  if (!text) return ''
  let s = text
  // 1) 标题标记后补空格：##虚拟DOM -> ## 虚拟DOM
  //    用 (?![\s#]) 避免回溯：### 标题 已带空格/井号时不再改动
  s = s.replace(/^(#{1,6})(?![\s#])(.)/gm, '$1 $2')
  // 2) 块级元素前补空行，让结构生效
  s = s.replace(/\n(#{1,6}\s+\S)/g, '\n\n$1') // 标题
  s = s.replace(/\n```/g, '\n\n```') // 代码块开/闭
  s = s.replace(/\n(>\s)/g, '\n\n>$1') // 引用
  s = s.replace(/\n([-*+]\s)(?![-*+])/g, '\n\n$1') // 顶格无序列表
  s = s.replace(/\n(\d+[.)]\s)/g, '\n\n$1') // 顶格有序列表
  // 3) 清理开头多余空行
  return s.replace(/^\s*\n/, '')
}

/**
 * 渲染 + 规范化 + 消毒一条龙，供 AI 文本安全展示。
 */
export function renderMarkdown(text: string): string {
  return sanitizeHtml(md.render(normalizeMarkdown(text ?? '')))
}

export default renderMarkdown