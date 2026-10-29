import { describe, expect, it } from 'vitest'
import {
  SALARY_MODES,
  buildSalaryPayload,
  emptySalaryForm,
  formatMoney,
  formatPercent,
  formatSignedMoney,
  hasSalaryNumbers,
  modeMeta,
  normalizeSalaryReport,
  type SalaryForm,
} from './salary'

const form = (over: Partial<SalaryForm> = {}): SalaryForm => ({
  ...emptySalaryForm(),
  targetJob: 'Java 后端',
  ...over,
})

const SERVER_REPORT = {
  headline: '先把薪资结构问清楚，再报区间，不要报到单点。',
  analysis: [{ point: '月薪 22k 没有提薪数', basis: '来自你填写的 offer 月薪' }],
  scripts: [{ scene: 'HR 问期望薪资时', say: '我希望月薪 25k，按 14 薪算。' }],
  qa: [{ q: '这就是上限了', a: '理解，那能否在签字费或调薪周期上再看一下？' }],
  warnings: ['只谈月薪不谈薪数'],
  actions: ['把薪数写进书面 offer'],
  numbers: {
    raiseAmount: 4000,
    raisePct: 22.2,
    offerAnnual: 330000,
    expectedGapMonthly: -3000,
    expectedGapPct: -12,
  },
}

describe('modeMeta —— 三种谈薪场景', () => {
  it('三个场景都有标签、提示与填数说明', () => {
    for (const m of SALARY_MODES) {
      expect(m.label).not.toBe('')
      expect(m.hint).not.toBe('')
      expect(m.fieldNote).not.toBe('')
    }
  })

  it('按 mode 取到对应场景；未知值回退到「被问期望薪资」', () => {
    expect(modeMeta('OFFER').label).toBe('评估 offer')
    expect(modeMeta('NEGOTIATION').label).toBe('正在还价')
    expect(modeMeta('WHAT' as never).value).toBe('EXPECTATION')
  })
})

describe('buildSalaryPayload —— 本地校验与请求组装', () => {
  it('目标岗位为空：直接拒绝，不发请求', () => {
    expect(buildSalaryPayload(emptySalaryForm()).error).toBe('请填写目标岗位')
    expect(buildSalaryPayload(form({ targetJob: '   ' })).error).toBe('请填写目标岗位')
  })

  it('可选数字全部不填 → null（不是 0）', () => {
    const { payload, error } = buildSalaryPayload(form())
    expect(error).toBeNull()
    expect(payload.years).toBeNull()
    expect(payload.currentMonthly).toBeNull()
    expect(payload.expectedMonthly).toBeNull()
    expect(payload.offerMonthly).toBeNull()
    expect(payload.offerMonths).toBeNull()
    expect(payload.mode).toBe('EXPECTATION')
    expect(payload.targetJob).toBe('Java 后端')
  })

  it('字符串两端空白被去掉，空文本字段传空串', () => {
    const { payload } = buildSalaryPayload(
      form({ targetJob: '  Java 后端  ', city: ' 杭州 ', competing: '  ', situation: 'HR 让当场答复' })
    )
    expect(payload.targetJob).toBe('Java 后端')
    expect(payload.city).toBe('杭州')
    expect(payload.competing).toBe('')
    expect(payload.situation).toBe('HR 让当场答复')
  })

  it('填了数字就按整数提交（小数四舍五入，与后端一致）', () => {
    const { payload, error } = buildSalaryPayload(
      form({ years: '3', currentMonthly: '18000', expectedMonthly: '25000.6', offerMonthly: '22000', offerMonths: '15' })
    )
    expect(error).toBeNull()
    expect(payload.years).toBe(3)
    expect(payload.currentMonthly).toBe(18000)
    expect(payload.expectedMonthly).toBe(25001)
    expect(payload.offerMonthly).toBe(22000)
    expect(payload.offerMonths).toBe(15)
  })

  it('非数字：明确提示，而不是静默当成没填', () => {
    expect(buildSalaryPayload(form({ currentMonthly: '两万' })).error).toBe('当前月薪请填写数字')
    expect(buildSalaryPayload(form({ years: '3年' })).error).toBe('工作年限请填写数字')
  })

  it('越界：与后端同文案提示区间', () => {
    expect(buildSalaryPayload(form({ years: '61' })).error).toBe('工作年限需在 0 ~ 60 之间')
    expect(buildSalaryPayload(form({ offerMonths: '0' })).error).toBe('年薪月数需在 1 ~ 36 之间')
    expect(buildSalaryPayload(form({ currentMonthly: '0' })).error).toBe('当前月薪需在 1 ~ 1000000 之间')
    expect(buildSalaryPayload(form({ expectedMonthly: '1000001' })).error).toBe(
      '期望月薪需在 1 ~ 1000000 之间'
    )
  })

  it('边界值本身合法（0 年 / 1 薪 / 100 万）', () => {
    expect(buildSalaryPayload(form({ years: '0' })).error).toBeNull()
    expect(buildSalaryPayload(form({ offerMonths: '1' })).error).toBeNull()
    expect(buildSalaryPayload(form({ offerMonthly: '1000000' })).error).toBeNull()
  })

  it('mode 原样透传（由场景 Tab 决定）', () => {
    expect(buildSalaryPayload(form({ mode: 'NEGOTIATION' })).payload.mode).toBe('NEGOTIATION')
  })
})

