import { describe, it, expect } from 'vitest'
import { buildReportHtml, type ReportExportPayload } from './reportPdf'

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

  it('逐题无记录时给出占位文案而非报错', () => {
    const html = buildReportHtml({ ...basePayload(), questions: [], improvements: [] })
    expect(html).toContain('无逐题记录')
    expect(html).toContain('暂无高频改进点')
  })
})