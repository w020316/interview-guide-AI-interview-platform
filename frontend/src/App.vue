<template>
  <el-config-provider :locale="zhCn">
    <div class="app-wrapper">
      <!-- 顶部导航栏 -->
      <header ref="navRef" class="navbar" :class="{ scrolled }">
        <div class="nav-inner">
          <div
            class="nav-brand"
            role="link"
            tabindex="0"
            aria-label="返回首页"
            @click="router.push('/')"
            @keyup.enter="router.push('/')"
          >
            <span class="brand-mark">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <div class="brand-text-wrap">
              <span class="brand-text">AI 面试助手</span>
              <span class="brand-sub">智能面试准备平台</span>
            </div>
          </div>

          <!-- 主菜单（桌面端）：主导航 + 分组下拉，替代此前 13 项平铺 -->
          <nav class="nav-menu" aria-label="主导航">
            <router-link
              v-for="item in primaryNav"
              :key="item.to"
              :to="item.to"
              class="nav-link"
              :class="{ active: isActive(item) }"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path :d="ICONS[item.icon]" stroke="currentColor" stroke-width="2"
                  stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>{{ item.label }}</span>
            </router-link>

            <span v-if="authState.token" class="nav-divider" aria-hidden="true" />

            <div
              v-if="authState.token"
              class="nav-dropdown"
              @mouseenter="onEnter('tools')"
              @mouseleave="onLeave"
            >
              <button
                class="nav-link nav-trigger"
                :class="{ active: toolsActive }"
                type="button"
                aria-haspopup="true"
                :aria-expanded="openMenu === 'tools'"
                @click="onClickMenu('tools')"
              >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path :d="ICONS.grid" stroke="currentColor" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>求职工具</span>
                <svg class="chev" :class="{ open: openMenu === 'tools' }" width="12" height="12"
                  viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path :d="ICONS.chevron" stroke="currentColor" stroke-width="2.5"
                    stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
              <transition name="drop">
                <div v-if="openMenu === 'tools'" class="dropdown-panel tools-panel" role="menu">
                  <router-link
                    v-for="t in toolNav"
                    :key="t.to"
                    :to="t.to"
                    class="dd-item"
                    :class="{ active: isActive(t) }"
                    role="menuitem"
                  >
                    <span class="dd-ico">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path :d="ICONS[t.icon]" stroke="currentColor" stroke-width="2"
                          stroke-linecap="round" stroke-linejoin="round"/>
                      </svg>
                    </span>
                    <span class="dd-text">
                      <b>{{ t.label }}</b>
                      <em>{{ t.desc }}</em>
                    </span>
                  </router-link>
                </div>
              </transition>
            </div>
          </nav>

          <div class="nav-actions">
            <button
              class="theme-toggle"
              :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              :title="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
              @click="toggleTheme"
            >
              <svg v-if="theme === 'dark'" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z M12 3v2 M12 19v2 M3 12h2 M17 12h2 M5.6 5.6l1.4 1.4 M17 17l1.4 1.4 M5.6 18.4L7 17"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>

            <template v-if="authState.token">
              <div class="nav-dropdown" @mouseenter="onEnter('user')" @mouseleave="onLeave">
                <button
                  class="user-chip"
                  type="button"
                  aria-haspopup="true"
                  :aria-expanded="openMenu === 'user'"
                  @click="onClickMenu('user')"
                >
                  <span class="user-avatar">{{ userInitial }}</span>
                  <span class="user-name">{{ authState.username }}</span>
                  <svg class="chev" :class="{ open: openMenu === 'user' }" width="12" height="12"
                    viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path :d="ICONS.chevron" stroke="currentColor" stroke-width="2.5"
                      stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </button>
                <transition name="drop">
                  <div v-if="openMenu === 'user'" class="dropdown-panel user-panel" role="menu">
                    <div class="dd-user-head">
                      <span class="user-avatar lg">{{ userInitial }}</span>
                      <span class="dd-user-meta">
                        <b>{{ authState.username }}</b>
                        <em>{{ isAdmin() ? '管理员' : '普通用户' }}</em>
                      </span>
                    </div>
                    <router-link to="/profile" class="dd-item compact" role="menuitem">
                      <span class="dd-ico">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <path :d="ICONS.user" stroke="currentColor" stroke-width="2"
                            stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                      </span>
                      <span class="dd-text"><b>个人中心</b></span>
                    </router-link>
                    <router-link v-if="isAdmin()" to="/admin" class="dd-item compact" role="menuitem">
                      <span class="dd-ico">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <path :d="ICONS.settings" stroke="currentColor" stroke-width="2"
                            stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                      </span>
                      <span class="dd-text"><b>管理后台</b></span>
                    </router-link>
                    <div class="dd-sep" />
                    <button class="dd-item compact danger" type="button" role="menuitem" @click="logout">
                      <span class="dd-ico">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <path :d="ICONS.logout" stroke="currentColor" stroke-width="2"
                            stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                      </span>
                      <span class="dd-text"><b>退出登录</b></span>
                    </button>
                  </div>
                </transition>
              </div>
            </template>
            <template v-else>
              <button class="btn-login" @click="router.push('/login')">登录</button>
              <button class="btn-register" @click="router.push('/login')">免费注册</button>
            </template>

            <!-- 移动端汉堡（≤960px 时替代横向滚动菜单） -->
            <button
              class="menu-toggle"
              :class="{ open: mobileOpen }"
              type="button"
              :aria-label="mobileOpen ? '关闭菜单' : '打开菜单'"
              :aria-expanded="mobileOpen"
              @click.stop="mobileOpen = !mobileOpen"
            >
              <span /><span /><span />
            </button>
          </div>
        </div>

        <!-- 移动端抽屉菜单：按职能分组，替代此前的「13 项横向滚动」 -->
        <transition name="drawer">
          <div v-if="mobileOpen" class="mobile-drawer">
            <template v-for="g in mobileGroups" :key="g.title">
              <p class="md-title">{{ g.title }}</p>
              <div class="md-grid">
                <router-link
                  v-for="it in g.items"
                  :key="it.to"
                  :to="it.to"
                  class="md-item"
                  :class="{ active: isActive(it) }"
                >
                  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path :d="ICONS[it.icon]" stroke="currentColor" stroke-width="2"
                      stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  <span>{{ it.label }}</span>
                </router-link>
              </div>
            </template>
          </div>
        </transition>
      </header>

      <!-- 主内容区 -->
      <main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>

      <!-- 页脚 -->
      <footer class="app-footer">
        <div class="footer-inner">
          <div class="footer-brand">
            <span class="brand-mark">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                <path d="M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"
                  stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <span>AI 面试助手</span>
          </div>
          <p class="footer-desc">基于 Spring Boot 3.3 + Spring AI 1.0 + Vue 3 构建，为求职者打造的智能面试准备平台</p>
          <p class="footer-copy">
            © 2026 AI 面试助手 · MIT License
            <button class="version-link" @click="openChangelog">
              v{{ CURRENT_VERSION }}
              <!-- v1.34.1（UX P2-3）：存在未读更新时给一个轻量红点提示，
                   替代「首访直接弹模态框遮挡首屏」的打扰式提醒 -->
              <span v-if="hasUnreadChangelog" class="version-dot" aria-label="有更新" />
            </button>
          </p>
        </div>
      </footer>

      <!-- 版本更新弹窗 -->
      <ChangelogDialog v-model:visible="showChangelog" @unread-change="hasUnreadChangelog = $event" />
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { authState, clearAuth, isAdmin } from './auth'
import api from './api'
import ChangelogDialog from './components/ChangelogDialog.vue'
import { CURRENT_VERSION } from './changelog'
import { theme, toggleTheme as toggle } from './theme'

