/**
 * 后端 API 基地址（独立模块，避免 api/index.ts 与 utils/backendWake.ts 循环依赖）
 *
 * - 生产环境（Cloudflare Pages / Vercel 静态托管）：显式配置 VITE_API_BASE_URL 直连后端
 * - 同源反向代理部署：留空，走相对路径 /api
 */
export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || ''
