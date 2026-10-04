import { describe, it, expect } from 'vitest'
import {
  classifySource,
  sourceKindMeta,
  applyFact,
  jobTrust,
  jobFreshness,
  sourceMixLabel,
  SOURCE_KIND_META,
  type JobSourceKind,
} from './jobTrust'

describe('classifySource —— 来源类型归类', () => {
  it('官方公开 API 的 5 个源全部归为 official-api', () => {
    const apis = [
      'RemoteOK 全球远程',
      'Remotive 全球远程',
      'Arbeitnow 欧洲',
      'Jobicy 全球远程',
      'Himalayas 全球远程',
    ]
    for (const name of apis) {
      expect(classifySource(name), name).toBe('official-api')
    }
  })

  it('WeWorkRemotely 归为 official-rss', () => {
    expect(classifySource('WeWorkRemotely')).toBe('official-rss')
  })

  it('V2EX 与 HN 归为 community', () => {
    expect(classifySource('V2EX 酷工作')).toBe('community')
    expect(classifySource('HN Who is hiring')).toBe('community')
  })

  it('全部内置种子的 8 个源都归为 curated', () => {
    const seeds = [
      '内置精选',
      '行业精选',
      '服务精选',
      '社招精选',
      '精选频道',
      '热招速递',
      '秋招精选2027',
      '兼职专区',
    ]
    for (const name of seeds) {
      expect(classifySource(name), name).toBe('curated')
    }
  })

  it('第三方招聘平台归为 partner', () => {
    expect(classifySource('第三方平台')).toBe('partner')
    expect(classifySource('BOSS直聘')).toBe('partner')
  })

  it('未知来源如实归为 unknown —— 绝不默认成「可信」', () => {
    expect(classifySource('某个未来才有的新源')).toBe('unknown')
    expect(classifySource('LinkedIn')).toBe('unknown')
  })

  it('null / undefined / 空串 / 纯空白 → unknown（不抛错）', () => {
    expect(classifySource(null)).toBe('unknown')
    expect(classifySource(undefined)).toBe('unknown')
    expect(classifySource('')).toBe('unknown')
    expect(classifySource('   ')).toBe('unknown')
  })

  it('含前后空格仍能正确归类（先 trim 再匹配）', () => {
    expect(classifySource('  RemoteOK 全球远程  ')).toBe('official-api')
    expect(classifySource(' V2EX 酷工作 ')).toBe('community')
  })

  it('关键词大小写不敏感的场景：品牌名已含大写，原样匹配', () => {
    // 平台名由后端适配器固定返回，此处锁定「不会因为大小写漏判」
    expect(classifySource('remoteok')).toBe('unknown') // 小写不在清单里 → 如实 unknown，不回退猜测
    expect(classifySource('RemoteOK')).toBe('official-api')
  })

  it('伪冒关键字的来源不被误判（子串匹配的边界）', () => {
    // 含「精选」但不在种子清单里的来源不应被当成 curated
    expect(classifySource('某公司精选')).toBe('unknown')
  })
})

describe('SOURCE_KIND_META / sourceKindMeta —— 展示元数据', () => {
  it('每种来源类型都有 label 与 hint，且都不为空', () => {
    const kinds = Object.keys(SOURCE_KIND_META) as JobSourceKind[]
    expect(kinds.length).toBe(6)
    for (const k of kinds) {
      expect(SOURCE_KIND_META[k].label.length, k).toBeGreaterThan(0)
      expect(SOURCE_KIND_META[k].hint.length, k).toBeGreaterThan(10)
    }
  })

  it('措辞不含「可信 / 不可信」这类主观判断词', () => {
    const all = Object.values(SOURCE_KIND_META)
      .map((m) => m.label + m.hint)
      .join('')
    expect(all).not.toContain('可信')
    expect(all).not.toContain('不可信')
    expect(all).not.toContain('虚假')
  })

  it('sourceKindMeta 与 classifySource 结果一致', () => {
    expect(sourceKindMeta('RemoteOK').label).toBe('官方接口')
    expect(sourceKindMeta('某个新源').label).toBe('其他来源')
  })
})

describe('applyFact —— 可申请性事实', () => {
  it('有链接 → hasApplyUrl 为真，标签「有申请入口」', () => {
    const f = applyFact('https://example.com/apply')
    expect(f.hasApplyUrl).toBe(true)
    expect(f.label).toBe('有申请入口')
  })

  it('无链接（null / undefined / 空串 / 纯空白）→ 标签「无直达入口」', () => {
    for (const v of [null, undefined, '', '   ']) {
      const f = applyFact(v)
      expect(f.hasApplyUrl, String(v)).toBe(false)
      expect(f.label, String(v)).toBe('无直达入口')
    }
  })

  it('无链接时的说明明确否定「失效」推断', () => {
    // 产品纪律：不猜。必须写清「这不代表岗位失效」，否则用户会误读
    expect(applyFact(null).hint).toContain('不代表岗位失效')
  })
})

