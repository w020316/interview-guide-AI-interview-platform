import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/variables.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { initTheme } from './theme'
import { prewarmBackend, installWakeRecovery } from './utils/backendWake'

// 在任何渲染前应用主题，避免首屏闪烁
initTheme()

// 冷启动预热：后端部署在 Render 免费层，15 分钟无请求即休眠。
// 2026-09-22 线上实测：真实冷启动 338s / 355s（≈6 分钟，最慢超过 8 分钟也出现过），
// 远大于早期记录的 98s。这里在应用挂载前就发起一次轻量探测（fire-and-forget，
// 不阻塞首屏），让实例在用户浏览/输入账号密码期间就开始启动，从源头规避「登录界面加载停滞」。
prewarmBackend()
// 页面重新可见 / 网络恢复时自动补探测：移动端切走再切回会冻结定时器，
// 没有这个钩子会一直停在「正在冷启动」的假象上。
installWakeRecovery()

const app = createApp(App)

// 全局错误处理器：生产环境不输出到 console
app.config.errorHandler = (err) => {
  if (!import.meta.env.PROD) {
    console.error('全局错误:', err)
  }
}

// 路由错误处理：chunk 加载失败时自动刷新（CDN 故障/缓存问题兜底）
router.onError((error) => {
  if (error.message.includes('Failed to fetch dynamically imported module')) {
    window.location.reload()
  }
})

// 全局未处理 Promise rejection 捕获（SSE/fetch/async 中的未 catch 异常）
window.addEventListener('unhandledrejection', (event) => {
  if (!import.meta.env.PROD) {
    console.error('未处理 Promise rejection:', event.reason)
  }
})

app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
