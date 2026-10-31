/**
 * 简历取件与上传的纯逻辑（v1.90.0）。
 *
 * 抽出来的原因：上传校验、PDF 真伪判定、移动端唤起决策都需要在真机之外
 * 可重复验证。视图只负责渲染与事件绑定，判断逻辑全部在这里单测锁死。
 */

export const MAX_RESUME_BYTES = 10 * 1024 * 1024

export type ResumeFileKind = 'pdf' | 'document' | 'image' | 'unknown'

export interface ResumeFileLike {
  name: string
  size: number
  type?: string
}

export interface ResumeFileCheck {
  ok: boolean
  kind: ResumeFileKind
  error?: string
}

export interface ValidateFileOptions {
  /** 上传主入口不收图片（截图走「从其他平台导入 → 选择相册截图」） */
  allowImages?: boolean
}

const DOCUMENT_EXTS = ['.html', '.htm', '.md', '.markdown', '.txt']
const IMAGE_EXTS = ['.png', '.jpg', '.jpeg', '.webp', '.gif']

export function extensionOf(name: string): string {
  return name.toLowerCase().match(/\.[^.]+$/)?.[0] || ''
}

export function fileKind(name: string): ResumeFileKind {
  const ext = extensionOf(name)
  if (ext === '.pdf') return 'pdf'
  if (DOCUMENT_EXTS.includes(ext)) return 'document'
  if (IMAGE_EXTS.includes(ext)) return 'image'
  return 'unknown'
}

/** 字节数转展示文案；只在提示里用，不做精确计算 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * 浅校验：大小与扩展名。
 *
 * 文案原则：说清「哪个文件、哪里不对、下一步怎么办」，不只回一句「格式错误」。
 */
export function validateResumeFile(
  file: ResumeFileLike,
  options: ValidateFileOptions = {},
): ResumeFileCheck {
  if (!file.name.trim()) {
    return { ok: false, kind: 'unknown', error: '文件名为空，请重新选择文件' }
  }
  if (file.size <= 0) {
    return { ok: false, kind: fileKind(file.name), error: '文件为空，请重新选择' }
  }
  if (file.size > MAX_RESUME_BYTES) {
    return {
      ok: false,
      kind: fileKind(file.name),
      error: `文件大小不能超过 10MB（当前 ${formatFileSize(file.size)}）`,
    }
  }

  const kind = fileKind(file.name)
  if (kind === 'unknown') {
    return {
      ok: false,
      kind,
      error: `「${file.name}」不是支持的格式。支持 PDF / HTML / MD / TXT，或简历截图（PNG / JPG / WEBP）`,
    }
  }
  if (kind === 'image' && !options.allowImages) {
    return {
      ok: false,
      kind,
      error: '图片请用「从其他平台导入 → 选择相册截图」上传；主上传入口只收 PDF / HTML / MD / TXT',
    }
  }
  return { ok: true, kind }
}

/** 读文件前 5 字节，判断是否真的以 %PDF- 开头 */
export async function hasPdfMagic(blob: Blob): Promise<boolean> {
  try {
    const head = blob.slice(0, 5)
    const buffer = typeof head.arrayBuffer === 'function'
      ? await head.arrayBuffer()
      : await readAsArrayBuffer(head)
    const bytes = new Uint8Array(buffer)
    return bytes.length === 5
      && bytes[0] === 0x25
      && bytes[1] === 0x50
      && bytes[2] === 0x44
      && bytes[3] === 0x46
      && bytes[4] === 0x2d
  } catch {
    return false
  }
}

function readAsArrayBuffer(blob: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
}

/**
 * 深校验：在浅校验通过后，对 .pdf 再验一次文件头。
 * 防住「把 .docx 改名成 .pdf」——这类文件后端会拒绝，前端先说清楚。
 */
export async function validateResumeFileDeep(
  file: File,
  options: ValidateFileOptions = {},
): Promise<ResumeFileCheck> {
  const base = validateResumeFile(file, options)
  if (!base.ok || base.kind !== 'pdf') return base
  if (await hasPdfMagic(file)) return base
  return {
    ok: false,
    kind: 'pdf',
    error: `「${file.name}」的内容不是 PDF（可能只是改了后缀），请在源文件里另存为 PDF 后重试`,
  }
}

/** 真实上传百分比：0–100；total 未知时返回 0（视图用不确定态） */
export function uploadPercent(loaded: number, total: number): number {
  if (!Number.isFinite(total) || total <= 0) return 0
  if (!Number.isFinite(loaded) || loaded <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((loaded / total) * 100)))
}

export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value.trim())
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

