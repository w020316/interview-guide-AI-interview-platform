import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '../auth'

const routes = [
  { path: '/',          component: () => import('../views/HomeView.vue'),      meta: { requiresAuth: false } },
  { path: '/login',     component: () => import('../views/LoginView.vue'),     meta: { requiresAuth: false } },
  { path: '/resume',    component: () => import('../views/ResumeView.vue'),    meta: { requiresAuth: true  } },
  { path: '/resume/history', component: () => import('../views/ResumeHistoryView.vue'), meta: { requiresAuth: true } },
  { path: '/interview', component: () => import('../views/InterviewView.vue'),meta: { requiresAuth: true  } },
  { path: '/history',   component: () => import('../views/HistoryView.vue'),   meta: { requiresAuth: true  } },
  { path: '/profile',   component: () => import('../views/ProfileView.vue'),   meta: { requiresAuth: true  } },
  { path: '/knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { requiresAuth: true  } },
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
})

export default router