const router = useRouter()
const route = useRoute()
const showChangelog = ref(false)
/** 存在未读更新（首访或版本变化）：仅显示小红点，不自动弹窗打断首屏 */
const hasUnreadChangelog = ref(false)

/* ─────────────────────────────────────────────────────────────
   导航结构（v1.37.0 导航栏重构）
   问题：此前 13 个入口全部平铺，1440px 下已贴边、1280px 下换行挤压，
   且「智能体/求职诊断/投递看板/学习中心/历史记录/知识库」属于同一职能层级，
   平铺后没有任何信息层级，用户扫视成本高。
   解决：主导航只保留 5 个高频入口，把 6 个「求职过程工具」收进分组下拉
   （带图标 + 一句话说明），账户相关操作收进头像下拉。
   ───────────────────────────────────────────────────────────── */

interface NavItem {
  to: string
  label: string
  icon: string
  /** 需要精确匹配的路径 */
  exact?: boolean
  /** 命中即视为激活的多个路径（一个入口对应多子页面时使用） */
  match?: string[]
  desc?: string
}

/** 内联图标路径表：避免重复书写 13 份 <svg> 结构 */
const ICONS: Record<string, string> = {
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

/** 主导航：高频入口，常驻显示（未登录可点，由路由守卫引导登录） */
const primaryNav: NavItem[] = [
  { to: '/', label: '首页', icon: 'home', exact: true },
  { to: '/resume', label: '简历分析', icon: 'resume' },
  { to: '/job', label: '岗位分析', icon: 'briefcase' },
  { to: '/jobs', label: '招聘广场', icon: 'users' },
  { to: '/interview', label: '模拟面试', icon: 'chat' },
]

/** 求职工具下拉：求职过程中的 6 个工具页，需登录 */
const toolNav: NavItem[] = [
  { to: '/agent', label: '智能体', icon: 'bot', desc: '对话式求职助手' },
  { to: '/career', label: '求职诊断', icon: 'compass', desc: '四层能力挖掘 + 30 天计划' },
  { to: '/applications', label: '投递看板', icon: 'send', desc: '投递台账与回复监测' },
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

/** 下拉面板开合状态：'' | 'tools' | 'user' */
const openMenu = ref<'' | 'tools' | 'user'>('')
const mobileOpen = ref(false)
const scrolled = ref(false)
const navRef = ref<HTMLElement | null>(null)

const userInitial = computed(() => (authState.username || '?').charAt(0).toUpperCase())

/** 下拉触发器激活态：其下任一子项命中当前路由 */
const toolsActive = computed(() => toolNav.some((t) => isActive(t)))

function isActive(item: NavItem): boolean {
  const p = route.path
  if (item.match) return item.match.includes(p)
  if (item.exact) return p === item.to
  return p === item.to || p.startsWith(item.to + '/')
}

/* ── 下拉交互：桌面 hover 打开、触屏点击切换、外部点击/ESC 关闭 ── */
let hoverTimer: ReturnType<typeof setTimeout> | undefined
/** 是否支持 hover（区分桌面与触屏，避免触屏上「tap 开后无法关闭」） */
function hoverable(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(hover: hover)').matches
}

function onEnter(name: 'tools' | 'user') {
  if (!hoverable()) return
  if (hoverTimer) clearTimeout(hoverTimer)
  openMenu.value = name
}

function onLeave() {
  if (!hoverable()) return
  if (hoverTimer) clearTimeout(hoverTimer)
  hoverTimer = setTimeout(() => { openMenu.value = '' }, 160)
}

function onClickMenu(name: 'tools' | 'user') {
  if (hoverable()) {
    if (openMenu.value !== name) openMenu.value = name
  } else {
    openMenu.value = openMenu.value === name ? '' : name
  }
}

function closeMenu() {
  openMenu.value = ''
}

function onDocClick(e: MouseEvent) {
  if (!openMenu.value) return
  const target = e.target as Node | null
  if (navRef.value && target && !navRef.value.contains(target)) closeMenu()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (openMenu.value) closeMenu()
  if (mobileOpen.value) mobileOpen.value = false
}

function onScroll() {
  scrolled.value = window.scrollY > 4
}

/** 移动端抽屉分组：按职能而非平铺 */
const mobileGroups = computed(() => {
  const groups: { title: string; items: NavItem[] }[] = [
    { title: '求职准备', items: primaryNav },
  ]
  if (authState.token) {
    groups.push({ title: '求职工具', items: toolNav })
    const mine: NavItem[] = []
    if (isAdmin()) mine.push({ to: '/admin', label: '管理后台', icon: 'settings' })
    mine.push({ to: '/profile', label: '个人中心', icon: 'user' })
    groups.push({ title: '我的', items: mine })
  } else {
    groups.push({ title: '账户', items: [{ to: '/login', label: '登录 / 注册', icon: 'user' }] })
  }
  return groups
})

// 路由切换后收起所有浮层，避免「跳转后下拉仍悬在页面上」
watch(() => route.path, () => {
  closeMenu()
  mobileOpen.value = false
})

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', onScroll)
  if (hoverTimer) clearTimeout(hoverTimer)
})

