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

/* ─────────────────────────────────────────────────────────────
   多张截图合并识别（v1.90.2）
   手机截屏由系统自动进相册，用户不需要导出/下载；BOSS 等在线简历常跨 2–3 屏，
   允许一次选多张。后端单请求上限 10MB（spring.multipart.max-request-size）。
   ───────────────────────────────────────────────────────────── */

export const MAX_SCREENSHOT_COUNT = 5
export const MAX_SCREENSHOT_TOTAL_BYTES = 10 * 1024 * 1024

export interface ScreenshotBatchCheck {
  ok: boolean
  error?: string
}

export function validateScreenshotBatch(files: ResumeFileLike[]): ScreenshotBatchCheck {
  if (!files.length) {
    return { ok: false, error: '请至少选择一张简历截图' }
  }
  if (files.length > MAX_SCREENSHOT_COUNT) {
    return { ok: false, error: `一次最多合并 ${MAX_SCREENSHOT_COUNT} 张截图` }
  }
  let total = 0
  for (const file of files) {
    const check = validateResumeFile(file, { allowImages: true })
    if (!check.ok) {
      return { ok: false, error: check.error }
    }
    if (check.kind !== 'image') {
      return {
        ok: false,
        error: `「${file.name}」不是截图。这个入口只收 PNG / JPG / WEBP / GIF`,
      }
    }
    total += file.size
  }
  if (total > MAX_SCREENSHOT_TOTAL_BYTES) {
    return {
      ok: false,
      error: `多张截图合计不能超过 10MB（当前 ${formatFileSize(total)}），请减少张数或压缩后再试`,
    }
  }
  return { ok: true }
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
  /** 唤起后留在页面上的取件向导：在对应 App 内 3 步取到简历（截图优先） */
  steps: string[]
  /** 存到「文件」/「保存到手机」的菜单名在两端不同，分开写 */
  stepsIos?: string[]
  stepsAndroid?: string[]
  /**
   * 该平台的简历来自聊天记录时，取件面板把哪个位置提为主按钮。
   * 只对确有「聊天里收文件」场景的平台声明（目前是微信）。
   */
  chatPickSource?: string
}

/**
 * 平台清单。
 *
 * 说明：BOSS / 猎聘 / 智联 / 前程无忧都没有公开的简历导出 API，
 * 也没有任何 Web 标准能读取其他 App 的私有文件。手机截屏由系统自动写入相册，
 * 是唯一「不用导出、不用下载保存」的通用取件动作，因此步骤默认教截图，
 * 能导出 PDF 时再走文件选择器。不承诺自动登录、不保存第三方登录态（DESIGN.md §7）。
 */
export const RESUME_IMPORT_PLATFORMS: ResumeImportPlatform[] = [
  {
    key: 'boss',
    name: 'BOSS直聘',
    badge: 'BOSS',
    scheme: 'bosszp://',
    webUrl: 'https://www.zhipin.com/',
    exportHint: '「我的简历」通常不提供导出：直接分屏截图（自动进相册），回来选截图；有导出入口时选 PDF 更准。',
    steps: [
      '在 App 里打开「我的」→「我的简历」',
      '分屏截图 1–3 张（截屏自动进相册，不用导出下载）',
      '切回本页，点「选择截图，AI 直接提取」',
    ],
  },
  {
    key: 'liepin',
    name: '猎聘',
    badge: '猎',
    scheme: 'liepin://',
    webUrl: 'https://www.liepin.com/',
    exportHint: '「我的简历」多数只支持在线查看：直接分屏截图，回来选截图；能导出附件简历时选 PDF 更准。',
    steps: [
      '在 App 里打开「我的简历」',
      '分屏截图 1–3 张（自动进相册，不用下载）',
      '切回本页，点「选择截图，AI 直接提取」',
    ],
  },
  {
    key: 'zhaopin',
    name: '智联招聘',
    badge: '智',
    scheme: 'zhaopin://',
    webUrl: 'https://www.zhaopin.com/',
    exportHint: '「简历中心」可在线上查看：直接分屏截图，回来选截图；能下载附件简历时选 PDF 更准。',
    steps: [
      '在 App 里打开「简历中心」',
      '分屏截图 1–3 张（自动进相册，不用下载）',
      '切回本页，点「选择截图，AI 直接提取」',
    ],
  },
  {
    key: '51job',
    name: '前程无忧',
    badge: '51',
    scheme: '51job://',
    webUrl: 'https://www.51job.com/',
    exportHint: '「我的简历」多数只支持在线查看：直接分屏截图，回来选截图；能导出附件简历时选 PDF 更准。',
    steps: [
      '在 App 里打开「我的」→「我的简历」',
      '分屏截图 1–3 张（自动进相册，不用下载）',
      '切回本页，点「选择截图，AI 直接提取」',
    ],
  },
  {
    key: 'wechat',
    name: '微信文件',
    badge: '微',
    scheme: 'weixin://',
    webUrl: 'https://weixin.qq.com/',
    exportHint: '聊天里收到的简历 PDF：点开 → 右上角 ⋯ → 存一次到手机，回来直接选；不想保存就截图提取。',
    steps: [
      '在微信聊天里点开对方发来的简历 PDF',
      '右上角 ⋯ →「保存到手机」',
      '切回本页，点「选择聊天里的 PDF」，在「下载」里选它',
    ],
    stepsIos: [
      '在微信聊天里点开对方发来的简历 PDF',
      '右上角 ⋯ →「存储到「文件」」',
      '切回本页，点「选择聊天里的 PDF」，在「浏览 → 我的 iPhone / iCloud 云盘」里选它',
    ],
    stepsAndroid: [
      '在微信聊天里点开对方发来的简历 PDF',
      '右上角 ⋯ →「保存到手机」',
      '切回本页，点「选择聊天里的 PDF」，在「下载」或「文件管理」里选它',
    ],
    chatPickSource: 'wechat',
  },
  {
    key: 'wps',
    name: 'WPS',
    badge: 'WPS',
    scheme: 'wps://',
    webUrl: 'https://www.wps.cn/',
    exportHint: '文档已在 WPS 里：逐页截图最省事；也可以「分享」→「导出为 PDF」后回来选择文件。',
    steps: [
      '在 WPS 里打开简历文档',
      '逐页截图（自动进相册），或「分享」→「导出为 PDF」',
      '切回本页选截图；已有 PDF 也可直接选',
    ],
  },
]

