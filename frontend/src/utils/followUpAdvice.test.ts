import { describe, expect, it } from 'vitest'
import { daysSince, followUpAdvice } from './followUpAdvice'

const NOW = new Date('2026-10-10T12:00:00').getTime()
const daysAgo = (n: number): string => new Date(NOW - n * 86_400_000).toISOString()

describe('followUpAdvice —— 投递跟进建议（N4，纯本地规则）', () => {
  it('已淘汰 / 已放弃：不产生建议（别再催）', () => {
    expect(followUpAdvice({ status: 'REJECTED' }, NOW)).toBeNull()
    expect(followUpAdvice({ status: 'WITHDRAWN' }, NOW)).toBeNull()
  })

  it('未知状态：不产生建议（宁可不说，也不要乱指挥）', () => {
    expect(followUpAdvice({ status: 'WHAT' }, NOW)).toBeNull()
  })

  it('待投递：提醒尽快投，不代拟消息', () => {
    const a = followUpAdvice({ status: 'PLANNED' }, NOW)
    expect(a?.urgency).toBe('normal')
    expect(a?.action).toContain('投递')
    expect(a?.message).toBe('')
  })

  it('已投递 < 7 天：建议等待、不必催', () => {
    const a = followUpAdvice({ status: 'APPLIED', title: '后端', appliedAt: daysAgo(3) }, NOW)
    expect(a?.reason).toContain('3 天')
    expect(a?.urgency).toBe('normal')
    expect(a?.message).toBe('')
  })

  it('已投递 7 天：可跟进一次，消息含岗位名', () => {
    const a = followUpAdvice({ status: 'APPLIED', title: 'Java 后端', appliedAt: daysAgo(7) }, NOW)
    expect(a?.urgency).toBe('normal')
    expect(a?.message).toContain('Java 后端')
  })

  it('已投递 ≥14 天：升级为 high，并建议把精力分散', () => {
    const a = followUpAdvice({ status: 'VIEWED', title: '前端', appliedAt: daysAgo(20) }, NOW)
    expect(a?.urgency).toBe('high')
    expect(a?.reason).toContain('20 天')
    expect(a?.action).toContain('别的岗位')
  })

  it('appliedAt 缺失时回退 updatedAt', () => {
    const a = followUpAdvice({ status: 'APPLIED', title: 'X', updatedAt: daysAgo(10) }, NOW)
    expect(a?.reason).toContain('10 天')
  })

  it('对方已回复：提示尽快回信且带消息', () => {
    const a = followUpAdvice({ status: 'REPLIED' }, NOW)
    expect(a?.urgency).toBe('high')
    expect(a?.message).not.toBe('')
  })

  it('面试后 ≥3 天未推进：建议致谢并问结果（消息含公司名）', () => {
    const a = followUpAdvice({ status: 'INTERVIEW', companyName: '腾讯', lastReplyAt: daysAgo(4) }, NOW)
    expect(a?.urgency).toBe('high')
    expect(a?.action).toContain('致谢')
    expect(a?.message).toContain('腾讯')
  })

  it('面试后 < 3 天：只提示保持存在感，不催', () => {
    const a = followUpAdvice({ status: 'INTERVIEW', lastReplyAt: daysAgo(1) }, NOW)
    expect(a?.urgency).toBe('normal')
    expect(a?.message).toBe('')
  })

  it('收到 Offer：high，问清回复截止与入职材料', () => {
    const a = followUpAdvice({ status: 'OFFER', companyName: '阿里' }, NOW)
    expect(a?.urgency).toBe('high')
    expect(a?.message).toContain('阿里')
    expect(a?.message).toContain('Offer')
  })

  it('公司 / 岗位缺失时用占位词，不拼出空洞句子', () => {
    const a = followUpAdvice({ status: 'APPLIED', appliedAt: daysAgo(9) }, NOW)
    expect(a?.message).toContain('该岗位')
  })

  it('daysSince：不可解析返回 null，未来时间为 0', () => {
    expect(daysSince('不是日期', NOW)).toBeNull()
    expect(daysSince(null, NOW)).toBeNull()
    expect(daysSince(new Date(NOW + 3_600_000).toISOString(), NOW)).toBe(0)
  })
})