/** 手动打开版本更新：用户主动查看，视为已读 */
function openChangelog() {
  hasUnreadChangelog.value = false
  showChangelog.value = true
}

function toggleTheme() {
  toggle()
}

function logout() {
  closeMenu()
  // JWT 无状态登出：只需前端清除 token 并跳转。后端接口失败/挂起（冷启动/网络）都不应阻塞退出，
  // 因此改为后台静默调用（v1.31.4 修复：此前 await 挂起导致"点击退出无反应"）
  api.post('/api/auth/logout').catch(() => {})
  clearAuth()
  router.push('/login')
}
</script>

<style scoped>
.app-wrapper {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-height: 100dvh;
  overflow-x: hidden;
}

/* ── 导航栏：实色 + 细边框，无 blur ── */
.navbar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: var(--c-navbar);
  border-bottom: 1px solid var(--c-border);
  transition: box-shadow var(--transition-base);
}

/* 滚动后浮起：与页面内容分层（参考主流站点滚动抬升） */
.navbar.scrolled {
  box-shadow: var(--shadow-sm);
}

.nav-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  height: 64px;
  padding: 0 32px;
  gap: 16px;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  border-radius: var(--radius-md);
}

.nav-brand:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 4px;
}

/* 品牌图标：纯色，无渐变光晕 */
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  transition: background var(--transition-fast);
}

