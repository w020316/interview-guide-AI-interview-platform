import { describe, expect, it } from 'vitest'
import {
  PDF_MAX_BYTES,
  deriveVerdict,
  formatCount,
  normalizeExportReport,
  statusLabel,
  stricterVerdict,
  validatePdfFile,
  verdictLabel,
  type ExportCheckItem,
} from './exportCheck'

/** 与后端 ResumeExportCheckService 输出的 7 项检查同形 */
const SERVER_REPORT = {
  verdict: 'warn',
  summary: '有 1 项需要留意：联系方式里没有找到电话或邮箱',
  pageCount: 2,
  charCount: 1234,
  checks: [
    { id: 'pdf-open', label: '文件可打开', status: 'pass', detail: 'PDF 结构完整' },
    { id: 'pages', label: '页数', status: 'pass', detail: '共 2 页' },
    { id: 'text-layer', label: '文字层', status: 'pass', detail: '可提取 1234 个字符' },
    { id: 'contact', label: '联系方式', status: 'warn', detail: '没有找到电话或邮箱' },
    { id: 'sections', label: '关键板块', status: 'pass', detail: '识别到：教育背景、项目经历' },
    { id: 'timeline', label: '时间信息', status: 'pass', detail: '识别到 4 处年份' },
    { id: 'glyphs', label: '特殊字符', status: 'pass', detail: '没有可疑字符' },
  ],
}

const item = (over: Partial<ExportCheckItem> = {}): ExportCheckItem => ({
  id: 'x',
  label: '某项',
  status: 'pass',
  detail: '',
  ...over,
})

describe('normalizeExportReport —— 体检报告形状兜底', () => {
  it('服务端返回的合法报告原样保留', () => {
    const r = normalizeExportReport(SERVER_REPORT)
    expect(r).not.toBeNull()
    expect(r!.verdict).toBe('warn')
    expect(r!.pageCount).toBe(2)
    expect(r!.charCount).toBe(1234)
    expect(r!.checks).toHaveLength(7)
    expect(r!.checks[3]).toEqual({
      id: 'contact',
      label: '联系方式',
      status: 'warn',
      detail: '没有找到电话或邮箱',
    })
    expect(r!.summary).toContain('联系方式')
  })

  it('缺 summary 时用结论对应的兜底文案（与后端口径一致）', () => {
    const r = normalizeExportReport({ ...SERVER_REPORT, summary: '' })
    expect(r!.summary).toBe('有需要留意的项目，建议修复后重新导出')
  })

  it('verdict 字段缺失/非法时由检查项推导', () => {
    expect(normalizeExportReport({ ...SERVER_REPORT, verdict: undefined })!.verdict).toBe('warn')
    expect(normalizeExportReport({ ...SERVER_REPORT, verdict: 'PASS' })!.verdict).toBe('warn')
    const allPass = { ...SERVER_REPORT, verdict: undefined, checks: [item(), item({ id: 'b' })] }
    expect(normalizeExportReport(allPass)!.verdict).toBe('pass')
  })

  it('结论不得优于证据：声明 pass 但有 warn 项 → 按 warn 显示', () => {
    const raw = { ...SERVER_REPORT, verdict: 'pass', summary: '全部通过' }
    const r = normalizeExportReport(raw)!
    expect(r.verdict).toBe('warn')
    // summary 是服务端原文，仍原样展示（界面结论以 verdict 为准）
    expect(r.summary).toBe('全部通过')
  })

  it('结论不得优于证据：声明 fail 但全 pass → 保持 fail（不擅自升级为通过）', () => {
    const raw = { verdict: 'fail', pageCount: 1, charCount: 10, checks: [item()], summary: '' }
    expect(normalizeExportReport(raw)!.verdict).toBe('fail')
  })

  it('认不出的 status 按 warn 处理，绝不当成通过', () => {
    const r = normalizeExportReport({
      verdict: 'pass',
      pageCount: 1,
      charCount: 10,
      checks: [item({ status: 'ok' as never })],
      summary: '',
    })!
    expect(r.checks[0].status).toBe('warn')
    expect(r.verdict).toBe('warn')
  })

  it('条目缺 id 时用 label 兜底；缺 label 时用 id 兜底；两者都没有则丢弃', () => {
    const r = normalizeExportReport({
      pageCount: 1,
      charCount: 10,
      checks: [
        { label: '无 id 项', status: 'pass' },
        { id: 'no-label', status: 'warn' },
        { status: 'fail' },
        'not-an-object',
        null,
      ],
    })!
    expect(r.checks.map((c) => c.id)).toEqual(['无 id 项', 'no-label'])
    expect(r.checks[0].label).toBe('无 id 项')
    expect(r.checks[1].label).toBe('no-label')
    // 丢弃的 fail 项不应把结论拖成 fail
    expect(r.verdict).toBe('warn')
  })

  it('detail 非字符串按空串处理（不渲染 undefined）', () => {
    const r = normalizeExportReport({
      pageCount: 1,
      charCount: 1,
      checks: [{ id: 'a', label: 'A', status: 'pass', detail: 42 }],
    })!
    expect(r.checks[0].detail).toBe('')
  })

  it('页数 / 字数缺失或类型不对 → null（界面显示「—」，不是 0）', () => {
    for (const bad of [undefined, null, '2', NaN, Infinity, -1, {}]) {
      const r = normalizeExportReport({ ...SERVER_REPORT, pageCount: bad, charCount: bad })!
      expect(r.pageCount).toBeNull()
      expect(r.charCount).toBeNull()
    }
  })

  it('0 是真实数据，必须保留为 0（图片型 PDF 的可提取文字数就是 0）', () => {
    const r = normalizeExportReport({ ...SERVER_REPORT, pageCount: 0, charCount: 0 })!
    expect(r.pageCount).toBe(0)
    expect(r.charCount).toBe(0)
  })

  it('小数向下取整（页数不显示 1.5 页）', () => {
    const r = normalizeExportReport({ ...SERVER_REPORT, pageCount: 1.9, charCount: 99.99 })!
    expect(r.pageCount).toBe(1)
    expect(r.charCount).toBe(99)
  })

  it('形状完全不成立时返回 null，由界面展示错误态', () => {
    expect(normalizeExportReport(null)).toBeNull()
    expect(normalizeExportReport(undefined)).toBeNull()
    expect(normalizeExportReport('{"verdict":"pass"}')).toBeNull()
    expect(normalizeExportReport([SERVER_REPORT])).toBeNull()
    expect(normalizeExportReport({ ...SERVER_REPORT, checks: 'oops' })).toBeNull()
    expect(normalizeExportReport({ ...SERVER_REPORT, checks: [] })).toBeNull()
  })
})

