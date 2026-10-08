import { describe, expect, it } from 'vitest'
import {
  daysText,
  eventStatusText,
  kindLabel,
  laggingHintText,
  summaryText,
  timelineAtText,
  type StatusLaggingItem,
  type TimelineStats,
} from './timeline'

/**
 * 投递 ↔ 面试时序视图工具测试（v1.61.0）
 *
 * 守卫重点：**「无数据」与「0」必须显示得不一样**。
 * 本项目已因此复发过两次（P95 无样本显示 0、简历兜底 0 分），这里从展示层再锁一道。
 */

function stats(partial: Partial<TimelineStats> = {}): TimelineStats {
  return {
    appliedCount: 0,
    interviewCount: 0,
    linkedInterviewCount: 0,
    unlinkedInterviewCount: 0,
    interviewSampleSize: 0,
    avgDaysToInterview: null,
    medianDaysToInterview: null,
    ...partial,
  }
}

describe('daysText', () => {
  it('null / undefined → 占位符，不是 0', () => {
    expect(daysText(null)).toBe('—')
    expect(daysText(undefined)).toBe('—')
  })

  it('0 天显示「同一天」，而不是「0 天」', () => {
    expect(daysText(0)).toBe('同一天')
  })

  it('不足 1 天折算成小时（0.3 天 → 7 小时）', () => {
    expect(daysText(0.3)).toBe('7 小时')
    expect(daysText(0.5)).toBe('12 小时')
  })

  it('整数天直接展示', () => {
    expect(daysText(4)).toBe('4 天')
    expect(daysText(4.2)).toBe('4.2 天')
  })
})

describe('summaryText', () => {
  it('样本为 0 时明确说「没有可比较记录」，绝不显示 0 天', () => {
    const t = summaryText(stats())
    expect(t).toContain('还没有')
    expect(t).not.toContain('平均 0')
    expect(t).not.toContain('中位 0')
  })

  it('样本为 0 但平均值为 null —— 仍然不得输出现假数据', () => {
    const t = summaryText(stats({ interviewSampleSize: 0, avgDaysToInterview: 0 }))
    // 即使后端误传 0（不应发生），只要样本为 0 也必须走「无数据」分支
    expect(t).toContain('还没有')
  })

  it('有样本时给出平均与中位', () => {
    const t = summaryText(stats({ interviewSampleSize: 3, avgDaysToInterview: 5.3, medianDaysToInterview: 4 }))
    expect(t).toContain('3 组')
    expect(t).toContain('平均 5.3 天')
    expect(t).toContain('中位 4 天')
  })

  it('有样本但统计值为 null 时只报数量，不编造天数', () => {
    const t = summaryText(stats({ interviewSampleSize: 2 }))
    expect(t).toContain('2 组')
    expect(t).not.toContain('平均')
  })

  it('null 统计对象返回空串', () => {
    expect(summaryText(null)).toBe('')
    expect(summaryText(undefined)).toBe('')
  })
})

describe('kindLabel / eventStatusText', () => {
  it('节点类型中文', () => {
    expect(kindLabel('APPLIED')).toBe('投递')
    expect(kindLabel('INTERVIEW')).toBe('面试')
    expect(kindLabel('UNKNOWN')).toBe('状态变更')
  })

  it('面试事件状态中文，未知值不猜', () => {
    expect(eventStatusText('UPCOMING')).toBe('待面试')
    expect(eventStatusText('DONE')).toBe('已完成')
    expect(eventStatusText('CANCELLED')).toBe('已取消')
    expect(eventStatusText(null)).toBe('—')
    expect(eventStatusText('WHATEVER')).toBe('—')
  })
})

describe('timelineAtText', () => {
  const now = new Date(2026, 9, 5, 12, 0) // 2026-10-05

  it('同年省略年份', () => {
    expect(timelineAtText('2026-10-01T14:30', now)).toBe('10-01 14:30')
  })

  it('跨年补年份', () => {
    expect(timelineAtText('2025-12-31T09:05', now)).toBe('2025-12-31 09:05')
  })

  it('空值与非法值不抛异常', () => {
    expect(timelineAtText(null, now)).toBe('—')
    expect(timelineAtText('not-a-date', now)).toBe('not-a-date')
  })
})

/**
 * 状态滞后提示文案（v1.65.0）
 *
 * 守卫重点：提示里必须同时出现「面试时间」和「当前状态」——
 * 只给一句「状态待更新」，用户没法判断要不要点。
 */
describe('laggingHintText', () => {
  function item(partial: Partial<StatusLaggingItem> = {}): StatusLaggingItem {
    return {
      applicationId: 1,
      title: 'Java 后端',
      companyName: '腾讯',
      status: 'APPLIED',
      statusLabel: '已投递',
      interviewAt: '2026-09-12T14:00',
      ...partial,
    }
  }

  it('同时给出面试时间与当前状态', () => {
    const text = laggingHintText(item())
    expect(text).toContain('腾讯')
    expect(text).toContain('Java 后端')
    expect(text).toContain('09-12')      // 面试时间
    expect(text).toContain('已投递')      // 当前状态
  })

  it('公司名缺失时不留下孤零零的分隔符', () => {
    const text = laggingHintText(item({ companyName: null }))
    expect(text).not.toContain('·')
    expect(text).toContain('Java 后端')
  })

  it('标题缺失时用占位符而不是空白', () => {
    expect(laggingHintText(item({ title: null }))).toContain('—')
  })
})
