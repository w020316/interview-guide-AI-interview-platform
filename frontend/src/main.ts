import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/variables.css'
import './styles/markdown.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { initTheme } from './theme'
import { prewarmBackend, installWakeRecovery } from './utils/backendWake'
import { installSessionGuard } from './utils/sessionGuard'
import { setupPwa } from './utils/pwa'

// 在任何渲染前应用主题，避免首屏闪烁
initTheme()

// PWA（第三批 D）：注册 Service Worker（prompt 模式，不自动刷新）。
// 放在挂载前：让 app shell 尽早开始预缓存；新版本只提示，由用户在页脚确认后刷新。
setupPwa()

// 冷启动预热：后端部署在 Render 免费层，15 分钟无请求即休眠。
// 2026-09-22 线上实测：真实冷启动 338s / 355s（≈6 分钟，最慢超过 8 分钟也出现过），
// 远大于早期记录的 98s。这里在应用挂载前就发起一次轻量探测（fire-and-forget，
// 不阻塞首屏），让实例在用户浏览/输入账号密码期间就开始启动，从源头规避「登录界面加载停滞」。
prewarmBackend()
// 页面重新可见 / 网络恢复时自动补探测：移动端切走再切回会冻结定时器，
// 没有这个钩子会一直停在「正在冷启动」的假象上。
installWakeRecovery()

// 会话失效恢复：页面重新可见 / 窗口重新聚焦时立即重校验 token。
// 修复「页面长时间开着，回来时所有功能都用不了」：启动清理只在应用启动执行、
// 请求拦截器要有请求才触发、路由守卫要有跳转才触发，「回到页面」本身不触发任何一条
// → 这里补上主动校验，失效则清会话并带说明跳登录（见 utils/sessionGuard.ts 的归因说明：
// 线上 JWT 为 24h，同日几小时不会过期，真正兜的是「会话在页面之外失效」那一类）。
installSessionGuard()

const app = createApp(App)

// 全局错误处理器。
// v1.76.1 起**生产环境也输出**：此前这里是 `if (!PROD) console.error`，
// 结果线上一次「整页空白」事故完全没有留下任何线索 —— 页面炸了、控制台却是干净的，
// 排查全靠猜。渲染/生命周期错误本就极低频，留一行日志的代价远小于再瞎一次。
// （路由级异常已被 RouteErrorBoundary 接住并 return false，不会重复打印。）
app.config.errorHandler = (err) => {
  console.error('[app-error]', err)
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
