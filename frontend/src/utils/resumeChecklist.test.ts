import { describe, it, expect } from 'vitest'
import {
  countQuantifiedStats,
  countActionVerbs,
  hasVagueTechStatement,
  buildResumeChecklist,
  isGoldDimension,
  checklistForDimension,
} from './resumeChecklist'

/**
 * 规则化清单条目单测（正例 / 反例 / 空文本边界）。
 *
 * 这些函数是「含金量」维度清单最前面那几条的**唯一来源**，
 * 输出必须确定：同一段原文永远得到同一份清单。
 */
describe('countQuantifiedStats 量化数字计数', () => {
  it('正例：数字 + 性能 / 百分比 / 季度 / 倍数单位各计 1 处', () => {
    const text = '接口响应从 800ms 优化到 120ms，转化率提升 15%，Q3 上线灰度，吞吐提升 3倍'
    // 800ms、120ms、15%、Q3、3倍 = 5 处
    expect(countQuantifiedStats(text)).toBe(5)
  })

  it('正例：规模量词（万 / 人 / 种）也计入', () => {
    // 5 万、200 人、3 种 = 3 处；「万」后面的「次」前面没有数字，不独立计数
    expect(countQuantifiedStats('日承载 5 万次请求，服务 200 人，覆盖 3 种支付渠道')).toBe(3)
  })

  it('反例：纯形容词描述没有量化数据', () => {
    expect(countQuantifiedStats('负责用户中心模块的开发，性能优秀，体验流畅')).toBe(0)
  })

  it('年份不计入量化数据（是时间锚点，不是成果指标）', () => {
    expect(countQuantifiedStats('2023年毕业，2024 年带队')).toBe(0)
  })

  it('正例：「N 年经验 / 开发 / 工作」等资历背景不计入（目标用户简历恰是这类）', () => {
    const text = '3 年后端开发经验，5 年 Java 经验，2 年工作经历，3年以上从业时间'
    expect(countQuantifiedStats(text)).toBe(0)
  })

  it('反例：背景年限不吞掉真实量化——「响应时间 3 秒」正常计入', () => {
    expect(countQuantifiedStats('3 年经验，响应时间 3 秒')).toBe(1)
  })

  it('反例：「N 年内 / 期间」后跟真成果不误杀——「5 个版本」计入', () => {
    // 「3 年内」按时长框架排除（时间范围不是成果），后面的「5 个」照常计数
    expect(countQuantifiedStats('3 年内完成 5 个版本迭代')).toBe(1)
  })

  it('年份不吞掉相邻的真实量化数据', () => {
    // 2024 年之后的「3 人」应正常计入
    expect(countQuantifiedStats('2024 年组建 3 人小组')).toBe(1)
  })

  it('空文本安全：返回 0', () => {
    expect(countQuantifiedStats('')).toBe(0)
  })
})

describe('countActionVerbs 主导动词统计', () => {
  it('正例：弱动词逐个计数', () => {
    const text = '参与需求评审，配合后端联调，协助测试，了解容器化'
    expect(countActionVerbs(text)).toEqual({ strong: 0, weak: 4 })
  })

  it('正例：强动词逐个计数（独立 可出现多次）', () => {
    const text = '主导支付网关设计，独立完成对账模块，独立完成灰度发布'
    expect(countActionVerbs(text)).toEqual({ strong: 4, weak: 0 })
  })

  it('反例：强弱并存且强动词占优', () => {
    const text = '参与过两次迭代，但主导了整体架构设计并独立交付'
    expect(countActionVerbs(text)).toEqual({ strong: 3, weak: 1 })
  })

  it('空文本安全：两组计数均为 0', () => {
    expect(countActionVerbs('')).toEqual({ strong: 0, weak: 0 })
  })
})

describe('hasVagueTechStatement 技术栈模糊检测', () => {
  it('正例：「了解……等」命中', () => {
    expect(hasVagueTechStatement('了解 Kafka 等消息队列')).toBe(true)
  })

  it('正例：「熟悉……等」命中', () => {
    expect(hasVagueTechStatement('熟悉 Spring 等主流框架')).toBe(true)
  })

  it('正例：「相关技术」命中', () => {
    expect(hasVagueTechStatement('负责网关模块及中间件等相关技术')).toBe(true)
  })

  it('反例：落到具体技术的表述不命中', () => {
    expect(hasVagueTechStatement('精通 Spring Cloud 微服务，用 Kafka 做过事件削峰')).toBe(false)
  })

  it('反例：「了解」后没接「……等」的收尾不命中', () => {
    expect(hasVagueTechStatement('了解 Kafka。深入使用过 RocketMQ。')).toBe(false)
  })

  it('空文本安全：返回 false', () => {
    expect(hasVagueTechStatement('')).toBe(false)
  })
})

