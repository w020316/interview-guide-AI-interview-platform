import { describe, it, expect } from 'vitest'
import { parseNotice, hasAnyField } from './noticeParse'

/**
 * 投递通知识别（第三批 E）单测。
 *
 * 重点验证两条红线：
 *  1. **不猜假值**——无法识别时字段必须为 null、confidence='none'，绝不编造公司/岗位/时间；
 *  2. **时间必须同时有日期与明确时刻**——只写日期（无时刻）时 interviewAt 为 null。
 */
describe('parseNotice 投递通知识别', () => {
  it('中文完整通知：公司 + 岗位 + 阶段 + 时间 全部识别，置信度 high', () => {
    const r = parseNotice('【字节跳动】感谢您的投递，应聘的「Java 后端工程师」岗位已进入面试环节，面试时间 2026-09-05 14:00，地点：北京')
    expect(r.company).toBe('字节跳动')
    expect(r.title).toBe('Java 后端工程师')
    expect(r.stage).toBe('INTERVIEW')
    expect(r.stageLabel).toBe('面试中')
    expect(r.interviewAt).toBe('2026-09-05T14:00')
    expect(r.confidence).toBe('high')
    expect(hasAnyField(r)).toBe(true)
    expect(r.matched.some((m) => m.startsWith('阶段：'))).toBe(true)
  })

  it('英文完整通知：for the <role> interview at <company>，含 PM 时刻归一', () => {
    const r = parseNotice('Dear candidate, we would like to invite you for the Backend Engineer interview at Acme Corp on 2026-09-05 at 3:00 PM.')
    expect(r.stage).toBe('INTERVIEW')
    expect(r.title).toBe('Backend Engineer')
    expect(r.company).toBe('Acme Corp')
    expect(r.interviewAt).toBe('2026-09-05T15:00')
    expect(r.confidence).toBe('high')
  })

  it('只识别到阶段：confidence=low，其余字段为 null（不猜）', () => {
    const r = parseNotice('您的简历已被查看')
    expect(r.stage).toBe('VIEWED')
    expect(r.company).toBeNull()
    expect(r.title).toBeNull()
    expect(r.interviewAt).toBeNull()
    expect(r.confidence).toBe('low')
    expect(hasAnyField(r)).toBe(true)
  })

  it('Offer 通知：阶段识别为 OFFER，未写岗位则 title 保持 null', () => {
    const r = parseNotice('Congratulations! We are pleased to offer you the position of Senior Data Engineer at Globex Inc.')
    expect(r.stage).toBe('OFFER')
    expect(r.company).toBe('Globex Inc')
    expect(r.title).toBe('Senior Data Engineer')
    expect(r.confidence).toBe('high')
  })

  it('日期但无明确时刻：interviewAt 必须为 null（不得用 00:00 补齐）', () => {
    const r = parseNotice('已投递成功，请于 2026-09-05 前保持电话畅通')
    expect(r.interviewAt).toBeNull()
    expect(r.stage).toBe('APPLIED')
  })

  it('纯时刻但无日期：同样不产出 interviewAt', () => {
    const r = parseNotice('面试时间是 14:00，请准时参加')
    expect(r.interviewAt).toBeNull()
    expect(r.stage).toBe('INTERVIEW')
  })

  it('仅公司：confidence=low，title/stage 为 null', () => {
    const r = parseNotice('【腾讯】招聘季活动开始啦')
    expect(r.company).toBe('腾讯')
    expect(r.title).toBeNull()
    expect(r.stage).toBeNull()
    expect(r.confidence).toBe('low')
  })

  it('完全无法识别：confidence=none 且所有字段为 null（绝不编造）', () => {
    const r = parseNotice('今天天气不错，适合出去走走')
    expect(r.confidence).toBe('none')
    expect(r.company).toBeNull()
    expect(r.title).toBeNull()
    expect(r.stage).toBeNull()
    expect(r.interviewAt).toBeNull()
    expect(r.matched).toEqual([])
    expect(hasAnyField(r)).toBe(false)
  })

  it('空串 / 纯空白 / 非字符串：均回落为未识别', () => {
    expect(parseNotice('').confidence).toBe('none')
    expect(parseNotice('   ').confidence).toBe('none')
    // @ts-expect-error 模拟运行时传入非字符串
    expect(parseNotice(undefined).confidence).toBe('none')
  })

  it('中文月日无年份：用当前年补全（仅此一处允许推断年份）', () => {
    const y = new Date().getFullYear()
    const r = parseNotice('面试邀约：9月5日 14:30 线上面试')
    expect(r.interviewAt).toBe(`${y}-09-05T14:30`)
    expect(r.stage).toBe('INTERVIEW')
  })
})