/* ─────────────────────────────────────────────────────────────
   第三方平台取件（v1.90.0）
   ───────────────────────────────────────────────────────────── */

export type MobileOs = 'ios' | 'android' | 'harmony' | 'other'

export function detectMobileOS(ua?: string): MobileOs {
  const s = ua ?? (typeof navigator === 'undefined' ? '' : navigator.userAgent)
  if (!s) return 'other'
  // HarmonyOS 的 UA 通常也带 Android，必须先判
  if (/HarmonyOS/i.test(s)) return 'harmony'
  if (/iPhone|iPad|iPod/i.test(s)) return 'ios'
  if (/Android/i.test(s)) return 'android'
  return 'other'
}

export interface ResumeImportPlatform {
  key: string
  name: string
  /** 平台卡片角标文字（不用外部 logo，避免资源与商标问题） */
  badge: string
  /** 官方 App scheme；未登记的平台只提供网页版，不猜 */
  scheme?: string
  webUrl: string
  exportHint: string
}

/**
 * 平台清单。
 *
 * 说明：BOSS / 猎聘 / 智联 / 前程无忧都没有公开的简历导出 API，
 * 这里只做「唤起 App + 告诉用户在哪导出」与「打开网页版」，
 * 不承诺自动登录、不保存第三方登录态（DESIGN.md §7）。
 * scheme 有效性由 App 方维护，所有入口都保留网页版与本机文件兜底。
 */
export const RESUME_IMPORT_PLATFORMS: ResumeImportPlatform[] = [
  {
    key: 'boss',
    name: 'BOSS直聘',
    badge: 'BOSS',
    scheme: 'bosszp://',
    webUrl: 'https://www.zhipin.com/',
    exportHint: '打开「我的简历」，有导出入口就导出 PDF；没有导出入口时直接截图，回来用「选择相册截图」。',
  },
  {
    key: 'liepin',
    name: '猎聘',
    badge: '猎',
    scheme: 'liepin://',
    webUrl: 'https://www.liepin.com/',
    exportHint: '在「我的简历」里找附件简历或导出；找不到时截图，回来用「选择相册截图」。',
  },
  {
    key: 'zhaopin',
    name: '智联招聘',
    badge: '智',
    scheme: 'zhaopin://',
    webUrl: 'https://www.zhaopin.com/',
    exportHint: '在「简历中心」下载附件简历为 PDF；没有下载入口时截图。',
  },
  {
    key: '51job',
    name: '前程无忧',
    badge: '51',
    scheme: '51job://',
    webUrl: 'https://www.51job.com/',
    exportHint: '在「我的简历」导出附件简历为 PDF；没有导出入口时截图。',
  },
  {
    key: 'wechat',
    name: '微信文件',
    badge: '微',
    scheme: 'weixin://',
    webUrl: 'https://weixin.qq.com/',
    exportHint: '文件传输助手里的简历：手机上直接截图；或到电脑浏览器打开 filehelper.weixin.qq.com 下载后上传。',
  },
  {
    key: 'wps',
    name: 'WPS',
    badge: 'WPS',
    scheme: 'wps://',
    webUrl: 'https://www.wps.cn/',
    exportHint: '打开文档 → 分享 → 导出为 PDF，保存到手机后再回来上传。',
  },
]

export type LaunchMode = 'app' | 'web' | 'blocked'

export interface LaunchDecisionInput {
  mobile: boolean
  inAppBrowser: string
  hasScheme: boolean
}

/**
 * 唤起路径决策：
 * - 桌面端 → 网页版
 * - 微信/钉钉等内置浏览器 → 宿主会拦截 scheme，直接走网页版并说明原因
 * - 普通手机浏览器 + 有 scheme → 尝试唤起 App
 */
export function decideLaunchMode(input: LaunchDecisionInput): LaunchMode {
  if (!input.mobile) return 'web'
  if (input.inAppBrowser) return 'blocked'
  return input.hasScheme ? 'app' : 'web'
}

/** 从 App 返回后的继续入口文案（不断言 App 是否安装） */
export function returnFromAppHint(name: string): string {
  return `已尝试打开「${name}」。导出 PDF 后切回这里，点「选择已导出的 PDF」继续`
}

/** 分享链接授权说明（所有入口统一口径） */
export const THIRD_PARTY_CONSENT_TEXT =
  '我确认有权导入该简历，且链接可被公开访问'

export const THIRD_PARTY_BOUNDARY_NOTE =
  '平台不提供简历导出接口，本平台也不会保存你的第三方账号登录态。需要登录才能打开的分享页无法导入，请在对应 App 内导出 PDF 后再上传。'