describe('buildResumeChecklist 清单条目生成', () => {
  /** 三类信号全中的简历原文 */
  const BAD_TEXT =
    '参与团队项目开发，配合产品完成功能，了解 Redis 等缓存方案，负责网关模块及相关技术的维护'

  it('三类信号全命中：按 量化 → 弱动词 → 含糊 的顺序输出 3 条', () => {
    const items = buildResumeChecklist(BAD_TEXT)
    expect(items.map((i) => i.rule)).toEqual(['quantify', 'weak-verb', 'vague-tech'])
  })

  it('量化为 0 时条目写明「全文 0 处量化数据」', () => {
    const items = buildResumeChecklist(BAD_TEXT)
    expect(items[0].problem).toContain('全文 0 处量化数据')
  })

  it('每条条目都带「缺什么 + 怎么补」：problem 与 advice 均非空，advice 必须含可照抄的示例', () => {
    const items = buildResumeChecklist(BAD_TEXT)
    expect(items.length).toBeGreaterThan(0)
    for (const item of items) {
      expect(item.problem.trim()).not.toBe('')
      expect(item.advice.trim()).not.toBe('')
      // 「怎么补」必须给出具体改法（含「例如 / 改为」示例），不允许空泛的「建议增强」
      expect(item.advice).toMatch(/例如|改为/)
    }
  })

  it('正例：三类信号都不命中时返回空数组（不是「0 条问题」的假数据）', () => {
    const good =
      '主导订单系统重构，独立完成支付模块（覆盖 3 种渠道），峰值日承载 80 万次请求，精通 JVM 调优'
    expect(buildResumeChecklist(good)).toEqual([])
  })

  it('边界：弱动词数 == 强动词数时不报弱动词条目（严格大于才报）', () => {
    // 参与 1 处 = 主导 1 处（不得含「设计」等其余强动词），量化 1 处（120ms）→ 三类信号均不触发
    const balanced = '参与需求评审并主导方案落地，接口 P99 响应 120ms'
    expect(buildResumeChecklist(balanced)).toEqual([])
  })

  it('量化数据 > 0 时不生成量化条目，只生成命中的那几条', () => {
    const items = buildResumeChecklist('参与多个模块开发，配合测试，了解 Docker 等工具，日承载 5 万次请求')
    expect(items.map((i) => i.rule)).toEqual(['weak-verb', 'vague-tech'])
  })

  it('边界：空文本 / 纯空白 / 非字符串安全，返回空数组', () => {
    expect(buildResumeChecklist('')).toEqual([])
    expect(buildResumeChecklist('   \n\t  ')).toEqual([])
    expect(buildResumeChecklist(undefined as unknown as string)).toEqual([])
  })

  it('确定性：同一份原文两次调用结果完全一致', () => {
    expect(buildResumeChecklist(BAD_TEXT)).toEqual(buildResumeChecklist(BAD_TEXT))
  })
})

describe('checklistForDimension 维度注入判定', () => {
  const TEXT = '参与项目，配合开发，了解微服务等相关技术'

  it('「项目含金量」（后端实际维度名）注入条目', () => {
    expect(isGoldDimension('项目含金量')).toBe(true)
    expect(checklistForDimension('项目含金量', TEXT).length).toBeGreaterThan(0)
  })

  it('「含金量」裸名也命中', () => {
    expect(isGoldDimension('含金量')).toBe(true)
  })

  it('其余维度恒为空数组：岗位匹配 / 表述清晰度 / 求职意向', () => {
    for (const name of ['岗位匹配度', '表述清晰度', '求职意向']) {
      expect(isGoldDimension(name)).toBe(false)
      expect(checklistForDimension(name, TEXT)).toEqual([])
    }
  })

  it('简历原文不可用时：即便命中含金量维度也返回空数组（退化为仅 AI 建议）', () => {
    expect(checklistForDimension('项目含金量', '')).toEqual([])
  })
})
