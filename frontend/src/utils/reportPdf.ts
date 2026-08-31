/**
 * 面试复盘报告导出 PDF
 * - buildReportHtml：生成带内联样式的独立 HTML（不依赖 CSS 变量，打印窗口可正常渲染），纯函数可单测
 * - exportReportToPdf：打开新窗口写入 HTML 并触发浏览器打印，由用户「另存为 PDF」本地留存与分享
 */

export interface ReportExportItem {
  question: string
  category: string
  overallScore: number
}

export interface ReportExportPayload {
  /** 岗位描述，用于标题副题 */
  jobTitle: string
  /** 作答题目数 */
  answeredCount: number
  /** 综合平均分 */
  overall: number
  /** 维度平均分 */
  completeness: number
  accuracy: number
  expression: number
  /** 逐题明细 */
  questions: ReportExportItem[]
  /** 建议提升要点 */
  improvements: string[]
  /** 综合评价文案 */
  summary: string
  /** 导出时间，默认当前时间字符串 */
  exportedAt?: string
}

/** 依据分数返回文字等级 */
function scoreLevel(score: number): string {
  if (score >= 85) return '优秀'
  if (score >= 70) return '良好'
  if (score >= 60) return '合格'
  return '待加强'
}

/** 依据分数返回对应颜色（打印端无 CSS 变量，直接用固定色值） */
function scoreHex(score: number): string {
  if (score == null) return '#9ca3af'
  if (score >= 85) return '#10b981'
  if (score >= 70) return '#3b82f6'
  if (score >= 60) return '#f59e0b'
  return '#ef4444'
}

/** 转义为安全的 HTML 纯文本，避免打印内容注入样式/标签 */
function esc(text: string): string {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 生成复盘报告的独立 HTML（内联样式，适配打印另存为 PDF）
 */
export function buildReportHtml(p: ReportExportPayload): string {
  const level = scoreLevel(p.overall)
  const overallHex = scoreHex(p.overall)
  const dims = [
    { name: '综合', v: p.overall, hex: scoreHex(p.overall) },
    { name: '完整性', v: p.completeness, hex: scoreHex(p.completeness) },
    { name: '准确性', v: p.accuracy, hex: scoreHex(p.accuracy) },
    { name: '表达力', v: p.expression, hex: scoreHex(p.expression) },
  ]
  const improvements = Array.isArray(p.improvements) && p.improvements.length
    ? p.improvements.map((it) => `<li>${esc(it)}</li>`).join('')
    : '<p style="margin:0;color:#6b7280">暂无高频改进点，继续保持！</p>'
  const questions = Array.isArray(p.questions) && p.questions.length
    ? p.questions.map((q, i) => `<li class="q-item">
        <div class="q-head"><span class="q-index">${i + 1}</span><span class="q-cat">${esc(q.category)}</span><span class="q-score" style="color:${scoreHex(q.overallScore)}">${Math.round(q.overallScore)} 分</span></div>
        <div class="q-text">${esc(q.question)}</div>
      </li>`).join('')
    : '<li style="color:#6b7280">无逐题记录</li>'

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>模拟面试复盘报告</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; color: #1f2937; margin: 0; padding: 32px 28px; line-height: 1.6; background: #fff; }
  .head { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 3px solid #10b981; padding-bottom: 14px; margin-bottom: 22px; }
  .head h1 { margin: 0; font-size: 22px; }
  .head .sub { font-size: 12px; color: #6b7280; margin-top: 4px; }
  .overall { text-align: center; padding: 22px 0 26px; }
  .overall .num { font-size: 58px; font-weight: 700; line-height: 1; }
  .overall .unit { font-size: 16px; font-weight: 400; margin-left: 2px; color: #6b7280; }
  .overall .tag { display: inline-block; margin-top: 10px; padding: 2px 12px; border-radius: 999px; font-size: 13px; }
  table.dims { width: 100%; border-collapse: collapse; margin-bottom: 22px; }
  table.dims td { padding: 7px 4px; font-size: 13px; }
  .dim-name { color: #6b7280; width: 84px; }
  .dim-bar { height: 10px; background: #f3f4f6; border-radius: 999px; overflow: hidden; }
  .dim-fill { height: 100%; border-radius: 999px; }
  .dim-val { font-weight: 600; text-align: right; width: 48px; }
  h2 { font-size: 16px; margin: 24px 0 12px; border-left: 4px solid #10b981; padding-left: 10px; }
  .summary { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 10px; padding: 14px 16px; font-size: 13px; color: #14532d; }
  ul.improve { margin: 0; padding-left: 18px; font-size: 13px; }
  ul.improve li { margin-bottom: 5px; }
  ol.qlist { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 10px; }
  .q-item { border: 1px solid #e5e7eb; border-radius: 10px; padding: 10px 12px; }
  .q-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
  .q-index { width: 20px; height: 20px; border-radius: 999px; background: #10b981; color: #fff; font-size: 12px; display: inline-flex; align-items: center; justify-content: center; }
  .q-cat { font-size: 12px; color: #374151; background: #f3f4f6; border-radius: 6px; padding: 1px 8px; }
  .q-score { font-size: 12px; font-weight: 600; margin-left: auto; }
  .q-text { font-size: 13px; color: #1f2937; }
  .foot { margin-top: 28px; font-size: 11px; color: #9ca3af; text-align: center; }
  @media print { body { padding: 0; } }
</style>
</head>
<body>
  <div class="head">
    <div>
      <h1>模拟面试复盘报告</h1>
      <div class="sub">目标岗位：${esc(p.jobTitle)} ｜ 作答 ${p.answeredCount ?? 0} 题 ｜ ${esc(p.exportedAt || new Date().toLocaleString())}</div>
    </div>
  </div>

  <div class="overall">
    <div class="num" style="color:${overallHex}">${Math.round(p.overall ?? 0)}<span class="unit">分</span></div>
    <span class="tag" style="color:#fff;background:${overallHex}">${level}</span>
  </div>

  <table class="dims">
    ${dims.map((d) => `<tr>
      <td class="dim-name">${d.name}</td>
      <td><div class="dim-bar"><div class="dim-fill" style="width:${Math.max(2, Math.min(100, Math.round(d.v ?? 0)))}%;background:${d.hex}"></div></div></td>
      <td class="dim-val" style="color:${d.hex}">${Math.round(d.v ?? 0)}</td>
    </tr>`).join('')}
  </table>

  <h2>综合评价</h2>
  <div class="summary">${esc(p.summary || '')}</div>

  <h2>建议提升的要点</h2>
  <ul class="improve">${improvements}</ul>

  <h2>逐题得分</h2>
  <ol class="qlist">${questions}</ol>

  <div class="foot">由 AI 智能面试辅助平台生成</div>
</body>
</html>`
}

/**
 * 打开打印窗口并触发打印（可另存为 PDF），实现本地留存与分享
 */
export function exportReportToPdf(payload: ReportExportPayload): void {
  const html = buildReportHtml(payload)
  const win = window.open('', '_blank', 'width=800,height=900')
  if (!win) {
    // 弹窗被拦截时回退到当前页面内新建隐藏 iframe 打印
    printViaFrame(html)
    return
  }
  win.document.open()
  win.document.write(html)
  win.document.close()
  // 等待字体/样式就绪后再触发打印
  setTimeout(() => {
    win.focus()
    win.print()
  }, 350)
}

/** 兜底：通过隐藏 iframe 触发打印，规避弹窗拦截 */
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