/**
 * 岗位搜索关键词校验（P2-04）。
 *
 * 背景：Render 边缘 WAF 会对含 SQL 注入特征的查询串直接返回 403 + HTML 拦截页，
 * 且该响应**不带 CORS 头** → 浏览器只能上报 `net::ERR_FAILED`，前端拿不到任何
 * 可解释信息（曾据此误报「后端冷启动」并静默重放一次）。
 *
 * 关键认识（2026-09-23 线上实测修正）：**请求在到达应用之前就被边缘 WAF 拦掉了**。
 * 因此后端入口的白名单校验对这类输入永远不会执行 —— 实测带合法 JWT 请求
 * `GET /api/jobs?keyword=' OR 1=1` 仍返回 WAF 的 403 页面（Request ID a3fa4dffbef94c1d）。
 *
 * 所以校验必须放在**请求发出之前**：不合法就地提示、不发请求。
 * 规则与后端 `JobAgentController.validateKeyword` 保持一致，避免「前端放过、后端拒绝」
 * 两套口径；后端那道仍然保留，用于拦截「能到达应用、但字符不合规」的输入（如 `;`、`%`）。
 */

/** 允许的字符：中文、英文、数字、空格与常见技术符号 */
const ALLOWED = /^[\u4e00-\u9fa5a-zA-Z0-9 +#._\-/&·]*$/

/** 关键词长度上限（与后端保持一致） */
export const JOB_KEYWORD_MAX_LENGTH = 50

/**
 * 校验岗位搜索关键词，返回错误提示；合法（或为空）时返回 null。
 *
 * @param keyword 用户输入
 */
export function validateJobKeyword(keyword: string): string | null {
  const trimmed = (keyword || '').trim()
  if (!trimmed) return null
  if (trimmed.length > JOB_KEYWORD_MAX_LENGTH) {
    return `搜索关键词过长（最多 ${JOB_KEYWORD_MAX_LENGTH} 个字符），请缩短后重试`
  }
  if (!ALLOWED.test(trimmed)) {
    return '搜索关键词包含不支持的字符，请仅使用中英文、数字与常见符号（+ # . - _ / &）'
  }
  return null
}
