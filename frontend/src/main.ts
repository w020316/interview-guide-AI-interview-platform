import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/variables.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as Icons from '@element-plus/icons-vue'

const app = createApp(App)

// 全局注册 Element Plus 图标
Object.entries(Icons).forEach(([name, component]) => {
  app.component(name, component)
})

// 全局错误处理器：生产环境不输出到 console
app.config.errorHandler = (err) => {
  if (!import.meta.env.PROD) {
    console.error('全局错误:', err)
  }
}

app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