.nav-brand:hover .brand-mark {
  background: var(--brand-primary-hover);
}

.brand-text-wrap {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}

/* 品牌标题：衬线字体，编辑风 */
.brand-text {
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  letter-spacing: -0.3px;
}

.brand-sub {
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-tertiary);
  margin-top: 2px;
  letter-spacing: 0.2px;
}

.nav-menu {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* 主导航与工具组之间的细分割（层级暗示） */
.nav-divider {
  width: 1px;
  height: 20px;
  margin: 0 6px;
  background: var(--c-border);
  flex-shrink: 0;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 11px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-secondary);
  text-decoration: none;
  border-radius: var(--radius-md);
  transition: color var(--transition-fast), background-color var(--transition-fast);
  white-space: nowrap;
  background: transparent;
  border: none;
  cursor: pointer;
}

.nav-link svg {
  opacity: 0.7;
  transition: opacity var(--transition-fast);
}

/* hover：浅灰背景 */
.nav-link:hover {
  color: var(--c-text);
  background: var(--c-bg-alt);
}

.nav-link:hover svg {
  opacity: 1;
}

/* active：品牌色浅绿背景 + 深墨绿文字 */
.nav-link.active {
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  font-weight: 600;
}

.nav-link.active svg {
  opacity: 1;
}

