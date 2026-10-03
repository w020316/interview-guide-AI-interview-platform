/**
 * 导航配置（v1.48.0 从 App.vue 抽出，便于单测与复用）
 *
 * 第六轮 UX 报告（产品 P1）：
 * - 「岗位分析」(`/job`) 与「招聘广场」(`/jobs`) 命名撞车 → `/job` 显示名改为「JD 拆解」
 * - 「投递看板」(`/applications`) 高频却埋在二级下拉 → 提到主导航，与「招聘广场」相邻，
 *   形成「找岗位 → 管投递」的动线
 * ⚠️ 仅调整显示标签与归属，路由 path 不变（避免破坏书签/外链）。
 */

export interface NavItem {
  to: string
  label: string
  icon: string
  /** 需要精确匹配的路径 */
  exact?: boolean
  /** 命中即视为激活的多个路径（一个入口对应多子页面时使用） */
  match?: string[]
  desc?: string
}

/** 内联图标路径表：避免重复书写多份 <svg> 结构 */
export const ICONS: Record<string, string> = {
  home: 'M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M9 22V12h6v10',
  resume: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8',
  briefcase: 'M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  users: 'M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z',
  chat: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M14 14h7v7h-7z M3 14h7v7H3z',
  bot: 'M12 2a7 7 0 0 1 4 12.7V17a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-2.3A7 7 0 0 1 12 2z M9 21h6',
  compass: 'M12 2v4 M12 18v4 M4.9 4.9l2.8 2.8 M16.3 16.3l2.8 2.8 M2 12h4 M18 12h4 M4.9 19.1l2.8-2.8 M16.3 7.7l2.8-2.8 M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  send: 'M22 2L11 13 M22 2l-7 20-4-9-9-4 20-7z',
  cap: 'M22 10L12 5 2 10l10 5 10-5z M6 12v5c0 1.7 2.7 3 6 3s6-1.3 6-3v-5 M22 10v6',
  clock: 'M12 8v4l3 3 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z',
  book: 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z',
  user: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9',
  chevron: 'M6 9l6 6 6-6',
}

/**
 * 主导航：高频入口，常驻显示（未登录可点，由路由守卫引导登录）。
 * 「招聘广场 → 投递看板」相邻，形成「找岗位 → 管投递」动线；
 * `/job` 显示名用「JD 拆解」以区别于「招聘广场」。
 */
export const primaryNav: NavItem[] = [
  { to: '/', label: '首页', icon: 'home', exact: true },
  { to: '/resume', label: '简历分析', icon: 'resume' },
  { to: '/jobs', label: '招聘广场', icon: 'users' },
  { to: '/applications', label: '投递看板', icon: 'send' },
  { to: '/job', label: 'JD 拆解', icon: 'briefcase' },
  { to: '/interview', label: '模拟面试', icon: 'chat' },
]

/** 求职工具下拉：求职过程中的其余工具页，需登录 */
export const toolNav: NavItem[] = [
  { to: '/agent', label: '智能体', icon: 'bot', desc: '对话式求职助手' },
  { to: '/career', label: '求职诊断', icon: 'compass', desc: '四层能力挖掘 + 30 天计划' },
  {
    to: '/learning',
    label: '学习中心',
    icon: 'cap',
    desc: '学习日历 · 错题 · 收藏 · 进度',
    match: ['/learning', '/calendar', '/wrong-book', '/favorites', '/progress'],
  },
  { to: '/history', label: '历史记录', icon: 'clock', desc: '简历与面试复盘' },
  { to: '/knowledge', label: '知识库', icon: 'book', desc: 'RAG 八股文问答' },
]

/** 移动端抽屉分组 */
export interface MobileGroup {
  title: string
  items: NavItem[]
}

/**
 * 构建移动端抽屉分组（纯函数，便于对「普通用户 / 管理员」双身份做单测）。
 * - 未登录：求职准备 + 账户（登录/注册）
 * - 已登录：求职准备 + 求职工具 + 我的
 * - 管理员：我的分组额外包含「管理后台」
 * 保证每个分组都非空，避免出现空下拉。
 */
export function buildMobileGroups(opts: { loggedIn: boolean; admin: boolean }): MobileGroup[] {
  const groups: MobileGroup[] = [{ title: '求职准备', items: primaryNav }]
  if (opts.loggedIn) {
    groups.push({ title: '求职工具', items: toolNav })
    const mine: NavItem[] = []
    if (opts.admin) mine.push({ to: '/admin', label: '管理后台', icon: 'settings' })
    mine.push({ to: '/profile', label: '个人中心', icon: 'user' })
    groups.push({ title: '我的', items: mine })
  } else {
    groups.push({ title: '账户', items: [{ to: '/login', label: '登录 / 注册', icon: 'user' }] })
  }
  return groups
}