describe('normalizeSalaryReport —— 报告形状兜底', () => {
  it('合法报告完整保留（含服务端算出的金额）', () => {
    const r = normalizeSalaryReport(SERVER_REPORT)!
    expect(r.headline).toContain('薪资结构')
    expect(r.analysis).toEqual([{ point: '月薪 22k 没有提薪数', basis: '来自你填写的 offer 月薪' }])
    expect(r.scripts[0].say).toContain('25k')
    expect(r.qa[0].q).toBe('这就是上限了')
    expect(r.warnings).toEqual(['只谈月薪不谈薪数'])
    expect(r.actions).toEqual(['把薪数写进书面 offer'])
    expect(r.numbers).toEqual({
      raiseAmount: 4000,
      raisePct: 22.2,
      offerAnnual: 330000,
      expectedGapMonthly: -3000,
      expectedGapPct: -12,
    })
  })

  it('没有任何内容时返回 null（界面展示「没拿到建议」）', () => {
    expect(normalizeSalaryReport(null)).toBeNull()
    expect(normalizeSalaryReport('{}')).toBeNull()
    expect(normalizeSalaryReport([])).toBeNull()
    expect(normalizeSalaryReport({ headline: '  ', analysis: [], scripts: [] })).toBeNull()
  })

  it('只有 headline 也算有效报告（部分模型只给一句结论）', () => {
    const r = normalizeSalaryReport({ headline: '先问薪数再谈月薪' })!
    expect(r.headline).toBe('先问薪数再谈月薪')
    expect(r.analysis).toEqual([])
    expect(hasSalaryNumbers(r.numbers)).toBe(false)
  })

  it('headline 缺失但其余有内容：用兜底文案，不显示空白标题', () => {
    const r = normalizeSalaryReport({ actions: ['今天就问 HR 薪数'] })!
    expect(r.headline).toBe('这次没有生成有效建议，请稍后重试')
    expect(r.actions).toEqual(['今天就问 HR 薪数'])
  })

  it('列表形状漂移：非对象条目丢弃，空字符串条目丢弃，缺一方的对象保留', () => {
    const r = normalizeSalaryReport({
      headline: 'x',
      analysis: [
        { point: '只有判断' },
        { basis: '只有依据' },
        { point: '', basis: '' },
        'oops',
        null,
      ],
      scripts: [{ scene: '场景', say: 42 }],
      qa: 'not-a-list',
      warnings: [1, '有效风险', '  '],
      actions: null,
    })!
    expect(r.analysis).toEqual([
      { point: '只有判断', basis: '' },
      { point: '', basis: '只有依据' },
    ])
    expect(r.scripts).toEqual([{ scene: '场景', say: '' }])
    expect(r.qa).toEqual([])
    expect(r.warnings).toEqual(['有效风险'])
    expect(r.actions).toEqual([])
  })

  it('金额缺失或类型不对 → null（界面显示「—」，不补 0）', () => {
    const r = normalizeSalaryReport({
      headline: 'x',
      numbers: { raiseAmount: '4000', raisePct: NaN, offerAnnual: Infinity, expectedGapMonthly: null },
    })!
    expect(r.numbers).toEqual({
      raiseAmount: null,
      raisePct: null,
      offerAnnual: null,
      expectedGapMonthly: null,
      expectedGapPct: null,
    })
  })

  it('numbers 完全缺失时五项都是 null，不抛异常', () => {
    const r = normalizeSalaryReport({ headline: 'x' })!
    expect(Object.values(r.numbers).every((v) => v === null)).toBe(true)
    expect(hasSalaryNumbers(r.numbers)).toBe(false)
  })

  it('负的涨幅是真实数据，必须保留（offer 低于当前月薪）', () => {
    const r = normalizeSalaryReport({ headline: 'x', numbers: { raiseAmount: -2000, raisePct: -10 } })!
    expect(r.numbers.raiseAmount).toBe(-2000)
    expect(r.numbers.raisePct).toBe(-10)
    expect(hasSalaryNumbers(r.numbers)).toBe(true)
  })
})

describe('金额展示', () => {
  it('formatMoney：null → 「—」，千分位分组', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(0)).toBe('0 元')
    expect(formatMoney(12345)).toBe('12,345 元')
    expect(formatMoney(330000)).toBe('330,000 元')
  })

  it('formatSignedMoney：正数带 +，负数带 -，0 不带符号', () => {
    expect(formatSignedMoney(null)).toBe('—')
    expect(formatSignedMoney(0)).toBe('0 元')
    expect(formatSignedMoney(4000)).toBe('+4,000 元')
    expect(formatSignedMoney(-3000)).toBe('-3,000 元')
  })

  it('formatPercent：null → 「—」，整数不带小数点，正数带 +', () => {
    expect(formatPercent(null)).toBe('—')
    expect(formatPercent(22.2)).toBe('+22.2%')
    expect(formatPercent(-12)).toBe('-12%')
    expect(formatPercent(0)).toBe('0%')
    expect(formatPercent(-0.04)).toBe('0%')
  })
})
