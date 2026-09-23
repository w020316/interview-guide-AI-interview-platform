import { describe, it, expect } from 'vitest'
import { validateJobKeyword, JOB_KEYWORD_MAX_LENGTH } from './jobKeyword'

describe('utils/jobKeyword validateJobKeyword', () => {
  it('空值与纯空白视为合法（没输入就不需要校验）', () => {
    expect(validateJobKeyword('')).toBeNull()
    expect(validateJobKeyword('   ')).toBeNull()
  })

  it('常见技术关键词不被误伤（白名单不能挡掉真实搜索）', () => {
    for (const kw of ['Java', 'C++', 'C#', 'node.js', 'Java/Python', '前端 开发', 'Vue3.0', 'A&B']) {
      expect(validateJobKeyword(kw)).toBeNull()
    }
  })

  it('含注入特征的输入在发请求前就被拒绝（P2-04）', () => {
    // 线上实测：带合法 JWT 请求 GET /api/jobs?keyword=' OR 1=1 仍返回 WAF 的 403 页面
    // —— 请求在到达应用之前就被拦掉，后端入口校验没有机会执行。
    // 所以这些输入必须在**发请求之前**被前端挡住，否则用户只会看到网络错误。
    for (const kw of [
      "' OR 1=1",
      "1' or '1'='1",
      'union select 1,2',
      '<script>alert(1)</script>',
      'a;b',
      'a%b',
    ]) {
      expect(validateJobKeyword(kw), `应拒绝：${kw}`).toContain('不支持的字符')
    }
  })

  it('超长关键词被拒绝，恰好等于上限时放行', () => {
    expect(validateJobKeyword('a'.repeat(JOB_KEYWORD_MAX_LENGTH))).toBeNull()
    expect(validateJobKeyword('a'.repeat(JOB_KEYWORD_MAX_LENGTH + 1))).toContain('过长')
  })

  it('前后空白不计入长度（与后端 trim 口径一致）', () => {
    expect(validateJobKeyword('  ' + 'a'.repeat(JOB_KEYWORD_MAX_LENGTH) + '  ')).toBeNull()
  })
})
