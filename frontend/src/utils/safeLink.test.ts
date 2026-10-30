import { describe, expect, it } from 'vitest'
import { isSafeExternalUrl, safeExternalUrl } from './safeLink'

describe('safeExternalUrl（P1-04）', () => {
  it('放行正常 http/https 链接并 trim 首尾空白', () => {
    expect(safeExternalUrl('https://careers.example.com/jobs/1')).toBe(
      'https://careers.example.com/jobs/1',
    )
    expect(safeExternalUrl('  http://example.com/a?b=1 ')).toBe('http://example.com/a?b=1')
  })

  it.each([
    'javascript:alert(1)',
    'JavaScript:alert(1)',
    '  javascript:fetch(document.cookie)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'blob:https://example.com/uuid',
    'file:///etc/passwd',
    '//evil.example.com',
  ])('拒绝非 http(s) 链接：%s', (url) => {
    expect(safeExternalUrl(url)).toBeNull()
    expect(isSafeExternalUrl(url)).toBe(false)
  })

  it('拒绝含控制字符的链接（java\\nscript: 这类跨解析器绕过）', () => {
    expect(safeExternalUrl('java\nscript:alert(1)')).toBeNull()
    expect(safeExternalUrl('https://example.com/\u0000')).toBeNull()
  })

  it('空白与超长链接按「无链接」处理', () => {
    expect(safeExternalUrl(null)).toBeNull()
    expect(safeExternalUrl(undefined)).toBeNull()
    expect(safeExternalUrl('   ')).toBeNull()
    expect(safeExternalUrl('https://example.com/' + 'a'.repeat(600))).toBeNull()
  })
})
