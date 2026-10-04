import { describe, it, expect } from 'vitest'
import {
  buildReadiness,
  gradeFromCount,
  gradeLabel,
  progressText,
  defaultManual,
  READINESS_TOTAL,
  type ReadinessInput,
} from './readiness'
import { EMPTY } from './format'

/**
 * G 准备度纯函数单测。
 *
 * 重点锁定 R3「无数据 ≠ 0」最关键的一处：
 *   - 全空输入 → grade === null，且任何产出文案都不含「C 级」「0/14」；
 *   - 等级边界 10/11、5/6 逐个验。
 */

function emptyInput(): ReadinessInput {
  return {
    resumeCount: null,
    resumeScore: null,
    favoriteCount: null,
    applicationCount: null,
    appliedCount: null,
    interviewInviteCount: null,
    finishedSessionCount: null,
    interviewAvgScore: null,
    answeredQuestionCount: null,
    questionTotal: null,
    storyCount: null,
    manual: defaultManual(),
  }
}

function fullInput(): ReadinessInput {
  return {
    resumeCount: 1,
    resumeScore: 85,
    favoriteCount: 3,
    applicationCount: 2,
    appliedCount: 2,
    interviewInviteCount: 1,
    finishedSessionCount: 3,
    interviewAvgScore: 75,
    answeredQuestionCount: 10,
    questionTotal: 20,
    storyCount: 3,
    manual: { jdAnalysis: true, selfIntro: true, coverLetter: true },
  }
}

describe('readiness · 空态（R3 核心）', () => {
  it('全空输入 → grade===null、hasData===false、achievedCount===0', () => {
    const r = buildReadiness(emptyInput())
    expect(r.grade).toBeNull()
    expect(r.hasData).toBe(false)
    expect(r.achievedCount).toBe(0)
    expect(r.total).toBe(READINESS_TOTAL)
    expect(r.items).toHaveLength(14)
    expect(r.items.every((i) => !i.achieved)).toBe(true)
  })

  it('空态文案不含「C 级」、不含「0/14」及任何假评级', () => {
    const r = buildReadiness(emptyInput())
    const text = [gradeLabel(r.grade), progressText(r), ...r.items.map((i) => i.label + i.evidence)].join(' | ')
    expect(gradeLabel(r.grade)).toBe('')
    expect(progressText(r)).toBe('')
    expect(text).not.toContain('C 级')
    expect(text).not.toContain('0/14')
    expect(text).not.toContain('0 / 14')
    expect(text).not.toMatch(/A 级|B 级/)
  })
})

describe('readiness · 等级边界（逐个验）', () => {
  it('gradeFromCount：0→null，1..5→C，6..10→B，≥11→A', () => {
    expect(gradeFromCount(0)).toBeNull()
    expect(gradeFromCount(1)).toBe('C')
    expect(gradeFromCount(5)).toBe('C')
    expect(gradeFromCount(6)).toBe('B')
    expect(gradeFromCount(10)).toBe('B')
    expect(gradeFromCount(11)).toBe('A')
    expect(gradeFromCount(14)).toBe('A')
    // 非法输入不产生等级
    expect(gradeFromCount(Number.NaN)).toBeNull()
    expect(gradeFromCount(-3)).toBeNull()
  })

  it('5 项 → C，补到 6 项 → B（集成边界）', () => {
    const base = emptyInput()
    const five: ReadinessInput = {
      ...base,
      resumeCount: 1,
      resumeScore: 85,
      favoriteCount: 1,
      applicationCount: 1,
      appliedCount: 1,
    }
    const c = buildReadiness(five)
    expect(c.achievedCount).toBe(5)
    expect(c.grade).toBe('C')

    const six = buildReadiness({ ...five, interviewInviteCount: 1 })
    expect(six.achievedCount).toBe(6)
    expect(six.grade).toBe('B')
  })

  it('10 项 → B，补到 11 项 → A（集成边界）', () => {
    // 全达成 14 项后关掉 4 项 → 剩 10
    const ten = buildReadiness({
      ...fullInput(),
      storyCount: 0, // 未达成
      manual: { jdAnalysis: false, selfIntro: false, coverLetter: false }, // 关掉 3 个自述
    })
    expect(ten.achievedCount).toBe(10)
    expect(ten.grade).toBe('B')

    const eleven = buildReadiness({
      ...fullInput(),
      storyCount: 0,
      manual: { jdAnalysis: true, selfIntro: false, coverLetter: false },
    })
    expect(eleven.achievedCount).toBe(11)
    expect(eleven.grade).toBe('A')
  })

  it('全达成 → A 且 progressText 无「还差」', () => {
    const r = buildReadiness(fullInput())
    expect(r.achievedCount).toBe(14)
    expect(r.grade).toBe('A')
    expect(progressText(r)).toContain('已完成 14 / 14 项')
    expect(progressText(r)).not.toContain('还差')
  })
})