/* 下拉触发器右侧箭头：展开时翻转 */
.chev {
  margin-left: 1px;
  opacity: 0.6;
  transition: transform var(--transition-fast), opacity var(--transition-fast);
}

.chev.open {
  transform: rotate(180deg);
  opacity: 1;
}

/* ── 下拉容器 ── */
.nav-dropdown {
  position: relative;
}

.dropdown-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: 10;
  min-width: 200px;
  padding: 6px;
  background: var(--c-surface-elevated);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xl);
}

/* 工具面板：两列，条目带说明文字 */
.tools-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(210px, 1fr));
  gap: 2px;
  min-width: 452px;
  padding: 8px;
}

.user-panel {
  left: auto;
  right: 0;
  min-width: 224px;
}

.dd-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--radius-md);
  text-decoration: none;
  color: var(--c-text);
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  width: 100%;
  font-family: var(--font-sans);
  transition: background-color var(--transition-fast);
}

.dd-item:hover {
  background: var(--c-bg-alt);
}

.dd-item.active {
  background: var(--brand-primary-50);
}

.dd-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--radius-md);
  background: var(--c-bg-alt);
  color: var(--c-text-secondary);
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.dd-item:hover .dd-ico,
.dd-item.active .dd-ico {
  background: var(--brand-primary);
  color: #fff;
}

.dd-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.dd-text b {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--c-text);
  line-height: 1.3;
}

.dd-text em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--c-text-tertiary);
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dd-item.active .dd-text b {
  color: var(--brand-primary);
}

/* 紧凑条目（用户菜单） */
.dd-item.compact {
  padding: 8px 10px;
}

.dd-item.compact .dd-ico {
  width: 28px;
  height: 28px;
  background: transparent;
}

.dd-item.compact:hover .dd-ico {
  background: transparent;
  color: var(--brand-primary);
}

.dd-item.danger:hover,
.dd-item.danger:hover .dd-ico {
  color: var(--c-danger);
}

.dd-item.danger:hover {
  background: var(--c-danger-light);
}

.dd-sep {
  height: 1px;
  margin: 4px 6px;
  background: var(--c-border-light);
}

.dd-user-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px 10px;
  border-bottom: 1px solid var(--c-border-light);
  margin-bottom: 4px;
}

.dd-user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.dd-user-meta b {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dd-user-meta em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--brand-primary);
  font-weight: 600;
}

/* 下拉动画 */
.drop-enter-active,
.drop-leave-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  margin-left: auto;
}

/* 主题切换按钮 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  color: var(--c-text-secondary);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}
.theme-toggle:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: var(--brand-primary-50);
}
.theme-toggle:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 1px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  background: var(--c-bg-alt);
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  cursor: pointer;
  font-family: var(--font-sans);
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.user-chip:hover {
  background: var(--c-border-light);
  border-color: var(--c-border);
}

.user-chip .chev {
  color: var(--c-text-tertiary);
}

/* 用户头像：纯色背景，无渐变 */
.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-avatar.lg {
  width: 36px;
  height: 36px;
  font-size: 15px;
}

.user-name {
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 登录按钮：描边样式 */
.btn-login {
  padding: 7px 16px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast), background-color var(--transition-fast);
}

.btn-login:hover {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-50);
}

/* 注册按钮：实色背景，hover 颜色加深而非上浮 */
.btn-register {
  padding: 7px 16px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-primary);
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-fast), border-color var(--transition-fast);
}

.btn-register:hover {
  background: var(--brand-primary-hover);
  border-color: var(--brand-primary-hover);
}

/* ── 移动端汉堡按钮（桌面隐藏） ── */
.menu-toggle {
  display: none;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  width: 34px;
  height: 34px;
  padding: 0 8px;
  background: transparent;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  cursor: pointer;
}

