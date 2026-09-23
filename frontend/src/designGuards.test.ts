import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 设计系统防回退守卫（v1.39.0）
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * v1.39.0 的审美审计发现两类**代码审查看不出来**的问题，它们都不是语法错误、
 * 编译和单测全绿，但真机上观感明显是坏的：
 *
 * 1. **写死的浅色背景 / 深色文字**：项目靠 `[data-theme='dark']` 覆盖 CSS 变量切换主题，
 *    而直接写 hex 的规则不参与切换，暗色下表现为「浅底深字贴在深色页面上」。
 *    本次在 7 个文件里清出 11 处。
 *
 * 2. **小号衬线标题**：`--font-serif` 的 CJK 首选（Source Han Serif SC / Songti SC）
 *    只在 Apple 设备存在，Windows 回退 SimSun、多数 Android 回退默认衬线。
 *    14~18px 的宋体标题笔画发虚、显旧。本次清出 24 处。
 *
 * 这两类问题**极易在后续迭代中被重新写回来**（新增一个状态标签、复制一段旧样式），
 * 所以用测试固化成门禁。命中时请按提示改用语义令牌 / --font-title，
 * 而不是往 ALLOWED 里加例外。
 */

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)))

const SCAN_EXT = new Set(['.vue', '.css'])

/**
 * 已知且**合理**的例外。每条都要写清理由，没有理由就不该进来。
 */
const ALLOWED: Array<{ file: string; pattern: RegExp; reason: string }> = [
  {
    file: 'components/BaseButton.vue',
    pattern: /background:\s*#fff\s*;/,
    reason: 'CTA 按钮：白底反色，用在品牌色背景上，本就不跟随主题',
  },
  {
    file: 'views/LoginView.vue',
    pattern: /background:\s*rgba\(255,\s*255,\s*255,\s*0?\.\d+\)\s*;/,
    reason: '登录页左侧深色插画区的玻璃拟态叠层，固定为白色半透明',
  },
  {
    file: 'styles/variables.css',
    pattern: /color:\s*#0b2321\s*;/,
    reason: '暗色主题 ::selection 的前景色，与提亮后的选中底色配对',
  },
  {
    file: 'views/ResumeView.vue',
    pattern: /^\s*(body|h1|h2|h3|strong)\s*\{[^}]*#[0-9a-fA-F]{6}/,
    reason: '简历报告导出用的独立 HTML 模板字符串，不参与应用主题切换',
  },
]

/** 浅色背景（暗色下会亮成一块） */
const LIGHT_BG = /background(?:-color)?:\s*(#[0-9a-fA-F]{3,8}|white|rgba?\([^)]*\))\s*;/g
/** 深色前景（暗色下对比度不足） */
const DARK_FG = /(?<!-)color:\s*(#[0-9a-fA-F]{3,8}|black)\s*;/g
/** 衬线字族声明 */
const SERIF_FAMILY = /font-family:\s*var\(--font-serif\)/

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue
      out.push(...walk(full))
    } else if (SCAN_EXT.has(path.extname(entry.name))) {
      out.push(full)
    }
  }
  return out
}

function rel(abs: string): string {
  return path.relative(SRC, abs).split(path.sep).join('/')
}

function isAllowed(file: string, line: string): boolean {
  return ALLOWED.some((a) => a.file === file && a.pattern.test(line))
}

function isLightHex(token: string): boolean {
  const t = token.trim().toLowerCase()
  if (t === 'white') return true
  if (t.startsWith('rgb')) {
    const nums = t.match(/[\d.]+/g)
    if (!nums || nums.length < 3) return false
    const [r, g, b] = nums.slice(0, 3).map(Number)
    return (r + g + b) / 3 >= 200
  }
  if (t.startsWith('#')) {
    let h = t.slice(1)
    if (h.length === 3) h = h.split('').map((c) => c + c).join('')
    if (h.length !== 6 && h.length !== 8) return false
    const r = parseInt(h.slice(0, 2), 16)
    const g = parseInt(h.slice(2, 4), 16)
    const b = parseInt(h.slice(4, 6), 16)
    return (r + g + b) / 3 >= 200
  }
  return false
}

function isDarkHex(token: string): boolean {
  const t = token.trim().toLowerCase()
  if (t === 'black') return true
  let h = t.slice(1)
  if (h.length === 3) h = h.split('').map((c) => c + c).join('')
  if (h.length !== 6 && h.length !== 8) return false
  return parseInt(h.slice(0, 1), 16) <= 2
}

describe('设计系统防回退守卫', () => {
  const files = walk(SRC)

  it('扫描范围有效（避免路径写错导致守卫静默失效）', () => {
    expect(files.length).toBeGreaterThan(20)
    expect(files.some((f) => f.endsWith('App.vue'))).toBe(true)
  })

  it('不允许写死浅色背景（暗色主题下会反色破版）', () => {
    const bad: string[] = []
    for (const file of files) {
      const r = rel(file)
      for (const [i, line] of fs.readFileSync(file, 'utf8').split('\n').entries()) {
        for (const m of line.matchAll(LIGHT_BG)) {
          if (!isLightHex(m[1])) continue
          if (isAllowed(r, line)) continue
          bad.push(`${r}:${i + 1}  ${line.trim()}`)
        }
      }
    }
    expect(bad, `请改用 --c-*-light / --c-bg / --c-surface 等语义令牌：\n${bad.join('\n')}`)
      .toEqual([])
  })

  it('不允许写死深色文字（暗色主题下对比度不足）', () => {
    const bad: string[] = []
    for (const file of files) {
      const r = rel(file)
      for (const [i, line] of fs.readFileSync(file, 'utf8').split('\n').entries()) {
        for (const m of line.matchAll(DARK_FG)) {
          if (!isDarkHex(m[1])) continue
          if (isAllowed(r, line)) continue
          bad.push(`${r}:${i + 1}  ${line.trim()}`)
        }
      }
    }
    expect(bad, `请改用 --c-text / --c-*-* 等语义令牌：\n${bad.join('\n')}`).toEqual([])
  })

  it('不允许小号标题使用衬线字族（Windows/Android 会退化成宋体）', () => {
    const bad: string[] = []
    for (const file of files) {
      const text = fs.readFileSync(file, 'utf8')
      for (const m of text.matchAll(new RegExp(SERIF_FAMILY.source, 'g'))) {
        const open = text.lastIndexOf('{', m.index)
        const close = text.indexOf('}', m.index)
        const block = open !== -1 && close !== -1 ? text.slice(open, close) : ''
        const size = block.match(/font-size:\s*(\d+(?:\.\d+)?)px/)
        const px = size ? Number(size[1]) : null
        // 字号读不到（继承/变量）时无法判定，交给人工；能判定且 <24px 才算违规
        if (px === null || px >= 24) continue
        const line = text.slice(0, m.index).split('\n').length
        bad.push(`${rel(file)}:${line}  font-size:${px}px`)
      }
    }
    expect(bad, `小号标题请改用 var(--font-title)：\n${bad.join('\n')}`).toEqual([])
  })

  it('展示级衬线只允许出现在 --font-display 上（--font-serif 已降级为它的别名）', () => {
    const css = fs.readFileSync(path.join(SRC, 'styles', 'variables.css'), 'utf8')
    expect(css).toContain('--font-display: var(--font-serif);')
    expect(css).toContain('--font-title:')
  })
})