describe('readiness · 规则细节', () => {
  it('刷题覆盖率 50% 为达成边界；2/5 不达成', () => {
    const r1 = buildReadiness({ ...emptyInput(), answeredQuestionCount: 4, questionTotal: 8 })
    const item1 = r1.items.find((i) => i.id === 'coverage_50')!
    expect(item1.achieved).toBe(true) // 4/8 = 50%

    const r2 = buildReadiness({ ...emptyInput(), answeredQuestionCount: 2, questionTotal: 5 })
    const item2 = r2.items.find((i) => i.id === 'coverage_50')!
    expect(item2.achieved).toBe(false) // 40%
    expect(item2.evidence).toBe('当前 2/5')

    // 有作答但无题量 → 覆盖率不可知，不达成（也不臆造 0）
    const r3 = buildReadiness({ ...emptyInput(), answeredQuestionCount: 3 })
    const item3 = r3.items.find((i) => i.id === 'coverage_50')!
    expect(item3.achieved).toBe(false)
    expect(item3.evidence).toBe('暂无题库数据')
  })

  it('已作答数缺失（answeredCount=null）：evidence 显式缺失，绝不回落成假 0', () => {
    const r = buildReadiness({ ...emptyInput(), answeredQuestionCount: null, questionTotal: 120 })
    const item = r.items.find((i) => i.id === 'coverage_50')!
    expect(item.achieved).toBe(false)
    // 断言「输出为 EMPTY」而不是「函数不崩」
    expect(item.evidence).toBe(`当前 ${EMPTY}/120`)
    expect(item.evidence).not.toContain('0/')
    expect(item.evidence).not.toMatch(/当前 0/)
  })

  it('简历分数 80 为达成边界', () => {
    const at80 = buildReadiness({ ...emptyInput(), resumeScore: 80 })
    expect(at80.items.find((i) => i.id === 'resume_score_80')!.achieved).toBe(true)
    const at79 = buildReadiness({ ...emptyInput(), resumeScore: 79 })
    expect(at79.items.find((i) => i.id === 'resume_score_80')!.achieved).toBe(false)
  })

  it('自述项可单独达成（无接口数据也能推进准备度）', () => {
    const r = buildReadiness({
      ...emptyInput(),
      manual: { jdAnalysis: true, selfIntro: false, coverLetter: false },
    })
    expect(r.achievedCount).toBe(1)
    expect(r.grade).toBe('C')
    expect(r.items.find((i) => i.id === 'jd_analysis')!.manualKey).toBe('jdAnalysis')
  })

  it('清单覆盖 6 维度共 14 项', () => {
    const r = buildReadiness(emptyInput())
    const dims = new Set(r.items.map((i) => i.dimension))
    expect(dims).toEqual(new Set(['简历', '岗位目标', '投递', '面试训练', '知识储备', '材料准备']))
    expect(r.total).toBe(14)
    // 每个 item id 唯一
    expect(new Set(r.items.map((i) => i.id)).size).toBe(14)
  })
})
