import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn, isAdmin } from '../auth'

const routes = [
  { path: '/',          component: () => import('../views/HomeView.vue'),      meta: { requiresAuth: false } },
  { path: '/login',     component: () => import('../views/LoginView.vue'),     meta: { requiresAuth: false } },
  { path: '/resume',    component: () => import('../views/ResumeView.vue'),    meta: { requiresAuth: true  } },
  { path: '/resume/history', component: () => import('../views/ResumeHistoryView.vue'), meta: { requiresAuth: true } },
  { path: '/job',       component: () => import('../views/JobAnalysisView.vue'), meta: { requiresAuth: true  } },
  { path: '/jobs',      component: () => import('../views/JobsView.vue'),        meta: { requiresAuth: true  } },
  { path: '/agent',     component: () => import('../views/AgentView.vue'),       meta: { requiresAuth: true  } },
  { path: '/interview', component: () => import('../views/InterviewView.vue'),meta: { requiresAuth: true  } },
  { path: '/history',   component: () => import('../views/HistoryView.vue'),   meta: { requiresAuth: true  } },
  { path: '/learning',  component: () => import('../views/LearningView.vue'),   meta: { requiresAuth: true  } },
  { path: '/calendar',  component: () => import('../views/CalendarView.vue'),   meta: { requiresAuth: true  } },
  { path: '/wrong-book',component: () => import('../views/WrongBookView.vue'),  meta: { requiresAuth: true  } },
  { path: '/favorites', component: () => import('../views/FavoritesView.vue'),  meta: { requiresAuth: true  } },
  { path: '/progress',  component: () => import('../views/ProgressView.vue'),   meta: { requiresAuth: true  } },
  { path: '/profile',   component: () => import('../views/ProfileView.vue'),   meta: { requiresAuth: true  } },
  { path: '/knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { requiresAuth: true  } },
  { path: '/admin',     component: () => import('../views/AdminView.vue'),    meta: { requiresAuth: true, requiresAdmin: true  } },
  // 404 兜底：未匹配路径显示 404 页
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('../views/NotFoundView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  }
})

// 路由守卫：未登录或 token 格式非法时跳转到 /login，并记录 redirect 参数
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !isLoggedIn()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // v1.31.4：管理后台仅 ROLE_ADMIN 可访问
  if (to.meta.requiresAdmin && !isAdmin()) {
    return { path: '/' }
  }
})

export default router