describe('deriveVerdict / stricterVerdict —— 结论只降不升', () => {
  it('任一 fail → fail > 任一 warn → warn > 全 pass → pass', () => {
    expect(deriveVerdict([item(), item({ status: 'warn' })])).toBe('warn')
    expect(deriveVerdict([item({ status: 'warn' }), item({ status: 'fail' })])).toBe('fail')
    expect(deriveVerdict([item(), item()])).toBe('pass')
  })

  it('stricterVerdict 取更差的一方', () => {
    expect(stricterVerdict('pass', 'warn')).toBe('warn')
    expect(stricterVerdict('fail', 'pass')).toBe('fail')
    expect(stricterVerdict('warn', 'warn')).toBe('warn')
    expect(stricterVerdict('pass', 'pass')).toBe('pass')
  })
})

describe('formatCount —— 数字展示', () => {
  it('null 显示「—」', () => {
    expect(formatCount(null)).toBe('—')
  })

  it('千分位分组，0 照常显示', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(999)).toBe('999')
    expect(formatCount(1234)).toBe('1,234')
    expect(formatCount(1234567)).toBe('1,234,567')
  })
})

describe('validatePdfFile —— 上传前本地预校验（与后端同文案）', () => {
  it('没选文件', () => {
    expect(validatePdfFile('', 0)).toBe('请选择要体检的 PDF 文件')
    expect(validatePdfFile('   ', 10)).toBe('请选择要体检的 PDF 文件')
  })

  it('非 PDF 给出「先导出再上传」的可操作指引', () => {
    const msg = validatePdfFile('resume.docx', 1024)
    expect(msg).toContain('只支持 PDF')
    expect(msg).toContain('导出 PDF')
  })

  it('大小写后缀都算 PDF', () => {
    expect(validatePdfFile('简历.PDF', 1024)).toBeNull()
  })

  it('超过 10MB 拒绝，边界值（恰好 10MB）放行', () => {
    expect(validatePdfFile('a.pdf', PDF_MAX_BYTES + 1)).toBe('文件大小不能超过 10MB')
    expect(validatePdfFile('a.pdf', PDF_MAX_BYTES)).toBeNull()
  })

  it('空文件拒绝', () => {
    expect(validatePdfFile('a.pdf', 0)).toBe('这个文件是空的，请重新导出后再试')
  })
})

describe('文案映射', () => {
  it('结论与单项状态的标签都是中文且互不混淆', () => {
    expect(verdictLabel('pass')).toBe('通过')
    expect(verdictLabel('warn')).toBe('有提醒')
    expect(verdictLabel('fail')).toBe('需要修复')
    expect(statusLabel('pass')).toBe('通过')
    expect(statusLabel('warn')).toBe('留意')
    expect(statusLabel('fail')).toBe('未通过')
  })
})
