import { describe, it, expect } from 'vitest'
import { ICONS, primaryNav, toolNav, buildMobileGroups } from './navigation'

describe('导航配置（v1.48.0）', () => {
  it('「投递看板」在主导航而非求职工具下拉', () => {
    expect(primaryNav.some((i) => i.to === '/applications')).toBe(true)
    expect(toolNav.some((i) => i.to === '/applications')).toBe(false)
  })

  it('「招聘广场」与「投递看板」在主导航中相邻（找岗位 → 管投递）', () => {
    const jobsIdx = primaryNav.findIndex((i) => i.to === '/jobs')
    const appsIdx = primaryNav.findIndex((i) => i.to === '/applications')
    expect(jobsIdx).toBeGreaterThanOrEqual(0)
    expect(appsIdx).toBe(jobsIdx + 1)
  })

  it('/job 的显示名为「JD 拆解」（与 /jobs 招聘广场区分）', () => {
    const job = primaryNav.find((i) => i.to === '/job')
    expect(job).toBeTruthy()
    expect(job!.label).toBe('JD 拆解')
    // 不再使用易与「招聘广场」混淆的旧名
    expect(job!.label).not.toBe('岗位分析')
  })

  it('路由 path 未被改动：/job 与 /jobs 均仍存在', () => {
    expect(primaryNav.some((i) => i.to === '/job')).toBe(true)
    expect(primaryNav.some((i) => i.to === '/jobs')).toBe(true)
  })

  it('主导航与工具下拉之间无重复入口', () => {
    const all = [...primaryNav, ...toolNav].map((i) => i.to)
    expect(new Set(all).size).toBe(all.length)
  })

  it('所有导航项引用的图标都在 ICONS 中定义', () => {
    for (const item of [...primaryNav, ...toolNav]) {
      expect(ICONS[item.icon], `缺少图标: ${item.icon}`).toBeTruthy()
    }
  })

  it('主导航每项都有非空 label', () => {
    for (const item of primaryNav) {
      expect(item.label.trim().length).toBeGreaterThan(0)
    }
  })
})

describe('buildMobileGroups（移动端抽屉 · 双身份自测）', () => {
  it('未登录：求职准备 + 账户，无空分组', () => {
    const groups = buildMobileGroups({ loggedIn: false, admin: false })
    expect(groups.map((g) => g.title)).toEqual(['求职准备', '账户'])
    expect(groups[0].items).toBe(primaryNav)
    expect(groups[1].items.map((i) => i.to)).toContain('/login')
    for (const g of groups) expect(g.items.length).toBeGreaterThan(0)
  })

  it('普通用户：求职准备 + 求职工具 + 我的（无管理后台）', () => {
    const groups = buildMobileGroups({ loggedIn: true, admin: false })
    expect(groups.map((g) => g.title)).toEqual(['求职准备', '求职工具', '我的'])
    const tools = groups.find((g) => g.title === '求职工具')!
    expect(tools.items).toBe(toolNav)
    expect(tools.items.length).toBeGreaterThan(0) // 下拉不空
    const mine = groups.find((g) => g.title === '我的')!
    expect(mine.items.map((i) => i.to)).toEqual(['/profile'])
    for (const g of groups) expect(g.items.length).toBeGreaterThan(0)
  })

  it('管理员：我的分组额外含「管理后台」', () => {
    const groups = buildMobileGroups({ loggedIn: true, admin: true })
    const mine = groups.find((g) => g.title === '我的')!
    const tos = mine.items.map((i) => i.to)
    expect(tos).toContain('/admin')
    expect(tos).toContain('/profile')
    for (const g of groups) expect(g.items.length).toBeGreaterThan(0)
  })

  it('移动端「求职准备」已包含投递看板（与主导航一致）', () => {
    const groups = buildMobileGroups({ loggedIn: true, admin: false })
    const prep = groups.find((g) => g.title === '求职准备')!
    expect(prep.items.some((i) => i.to === '/applications')).toBe(true)
  })
})