.menu-toggle span {
  display: block;
  height: 2px;
  border-radius: 2px;
  background: var(--c-text-secondary);
  transition: transform var(--transition-fast), opacity var(--transition-fast);
}

.menu-toggle.open span:nth-child(1) {
  transform: translateY(6px) rotate(45deg);
}
.menu-toggle.open span:nth-child(2) {
  opacity: 0;
}
.menu-toggle.open span:nth-child(3) {
  transform: translateY(-6px) rotate(-45deg);
}

/* ── 移动端抽屉 ── */
.mobile-drawer {
  padding: 12px 16px 18px;
  border-top: 1px solid var(--c-border-light);
  background: var(--c-surface);
  max-height: 70vh;
  overflow-y: auto;
}

.md-title {
  margin: 6px 0 8px;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.6px;
  color: var(--c-text-quaternary);
  text-transform: uppercase;
}

.md-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.md-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-secondary);
  text-decoration: none;
  background: var(--c-bg-alt);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: color var(--transition-fast), background-color var(--transition-fast), border-color var(--transition-fast);
}

.md-item svg {
  flex-shrink: 0;
  opacity: 0.75;
}

.md-item.active {
  color: var(--brand-primary);
  background: var(--brand-primary-50);
  border-color: var(--brand-primary-200);
  font-weight: 600;
}

.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--transition-base), max-height var(--transition-base);
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
  max-height: 0;
  overflow: hidden;
}

/* ── 主内容 ── */
.main-content {
  flex: 1;
  padding: 32px 24px;
  max-width: 1280px;
  width: 100%;
  margin: 0 auto;
}

/* ── 页脚：简洁实色 ── */
.app-footer {
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
  padding: 40px 24px;
}

.footer-inner {
  max-width: 1280px;
  margin: 0 auto;
  text-align: center;
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.footer-brand .brand-mark {
  width: 28px;
  height: 28px;
}

.footer-brand span:last-child {
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.footer-desc {
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-secondary);
  margin: 0 0 8px;
  max-width: 560px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

.footer-copy {
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-tertiary);
  margin: 0;
}

.version-link {
  display: inline-block;
  position: relative;
  margin-left: 8px;
  padding: 2px 8px;
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--brand-primary-light);
  border: none;
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--transition-fast);
  letter-spacing: 0.2px;
}

.version-link:hover {
  background: var(--brand-primary);
  color: #fff;
}

/* 未读更新小红点（v1.34.1 UX P2-3）：以轻量提示替代首访自动弹窗 */
.version-dot {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-danger, #f56c6c);
  box-shadow: 0 0 0 1.5px var(--c-bg, #fff);
}

/* ── 页面切换动画 ── */
.page-enter-active,
.page-leave-active {
  transition: opacity var(--transition-base), transform var(--transition-base);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ── 响应式 ── */

/* 中等屏幕：收紧间距，隐藏品牌副标题与用户名，保证 5 项主导航不挤压 */
@media (max-width: 1180px) {
  .nav-inner {
    padding: 0 20px;
    gap: 10px;
  }
  .brand-sub {
    display: none;
  }
  .nav-link {
    padding: 7px 9px;
    font-size: 13px;
  }
  .user-name {
    display: none;
  }
  .user-chip {
    padding: 4px;
  }
}

/* ≤960px：主导航收进汉堡抽屉（v1.37.0：替代此前的横向滚动菜单） */
@media (max-width: 960px) {
  .nav-inner {
    height: 56px;
    padding: 0 16px;
    gap: 10px;
  }
  .nav-menu {
    display: none;
  }
  .menu-toggle {
    display: flex;
  }
  .user-chip .chev {
    display: none;
  }
}

@media (max-width: 480px) {
  .btn-login,
  .btn-register {
    padding: 6px 12px;
    font-size: 12px;
  }
  .nav-actions {
    gap: 8px;
  }
  .main-content {
    padding: 20px 16px;
  }
  .md-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
