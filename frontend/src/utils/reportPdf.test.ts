import { describe, it, expect, vi, afterEach } from 'vitest'
import { buildReportHtml, exportReportToPdf, type ReportExportPayload } from './reportPdf'
import { EMPTY } from './format'

function basePayload(): ReportExportPayload {
  return {
    jobTitle: 'Java 后端',
    answeredCount: 3,
    overall: 78,
    completeness: 75,
    accuracy: 82,
    expression: 74,
    questions: [
      { question: '请介绍你的项目架构', category: '项目深挖', overallScore: 88 },
      { question: '如何解决高并发问题？', category: '技术基础', overallScore: 70 },
    ],
    improvements: ['结构化表达更清晰', '补充量化结果'],
    summary: '本轮共回答 3 题，综合得分 78 分（良好）。',
  }
}

describe('reportPdf - buildReportHtml', () => {
  it('返回包含 DOCTYPE、标题与样式标签的独立 HTML', () => {
    const html = buildReportHtml(basePayload())
    expect(html).toContain('<!DOCTYPE html>')
    expect(html).toContain('<title>模拟面试复盘报告</title>')
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true)
  })

  it('展示岗位、作答数、综合等级与评价文案', () => {
    const html = buildReportHtml(basePayload())
    expect(html).toContain('Java 后端')
    expect(html).toContain('作答 3 题')
    expect(html).toContain('良好')
    expect(html).toContain('结构化表达更清晰')
  })

  it('准确输出分数等级（>=85 优秀，>=70 良好，>=60 合格，否则待加强）', () => {
    expect(buildReportHtml({ ...basePayload(), overall: 90 })).toContain('优秀')
    expect(buildReportHtml({ ...basePayload(), overall: 65 })).toContain('合格')
    expect(buildReportHtml({ ...basePayload(), overall: 45 })).toContain('待加强')
  })

  it('HTML 特殊字符被转义，防止注入', () => {
    const p = basePayload()
    p.summary = '含 <script>alert(1)</script> & 引号 " 的总结'
    const html = buildReportHtml(p)
    expect(html).not.toContain('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('作答数缺失（answeredCount=null）：渲染 EMPTY 占位，不打印「作答 0 题」', () => {
    const html = buildReportHtml({ ...basePayload(), answeredCount: null })
    expect(html).toContain(`作答 ${EMPTY} 题`)
    expect(html).not.toContain('作答 0 题')
  })

  it('逐题无记录时给出占位文案而非报错', () => {
    const html = buildReportHtml({ ...basePayload(), questions: [], improvements: [] })
    expect(html).toContain('无逐题记录')
    expect(html).toContain('暂无高频改进点')
  })
})

/**
 * RK1「无数据 ≠ 0」断言级防护（导出层）：
 * 旧会话 eval_detail 为 NULL ⇒ 导出 HTML 不得出现维度条，也不得把缺失维度补成 0。
 */
describe('reportPdf - 降级感知（RK1 无数据 ≠ 0）', () => {
  function degradedPayload(): ReportExportPayload {
    return {
      jobTitle: '历史会话',
      answeredCount: 2,
      overall: 66,
      completeness: null,
      accuracy: null,
      expression: null,
      questions: [
        { question: '请自我介绍', category: '通用', overallScore: 70 },
        { question: '项目难点？', category: '项目', overallScore: 62 },
      ],
      improvements: [],
      summary: '本场为早期会话，未保存分维度明细。',
    }
  }

  it('维度明细全缺失 → 不打印维度条、不补 0，仅给出降级说明', () => {
    const html = buildReportHtml(degradedPayload())
    expect(html).not.toContain('class="dims"')
    expect(html).not.toContain('class="dim-name"')
    // 不得出现被补 0 的维度行（>完整性< / >准确性< 等）
    expect(html).not.toMatch(/>完整性</)
    expect(html).not.toMatch(/>准确性</)
    expect(html).not.toMatch(/>表达力</)
    expect(html).toContain('data-report-degraded-note="true"')
    expect(html).toContain('未保存分维度明细')
  })

  it('维度明细完整 → 打印含综合在内的 4 条维度', () => {
    const html = buildReportHtml(basePayload())
    expect(html).toContain('class="dims"')
    expect((html.match(/class="dim-name"/g) || []).length).toBe(4)
    expect(html).toMatch(/>综合</)
    expect(html).toMatch(/>完整性</)
  })

  it('综合分缺失时展示占位符而非 0 分', () => {
    const html = buildReportHtml({ ...basePayload(), overall: null })
    // 不出现「0 分」结论（占位符为 —，且不渲染等级胶囊）
    expect(html).not.toMatch(/>0<span class="unit">分</)
    expect(html).toContain('—')
  })
})

describe('reportPdf - exportReportToPdf', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('window.open 被拦截时回退到隐藏 iframe 兜底打印', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null as unknown as Window)
    const append = vi.spyOn(document.body, 'appendChild')
    vi.useFakeTimers()
    exportReportToPdf(basePayload())
    expect(open).toHaveBeenCalled()
    expect(append).toHaveBeenCalled()
    // 推进定时器，确认打印流程不会抛错
    vi.advanceTimersByTime(2000)
  })

  it('window.open 返回窗口时写入 HTML 并在延迟后触发打印', () => {
    const win = {
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() },
      focus: vi.fn(),
      print: vi.fn(),
    }
    vi.spyOn(window, 'open').mockReturnValue(win as unknown as Window)
    vi.useFakeTimers()
    exportReportToPdf(basePayload())
    expect(win.document.open).toHaveBeenCalled()
    expect(win.document.write).toHaveBeenCalledWith(expect.stringContaining('<!DOCTYPE html>'))
    expect(win.document.close).toHaveBeenCalled()
    vi.advanceTimersByTime(400)
    expect(win.focus).toHaveBeenCalled()
    expect(win.print).toHaveBeenCalled()
  })
})