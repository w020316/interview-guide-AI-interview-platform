import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/',          component: () => import('../views/HomeView.vue'),      meta: { requiresAuth: false } },
  { path: '/login',     component: () => import('../views/LoginView.vue'),     meta: { requiresAuth: false } },
  { path: '/resume',    component: () => import('../views/ResumeView.vue'),    meta: { requiresAuth: true  } },
  { path: '/interview', component: () => import('../views/InterviewView.vue'),meta: { requiresAuth: true  } },
  { path: '/history',   component: () => import('../views/HistoryView.vue'),   meta: { requiresAuth: true  } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：未登录自动跳转到 /login，并记录 redirect 参数
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})

export default router
