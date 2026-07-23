import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/variables.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

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
