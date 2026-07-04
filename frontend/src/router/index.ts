import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import ResumeView from '../views/ResumeView.vue'
import InterviewView from '../views/InterviewView.vue'
import HistoryView from '../views/HistoryView.vue'
import LoginView from '../views/LoginView.vue'

const routes = [
  { path: '/',         component: HomeView,     meta: { requiresAuth: false } },
  { path: '/login',    component: LoginView,    meta: { requiresAuth: false } },
  { path: '/resume',   component: ResumeView,   meta: { requiresAuth: true  } },
  { path: '/interview',component: InterviewView,meta: { requiresAuth: true  } },
  { path: '/history',  component: HistoryView,  meta: { requiresAuth: true  } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：未登录自动跳转到 /login
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    return { path: '/login' }
  }
})

export default router
