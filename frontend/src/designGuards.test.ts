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

/** 写死 hex 扫描范围（含 .ts：评分工具等也在此列） */
const HEX_SCAN_EXT = new Set(['.vue', '.css', '.ts'])

/**
 * 写死 hex 的**合理例外**文件（只有这里能出现 hex，其余一律用语义令牌）。
 */
const HEX_ALLOWED_FILES = new Set([
  'styles/variables.css', // 令牌定义文件本身
  'utils/reportPdf.ts', // 导出 PDF 的独立 HTML 模板，脱离应用主题
  'utils/reportShare.ts', // Canvas 分享图绘制，无法使用 CSS 变量
  'changelog.ts', // 版本发布说明文本
  'designGuards.test.ts', // 守卫自身的白名单/断言定义
])

/** 纯白/纯黑：与主题无关的基元色（品牌色底上的文字、color-mix 基色） */
const HEX_NEUTRAL = /^#(fff|ffffff|000|000000)$/i
/** 十六进制颜色字面量 */
const HEX_LITERAL = /#[0-9a-fA-F]{3,8}\b/g

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
  return walkExt(dir, SCAN_EXT)
}

function walkExt(dir: string, exts: Set<string>): string[] {
  const out: string[] = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue
      out.push(...walkExt(full, exts))
    } else if (exts.has(path.extname(entry.name))) {
      out.push(full)
    }
  }
  return out
}

/**
 * 去掉「注释行」与「var() 及其 fallback」后再扫描 hex。
 * - 注释里的 hex（如说明文字）不应算违规；
 * - `var(--x, #hex)` 的 fallback 不随主题切换，但它是变量缺失时的兜底，
 *   且大量存量如此，故放行（真正要防的是「直接写死成生效色」）。
 */
function stripCommentsAndVarFallbacks(line: string): string {
  const trimmed = line.trim()
  if (trimmed.startsWith('*') || trimmed.startsWith('/*') || trimmed.startsWith('//')) return ''
  return line.replace(/var\([^)]*\)/g, '')
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

  it('除 variables.css 外不得出现 var(--font-serif)（展示级衬线只走 --font-display）', () => {
    const bad: string[] = []
    for (const file of files) {
      const r = rel(file)
      if (r === 'styles/variables.css') continue
      const text = fs.readFileSync(file, 'utf8')
      for (const [i, line] of text.split('\n').entries()) {
        if (line.includes('var(--font-serif)')) bad.push(`${r}:${i + 1}  ${line.trim()}`)
      }
    }
    expect(bad, `请改用 var(--font-display)：\n${bad.join('\n')}`).toEqual([])
  })

  it('业务文件不得写死颜色 hex（评分/语义色必须走令牌）', () => {
    const bad: string[] = []
    for (const file of walkExt(SRC, HEX_SCAN_EXT)) {
      const r = rel(file)
      if (HEX_ALLOWED_FILES.has(r)) continue
      for (const [i, raw] of fs.readFileSync(file, 'utf8').split('\n').entries()) {
        const line = stripCommentsAndVarFallbacks(raw)
        if (!line) continue
        for (const m of line.matchAll(HEX_LITERAL)) {
          if (HEX_NEUTRAL.test(m[0])) continue
          if (isAllowed(r, raw)) continue
          bad.push(`${r}:${i + 1}  ${raw.trim()}`)
        }
      }
    }
    expect(bad, `请改用语义令牌（如 var(--score-good) / var(--c-danger)），不要写死 hex：\n${bad.join('\n')}`)
      .toEqual([])
  })
})

/* ───────────── CSS 变量定义完整性守卫（v1.63.1）───────────── */