describe('jobTrust —— 汇总标注', () => {
  it('官方 API + 有链接：不需要提示核实', () => {
    const t = jobTrust('RemoteOK 全球远程', 'https://remoteok.com/x')
    expect(t.kind).toBe('official-api')
    expect(t.kindLabel).toBe('官方接口')
    expect(t.hasApplyUrl).toBe(true)
    expect(t.needsVerify).toBe(false)
  })

  it('社区发帖 + 无链接：needsVerify 为真（两个条件同时成立）', () => {
    const t = jobTrust('V2EX 酷工作', null)
    expect(t.kind).toBe('community')
    expect(t.needsVerify).toBe(true)
  })

  it('社区发帖 + 有链接：needsVerify 为假（链接已足够核实）', () => {
    const t = jobTrust('V2EX 酷工作', 'https://v2ex.com/t/123')
    expect(t.needsVerify).toBe(false)
  })

  it('非社区来源 + 无链接：needsVerify 仍为假（只在两个条件同时成立才提示）', () => {
    const t = jobTrust('内置精选', null)
    expect(t.kind).toBe('curated')
    expect(t.needsVerify).toBe(false)
  })

  it('未知来源 + 无链接：needsVerify 为假（unknown 不是 community）', () => {
    const t = jobTrust('LinkedIn', null)
    expect(t.kind).toBe('unknown')
    expect(t.needsVerify).toBe(false)
  })
})

describe('jobFreshness —— 新鲜度（如实转述，不评分）', () => {
  const today = new Date('2026-10-04T10:00:00')

  it('无截止日期 → 「长期有效」，daysLeft 为 null（不是 0）', () => {
    for (const v of [null, undefined, '', '  ']) {
      const f = jobFreshness(v, today)
      expect(f.label, String(v)).toBe('长期有效')
      expect(f.daysLeft, String(v)).toBeNull()
    }
  })

  // ⚠️ 天数口径说明：deadline 按「当天 23:59:59」锚定（与 JobsView.deadlineText 一致），
  // 再从 today 时刻向上取整。因此 from 10-04T10:00 看 10-08 是 ceil(4.58) = 5，不是 4。
  // 下面锁定的是**真实值**（已用 node 复算），不是直觉值——直觉值会写错，正如我第一版。
  it('7 天内 → urgent，标签「仅剩 N 天」', () => {
    const f = jobFreshness('2026-10-08', today)
    expect(f.urgent).toBe(true)
    expect(f.daysLeft).toBe(5)
    expect(f.label).toBe('仅剩 5 天')
  })

  it('边界：daysLeft 恰好 7 仍算 urgent（≤7 含等号）', () => {
    const f = jobFreshness('2026-10-10', today)
    expect(f.urgent).toBe(true)
    expect(f.daysLeft).toBe(7)
    expect(f.label).toBe('仅剩 7 天')
  })

  it('边界：daysLeft 跨到 8 即不再 urgent，改用「N 天后截止」', () => {
    const f = jobFreshness('2026-10-11', today)
    expect(f.urgent).toBe(false)
    expect(f.daysLeft).toBe(8)
    expect(f.label).toBe('8 天后截止')
  })

  it('超过 30 天 → 直接显示具体日期', () => {
    const f = jobFreshness('2026-12-31', today)
    expect(f.urgent).toBe(false)
    expect(f.label).toBe('截止 2026-12-31')
  })

  it('已过期 → 「已截止」，urgent 为假，daysLeft 为负', () => {
    const f = jobFreshness('2026-09-01', today)
    expect(f.label).toBe('已截止')
    expect(f.urgent).toBe(false)
    expect(f.daysLeft!).toBeLessThan(0)
  })

  it('不可解析的日期 → 占位符，不猜成「长期有效」', () => {
    const f = jobFreshness('不是日期', today)
    expect(f.label).toBe('—')
    expect(f.daysLeft).toBeNull()
  })

  it('与 JobsView.deadlineText 口径一致（同样把截止日锚到 23:59:59）', () => {
    // deadlineText 用 23:59:59 计算；锁死这一口径，避免有人改成 00:00 后全站天数集体缩水一天。
    // from 10-04T22:00 看 10-05T23:59:59 差 25h59m → ceil = 2 天；若锚到 00:00 则只有 1 天。
    const f = jobFreshness('2026-10-05', new Date('2026-10-04T22:00:00'))
    expect(f.daysLeft).toBe(2)
  })
})

describe('sourceMixLabel —— 列表级来源汇总', () => {
  it('空列表 → 空串（不输出「本页来源：」这种半截文案）', () => {
    expect(sourceMixLabel([])).toBe('')
  })

  it('相同类型去重后只出现一次', () => {
    const label = sourceMixLabel(['RemoteOK', 'Remotive', 'Jobicy'])
    expect(label).toBe('本页来源：官方接口')
  })

  it('多类型按稳定顺序输出（同输入同输出，可断言）', () => {
    const label = sourceMixLabel(['V2EX 酷工作', 'RemoteOK', '内置精选'])
    // 稳定顺序：curated → official-api → official-rss → partner → community → unknown
    expect(label).toBe('本页来源：平台精选 · 官方接口 · 社区发帖')
  })

  it('undefined / null 元素不导致崩溃，且归为其他来源', () => {
    const label = sourceMixLabel([null, undefined, 'RemoteOK'])
    expect(label).toContain('官方接口')
    expect(label).toContain('其他来源')
  })
})
