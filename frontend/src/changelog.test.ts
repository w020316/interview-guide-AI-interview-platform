import { describe, it, expect } from 'vitest'
import {
  CHANGELOG,
  CHANGELOG_AUTO_OPEN_DELAY_MS,
  CURRENT_VERSION,
  decideChangelogAction,
  isTechItem,
  itemText,
} from './changelog'

/**
 * 更新弹窗内容分级测试（v1.33.3）
 *
 * 需求：弹窗只展示用户能感知的内容；架构/依赖/测试/内部实现等
 * 「程序员看的」条目必须对普通用户隐藏。
 */
describe('changelog 内容分级', () => {
  describe('isTechItem 判定', () => {
    it('技术类条目被识别为 tech', () => {
      const techSamples = [
        '测试：后端 620 → 631 例全绿',
        '后端：新增共享知识库播种器，启动时写入向量库',
        '前端：更新弹窗按内容分级展示，技术类条目不再打扰用户',
        '依赖升级：vite 5→8、vitest 2→5，audit 0 漏洞',
        '修复：CORS 允许请求头白名单补充 Cache-Control',
        '架构：拆分为独立 Bean，避免自调用导致事务失效',
        '新增：Prometheus 指标埋点用于监控告警',
        '替换：连接池参数适配免费层（连接探测 + 泄漏预警）',
      ]
      for (const s of techSamples) {
        expect(isTechItem(s), `应判定为技术条目: ${s}`).toBe(true)
      }
    })

    it('用户可感知的条目不被误判', () => {
      const userSamples = [
        '登录体验：修复长时间未使用后打开网站，登录页会长时间无响应的问题',
        '新增功能：岗位收藏，点击岗位卡片即可收藏',
        '知识问答：内置了覆盖 Java、Spring、数据库的面试知识库',
        '体验升级：对比结果附一句话总结，直指可优化的维度',
        '新增能力：可直接让智能体出模拟面试题练习',
      ]
      for (const s of userSamples) {
        expect(isTechItem(s), `不应判定为技术条目: ${s}`).toBe(false)
      }
    })

    it('显式 level 标记优先于关键词推断', () => {
      // 文本本身像技术条目，但显式标为 user → 尊重显式标记
      expect(isTechItem({ text: '测试：某功能验证通过', level: 'user' })).toBe(false)
      // 文本看起来正常，但显式标为 tech → 尊重显式标记
      expect(isTechItem({ text: '优化了登录体验', level: 'tech' })).toBe(true)
    })

    it('itemText 对字符串与对象两种形态都能取值', () => {
      expect(itemText('纯文本')).toBe('纯文本')
      expect(itemText({ text: '对象文本', level: 'user' })).toBe('对象文本')
    })
  })

  describe('CHANGELOG 数据完整性', () => {
    it('最新版本有用户可见内容（弹窗不会空着）', () => {
      const latest = CHANGELOG[0]
      const visible = latest.items.filter((it) => !isTechItem(it))
      expect(visible.length).toBeGreaterThan(0)
    })

    it('版本号格式统一且首个条目为当前版本', () => {
      expect(CHANGELOG[0].version).toBe(CURRENT_VERSION)
      for (const e of CHANGELOG) {
        expect(e.version).toMatch(/^\d+\.\d+\.\d+$/)
        expect(e.date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
        expect(e.items.length).toBeGreaterThan(0)
      }
    })

    it('最新版本的标题不暴露内部技术术语', () => {
      const title = CHANGELOG[0].title
      for (const bad of ['CORS', 'Bean', 'OOM', '重构', '单测', 'DTO', 'API']) {
        expect(title.includes(bad), `标题不应含技术术语 ${bad}`).toBe(false)
      }
    })
  })
})

// ───────────── 版本更新弹窗的自动弹出决策（v1.34.1，UX P2-3）─────────────
// 背景：此前只要本地版本 ≠ 当前版本就立即自动弹模态框，首次访问的用户一进站
// 就被盖住 hero 与主 CTA（375px 下主标题几乎贴底）。决策改为区分「首访」与「老用户升级」。

describe('版本更新自动弹出决策 decideChangelogAction', () => {
  it('首次访问（无本地记录）：不自动弹出，仅显示未读提示', () => {
    expect(decideChangelogAction(null)).toEqual({ open: false, unread: true })
  })

  it('已看过当前版本：既不弹出也不提示未读', () => {
    expect(decideChangelogAction(CURRENT_VERSION)).toEqual({ open: false, unread: false })
  })

  it('老用户遇到新版本：延迟自动弹出，并提示未读', () => {
    const d = decideChangelogAction('0.0.1')
    expect(d.open).toBe(true)
    expect(d.unread).toBe(true)
  })

  it('自动弹窗存在延迟，给首屏渲染留出时间', () => {
    expect(CHANGELOG_AUTO_OPEN_DELAY_MS).toBeGreaterThanOrEqual(1000)
  })
})
