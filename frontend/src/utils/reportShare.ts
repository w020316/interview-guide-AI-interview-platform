/**
 * 面试复盘报告分享卡片（v1.25.0 新功能）
 * - generateShareCard：canvas 绘制成绩海报并触发 PNG 下载（纯客户端，无服务端依赖）
 * - scoreLevel / shareFileName：纯函数，可单测
 *
 * 说明：jsdom 测试环境无 2D canvas 实现，generateShareCard 内的绘制逻辑保持薄封装，
 * 可测逻辑全部收敛到导出的纯函数中。
 */

export interface ShareCardPayload {
  /** 岗位描述 */
  jobTitle: string
  /** 作答题目数 */
  answeredCount: number
  /** 综合平均分 */
  overall: number
  /** 维度平均分 */
  completeness: number
  accuracy: number
  expression: number
  /** 报告日期文本，默认当天 */
  dateText?: string
}

/** 依据分数返回文字等级（与报告 PDF 口径一致） */
export function scoreLevel(score: number): string {
  if (score >= 85) return '优秀'
  if (score >= 70) return '良好'
  if (score >= 60) return '合格'
  return '待加强'
}

/** 依据分数返回颜色（海报无 CSS 变量，用固定色值） */
export function scoreHex(score: number): string {
  if (score >= 85) return '#10b981'
  if (score >= 70) return '#3b82f6'
  if (score >= 60) return '#f59e0b'
  return '#ef4444'
}

/** 分享卡片文件名：岗位名清洗非文件字符 + 日期，如 share_Java后端_2026-09-10.png */
export function shareFileName(jobTitle: string, dateText: string): string {
  const safe = (jobTitle || '面试报告').replace(/[\\/:*?"<>|\s]+/g, '').slice(0, 20) || '面试报告'
  return `share_${safe}_${dateText}.png`
}

/** 默认日期文本：2026-09-10 */
export function todayText(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/**
 * 绘制分享海报并下载 PNG
 * 布局：顶部品牌色头带（标题+日期）→ 大号综合分 → 四维度条 → 底部署名
 */
export async function generateShareCard(p: ShareCardPayload): Promise<void> {
  const W = 640
  const H = 840
  const canvas = document.createElement('canvas')
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('当前浏览器不支持 Canvas，无法生成分享卡片')

  const dateText = p.dateText || todayText()

  // 背景
  ctx.fillStyle = '#f8fafc'
  ctx.fillRect(0, 0, W, H)

  // 顶部品牌色头带
  const grad = ctx.createLinearGradient(0, 0, W, 220)
  grad.addColorStop(0, '#0f766e')
  grad.addColorStop(1, '#115e59')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, W, 220)

  ctx.fillStyle = 'rgba(255,255,255,0.85)'
  ctx.font = '600 22px system-ui, sans-serif'
  ctx.fillText('AI 模拟面试复盘', 40, 64)

  ctx.fillStyle = '#ffffff'
  ctx.font = '700 30px system-ui, sans-serif'
  const title = (p.jobTitle || '未指定岗位').slice(0, 16)
  ctx.fillText(title, 40, 116)

  ctx.fillStyle = 'rgba(255,255,255,0.7)'
  ctx.font = '400 16px system-ui, sans-serif'
  ctx.fillText(`${dateText} · 共 ${p.answeredCount} 题作答`, 40, 152)

  // 综合分
  ctx.fillStyle = scoreHex(p.overall)
  ctx.font = '800 88px system-ui, sans-serif'
  ctx.fillText(String(Math.round(p.overall)), 40, 330)
  ctx.fillStyle = '#64748b'
  ctx.font = '500 18px system-ui, sans-serif'
  ctx.fillText(`综合 ${scoreLevel(p.overall)}`, 40, 364)

  // 四维度条
  const dims = [
    { name: '完整性', v: p.completeness },
    { name: '准确性', v: p.accuracy },
    { name: '表达力', v: p.expression },
  ]
  let y = 430
  ctx.font = '500 18px system-ui, sans-serif'
  for (const d of dims) {
    ctx.fillStyle = '#334155'
    ctx.fillText(d.name, 40, y)
    // 轨道
    ctx.fillStyle = '#e2e8f0'
    roundRect(ctx, 130, y - 14, 430, 14, 7)
    // 填充
    ctx.fillStyle = scoreHex(d.v)
    roundRect(ctx, 130, y - 14, Math.max(14, 430 * Math.min(100, Math.max(0, d.v)) / 100), 14, 7)
    // 数值
    ctx.fillStyle = scoreHex(d.v)
    ctx.fillText(`${Math.round(d.v)}`, 575, y)
    y += 54
  }

  // 底部署名
  ctx.fillStyle = '#94a3b8'
  ctx.font = '400 14px system-ui, sans-serif'
  ctx.fillText('AI 智能面试辅助平台 · 基于 AI 评估生成', 40, H - 48)

  // 下载 PNG
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('分享卡片生成失败，请重试')
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = shareFileName(p.jobTitle, dateText)
  a.click()
  URL.revokeObjectURL(url)
}

/** 圆角矩形路径辅助 */
function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
  ctx.fill()
}
