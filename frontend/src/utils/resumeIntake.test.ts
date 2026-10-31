import { describe, it, expect } from 'vitest'
import {
  MAX_RESUME_BYTES,
  decideLaunchMode,
  detectMobileOS,
  extensionOf,
  fileKind,
  formatFileSize,
  hasPdfMagic,
  isHttpUrl,
  returnFromAppHint,
  uploadPercent,
  validateResumeFile,
  validateResumeFileDeep,
  RESUME_IMPORT_PLATFORMS,
} from './resumeIntake'

describe('utils/resumeIntake · 文件识别与校验', () => {
  it('按扩展名分类（大小写不敏感）', () => {
    expect(fileKind('简历.PDF')).toBe('pdf')
    expect(fileKind('resume.TXT')).toBe('document')
    expect(fileKind('resume.md')).toBe('document')
    expect(fileKind('resume.HTM')).toBe('document')
    expect(fileKind('截图.PNG')).toBe('image')
    expect(fileKind('resume.docx')).toBe('unknown')
    expect(fileKind('resume')).toBe('unknown')
    expect(extensionOf('a.b.pdf')).toBe('.pdf')
  })

  it('拒绝空文件与超过 10MB 的文件，并给出可执行提示', () => {
    expect(validateResumeFile({ name: 'a.pdf', size: 0 }).error).toBe('文件为空，请重新选择')
    const tooBig = validateResumeFile({ name: 'a.pdf', size: MAX_RESUME_BYTES + 1 })
    expect(tooBig.ok).toBe(false)
    expect(tooBig.error).toContain('不能超过 10MB')
    expect(tooBig.error).toContain('10.0 MB')
  })

  it('不支持的格式明确指出文件名与支持范围', () => {
    const result = validateResumeFile({ name: '我的简历.docx', size: 1024 })
    expect(result.ok).toBe(false)
    expect(result.kind).toBe('unknown')
    expect(result.error).toContain('我的简历.docx')
    expect(result.error).toContain('PDF / HTML / MD / TXT')
  })

  it('主入口拦截图片并指向相册通道', () => {
    const result = validateResumeFile({ name: 'shot.png', size: 1024 })
    expect(result.ok).toBe(false)
    expect(result.error).toContain('选择相册截图')
    expect(validateResumeFile({ name: 'shot.png', size: 1024 }, { allowImages: true }).ok).toBe(true)
  })

  it('PDF magic bytes：真 PDF 通过，改后缀的假 PDF 被拦下', async () => {
    const real = new File(['%PDF-1.7\nrest'], 'resume.pdf', { type: 'application/pdf' })
    const fake = new File(['PK\u0003\u0004 this is docx'], 'resume.pdf', { type: 'application/pdf' })

    expect(await hasPdfMagic(real)).toBe(true)
    expect(await hasPdfMagic(fake)).toBe(false)

    expect((await validateResumeFileDeep(real)).ok).toBe(true)
    const fakeCheck = await validateResumeFileDeep(fake)
    expect(fakeCheck.ok).toBe(false)
    expect(fakeCheck.error).toContain('内容不是 PDF')
  })

  it('非 PDF 文件不做 magic 校验，直接通过浅校验', async () => {
    const txt = new File(['hello'], 'resume.txt', { type: 'text/plain' })
    expect((await validateResumeFileDeep(txt)).ok).toBe(true)
  })
})

describe('utils/resumeIntake · 进度与 URL', () => {
  it('上传百分比只做真实换算并夹在 0–100', () => {
    expect(uploadPercent(0, 100)).toBe(0)
    expect(uploadPercent(32, 100)).toBe(32)
    expect(uploadPercent(100, 100)).toBe(100)
    expect(uploadPercent(120, 100)).toBe(100)
    expect(uploadPercent(10, 0)).toBe(0)
    expect(uploadPercent(Number.NaN, 100)).toBe(0)
  })

  it('文件大小格式化', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2.0 KB')
    expect(formatFileSize(3 * 1024 * 1024)).toBe('3.0 MB')
  })

  it('只接受 http/https 链接', () => {
    expect(isHttpUrl('https://example.com/a.pdf')).toBe(true)
    expect(isHttpUrl(' http://example.com/a.pdf ')).toBe(true)
    expect(isHttpUrl('ftp://example.com/a.pdf')).toBe(false)
    expect(isHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpUrl('not a url')).toBe(false)
  })
})

describe('utils/resumeIntake · 移动端识别与唤起决策', () => {
  it('识别 iOS / Android / HarmonyOS，桌面返回 other', () => {
    expect(detectMobileOS('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)')).toBe('ios')
    expect(detectMobileOS('Mozilla/5.0 (Linux; Android 14; Pixel 8)')).toBe('android')
    expect(detectMobileOS('Mozilla/5.0 (Linux; Android 12; HarmonyOS; ABC)')).toBe('harmony')
    expect(detectMobileOS('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('other')
    expect(detectMobileOS('')).toBe('other')
  })

  it('桌面走网页版；内置浏览器直接 blocked；普通手机浏览器才唤起 App', () => {
    expect(decideLaunchMode({ mobile: false, inAppBrowser: '', hasScheme: true })).toBe('web')
    expect(decideLaunchMode({ mobile: true, inAppBrowser: 'wechat', hasScheme: true })).toBe('blocked')
    expect(decideLaunchMode({ mobile: true, inAppBrowser: '', hasScheme: true })).toBe('app')
    expect(decideLaunchMode({ mobile: true, inAppBrowser: '', hasScheme: false })).toBe('web')
  })

  it('从 App 返回文案不断言「未安装 / 未检测到」', () => {
    const hint = returnFromAppHint('BOSS直聘')
    expect(hint).toContain('BOSS直聘')
    expect(hint).toContain('选择已导出的 PDF')
    expect(hint).not.toContain('未安装')
    expect(hint).not.toContain('未检测到')
  })
})

describe('utils/resumeIntake · 平台清单', () => {
  it('平台 key 唯一、都有 https 网页版与导出说明', () => {
    const keys = RESUME_IMPORT_PLATFORMS.map((p) => p.key)
    expect(new Set(keys).size).toBe(keys.length)
    for (const platform of RESUME_IMPORT_PLATFORMS) {
      expect(platform.webUrl.startsWith('https://')).toBe(true)
      expect(platform.exportHint.length).toBeGreaterThan(8)
      expect(platform.name.length).toBeGreaterThan(0)
    }
  })

  it('不猜测未登记平台的 scheme（只对有把握的给出 App 直达）', () => {
    expect(RESUME_IMPORT_PLATFORMS.find((p) => p.key === 'boss')?.scheme).toBe('bosszp://')
    expect(RESUME_IMPORT_PLATFORMS.find((p) => p.key === 'wechat')?.scheme).toBe('weixin://')
    for (const platform of RESUME_IMPORT_PLATFORMS) {
      if (platform.scheme) expect(platform.scheme).toMatch(/^[a-z0-9]+:\/\/$/i)
    }
  })
})