/* ─────────────────────────────────────────────────────────────
   取件向导（v1.90.1 / v1.90.2）

   真机问题：点「在 App 中打开」后跳进了对应 App，回到浏览器却没有任何后续；
   即使有后续，原来的主路径也要求用户先导出、再下载保存，手机端多一道门槛。

   现在的主路径改成**截图提取**：截屏由系统自动写入相册，不需要导出或下载，
   回到本页选中 1–5 张截图即可识别。PDF 选择保留为次路径：
   - 面板在唤起的同时打开，切回来时仍在；
   - 位置列表点击后统一打开系统文件选择器（网页无法直接读取其他 App 的文件），
     不同位置只影响提示文案，不假装能直连微信 / WPS。
   ───────────────────────────────────────────────────────────── */

export type IntakeSheetPhase = 'launching' | 'ready'

/**
 * 取件步骤的系统差异只在「存到哪」这一步：
 * iOS 的 WeChat 是「存储到「文件」」+「浏览 → 我的 iPhone / iCloud」，
 * Android / HarmonyOS 是「保存到手机」+「下载 / 文件管理」。
 * 没有单独文案的平台统一回落到 steps。
 */
export function resolvePlatformSteps(platform: ResumeImportPlatform, os: MobileOs): string[] {
  if (os === 'ios' && platform.stepsIos) return platform.stepsIos
  if ((os === 'android' || os === 'harmony') && platform.stepsAndroid) return platform.stepsAndroid
  return platform.steps
}

export interface ResumePickSource {
  key: string
  /** 列表左侧角标（沿用平台卡片的文字角标风格） */
  badge: string
  label: string
  /** 系统选择器里该去哪找 */
  where: string
  whereIos?: string
  whereAndroid?: string
}

export const RESUME_PICK_SOURCES: ResumePickSource[] = [
  {
    key: 'wechat',
    badge: '微',
    label: '微信聊天里的 PDF',
    where: '先点开 PDF 存到手机，再回到系统文件选择器里选它',
    whereIos: '微信里点开 PDF → 右上角 ⋯ →「存储到「文件」」；回来在「浏览 → 我的 iPhone / iCloud 云盘」里找',
    whereAndroid: '微信里点开 PDF → 右上角 ⋯ →「保存到手机」；回来在「下载」或「文件管理」里找',
  },
  {
    key: 'files',
    badge: '文',
    label: '手机文件 · 下载',
    where: '在「下载」「我的文件」或「浏览」里找刚保存的 PDF',
  },
  {
    key: 'wps',
    badge: 'WPS',
    label: 'WPS · 云文档',
    where: '先在 WPS 里导出到手机；在选择器里点 WPS 或对应网盘',
  },
  {
    key: 'other',
    badge: '⋯',
    label: '其他位置',
    where: '打开系统文件选择器，自己找 PDF',
  },
]