/**
 * ── 为什么需要它 ────────────────────────────────────────────────────
 * v1.63.1 走查发现：`--input-bg` 与 `--text-secondary` **全站从未定义**，
 * 而用法写成 `var(--token, #浅色)`。暗色主题下底色仍是近白，文字却是 `--c-text`
 * （暗色下=近白）→ **文字直接看不见**。
 *
 * 为什么原有的 hex 门禁没拦住：`stripCommentsAndVarFallbacks()` 会把整个 `var(...)`
 * **连同 fallback 一起剥掉**，所以 `var(--未定义, #f5f5f5)` 里的写死 hex 逃过了扫描。
 * 换句话说，「变量缺失时回退到写死的浅色」这条路径本身就是事故成因，不能放行。
 *
 * 扫描范围取 `SCAN_EXT`（.vue/.css）——导出用的 HTML 模板在 .ts 里，不参与主题切换。
 */

/** 运行时通过内联 `:style` 注入的变量 —— 不在 variables.css 里定义是**正确**的。每条都要写理由。 */
const RUNTIME_INJECTED_VARS: Array<{ name: string; reason: string }> = [
  { name: '--w', reason: '进度条宽度：HomeView 用 :style 逐行注入，值随数据变化，无法预定义' },
  { name: '--score-color', reason: '评分色：JobAnalysisView 用 :style 按分数档位注入' },
]

describe('CSS 变量定义完整性（v1.63.1）', () => {
  it('所有被引用的 CSS 变量都必须在 styles/variables.css 中定义', () => {
    const defined = new Set<string>()
    for (const line of fs.readFileSync(path.join(SRC, 'styles', 'variables.css'), 'utf8').split('\n')) {
      for (const m of line.matchAll(/(--[a-zA-Z0-9_-]+)\s*:/g)) defined.add(m[1])
    }

    const exempt = new Set(RUNTIME_INJECTED_VARS.map((v) => v.name))
    const missing: string[] = []
    let scanned = 0

    for (const file of walkExt(SRC, SCAN_EXT)) {
      const r = rel(file)
      scanned++
      for (const [i, line] of fs.readFileSync(file, 'utf8').split('\n').entries()) {
        for (const m of line.matchAll(/var\((--[a-zA-Z0-9_-]+)/g)) {
          const name = m[1]
          if (defined.has(name) || exempt.has(name)) continue
          missing.push(`${r}:${i + 1}  未定义 ${name}   ${line.trim()}`)
        }
      }
    }

    // ── 扫描范围自检 ──
    // 路径写错时扫描结果为空 → 守卫会**静默失效**（你以为有保护，实际没有），
    // 这比没有守卫更危险。所以必须断言「确实扫到了足够多的文件」。
    expect(scanned, '扫描范围异常：文件数过少，守卫可能已静默失效').toBeGreaterThan(20)
    expect(fs.existsSync(path.join(SRC, 'views', 'HomeView.vue')), '扫描根目录可能不对').toBe(true)

    expect(
      missing,
      `以下 CSS 变量被引用但从未定义 —— 暗色下会回退到写死的浅色，导致文字/边框不可见：\n${missing.join('\n')}`,
    ).toEqual([])
  })
})

describe('transition 必须指明属性（v1.64.1）', () => {
  it('不得使用 transition: all', () => {
    // `transition: all` 会动画化**所有**可动画属性，包括你没打算动的那些
    // （布局属性被动画化会触发重排，长列表上表现为卡顿）。
    // v1.64.1 全仓清了 57 处，用这条守住不再写回来。
    const bad: string[] = []
    let scanned = 0
    for (const file of walkExt(SRC, SCAN_EXT)) {
      const r = rel(file)
      scanned++
      for (const [i, line] of fs.readFileSync(file, 'utf8').split('\n').entries()) {
        if (/transition:\s*all\b/.test(line)) bad.push(`${r}:${i + 1}  ${line.trim()}`)
      }
    }
    expect(scanned, '扫描范围异常：文件数过少，守卫可能已静默失效').toBeGreaterThan(20)
    expect(bad, `transition: all 请改为指明属性（如 color / background-color / box-shadow / transform）：\n${bad.join('\n')}`)
      .toEqual([])
  })
})