/** 按当前系统取「该去哪找」；两端菜单名不同，桌面/未知系统用通用说法 */
export function resolvePickWhere(source: ResumePickSource, os: MobileOs): string {
  if (os === 'ios' && source.whereIos) return source.whereIos
  if ((os === 'android' || os === 'harmony') && source.whereAndroid) return source.whereAndroid
  return source.where
}

/** 微信平台下的主按钮：直接打开系统文件选择器选聊天里存下的 PDF */
export const INTAKE_CHAT_PICK_CTA = '选择聊天里的 PDF'

/**
 * 「为什么还要存一次」的说明。
 * 网页拿不到微信聊天记录，`wx.chooseMessageFile` 只在小程序里可用；
 * 不写清这一点，用户会以为是我们没做直连。这是平台边界，不是实现偷懒。
 */
export const WECHAT_FILE_PICK_NOTE =
  '微信不向网页开放聊天记录，网页无法直接列出聊天里的文件；在微信里存一次到手机，回来就能选。不想保存可改用截图提取。'

/** 截图主路径文案（非聊天取件平台的第一视觉层，强调「不用下载保存」） */
export const INTAKE_SHOT_TITLE = '最快：截图提取，不用下载保存'
/** 聊天取件平台里截图退为备选（PDF 才是用户本来要的东西） */
export const INTAKE_SHOT_TITLE_NO_SAVE = '不想保存？直接截图提取'
export const INTAKE_SHOT_DESC =
  '在 App 里分屏截图（1–5 张，截屏自动进相册）；回来选中截图，AI 直接提取文字并分析。'
export const INTAKE_SHOT_CTA = '选择截图，AI 直接提取'

/** 微信平台下位置列表已去掉微信行，标题相应改成「其他位置」 */
export const INTAKE_PICK_TITLE_OTHER = '其他位置的 PDF'

export const INTAKE_SHEET_NOTE =
  '截图提取不用下载或保存：截屏由系统自动存进相册。选 PDF 时会打开系统文件选择器——网页无法直接读取其他 App 的文件。'

export function intakeSheetTitle(phase: IntakeSheetPhase, name: string): string {
  return phase === 'launching' ? `正在尝试打开「${name}」` : `从「${name}」提取简历`
}

/**
 * 面板引导文案。
 * 交棒信号只能说明「页面被切走过」，不能断言用户已经完成截图/导出，
 * 因此 handedOff 时用问句，不做「你已截图」的承诺。
 */
export function intakeSheetLead(phase: IntakeSheetPhase, handedOff: boolean): string {
  if (phase === 'launching') {
    return '面板会保留：在 App 里截图后切回来，直接选截图，AI 提取文字。'
  }
  return handedOff
    ? '切回来了？直接选刚截的图（1–5 张可一起选），AI 会提取文字并分析。'
    : '没有跳转成功也不影响：可以重试或打开网页版；也可以先截图，再回来选择。'
}

/** 微信取件面板的引导：主路径是聊天里的 PDF，截图是备选 */
export function wechatIntakeLead(phase: IntakeSheetPhase, handedOff: boolean): string {
  if (phase === 'launching') {
    return '面板会保留：在聊天里点开 PDF → 右上角 ⋯ 存到手机，然后切回来选它。'
  }
  return handedOff
    ? '切回来了？在聊天里点开 PDF → 右上角 ⋯ 存到手机，再点下方「选择聊天里的 PDF」。'
    : '没跳转成功也不影响：手动打开微信，点开 PDF → 右上角 ⋯ 存到手机，再回来选。'
}

/** 微信平台交棒后的提示（与面板主按钮同名，避免两套说法） */
export function wechatReturnFromAppHint(): string {
  return '在微信里点开 PDF → 右上角 ⋯ 存到手机，回来点「选择聊天里的 PDF」'
}

/**
 * 微信内置浏览器的选文件限制提示。
 * 安卓微信的 `<input type="file">` 通常只给「拍照 / 相册」，iOS 也只给照片库和文件 App，
 * 聊天里的 PDF 不在其中；跳出到系统浏览器后，选择器才会列出刚存下的文件。
 */
export const WECHAT_IN_APP_HINT =
  '在微信内置浏览器里，选择文件通常只给相册，找不到聊天里的 PDF。点右上角 ⋯ →「在浏览器打开」，再回来选。'

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
  return `已尝试打开「${name}」。截图后切回这里，点「选择截图，AI 直接提取」继续`
}

/** 分享链接授权说明（所有入口统一口径） */
export const THIRD_PARTY_CONSENT_TEXT =
  '我确认有权导入该简历，且链接可被公开访问'

export const THIRD_PARTY_BOUNDARY_NOTE =
  '平台不提供简历导出接口，本平台也不会保存你的第三方账号登录态。需要登录才能打开的分享页无法导入，请在对应 App 内导出 PDF 后再上传。'
