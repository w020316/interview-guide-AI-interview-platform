import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as Icons from '@element-plus/icons-vue'

const app = createApp(App)

// 全局注册 Element Plus 图标
Object.entries(Icons).forEach(([name, component]) => {
  app.component(name, component)
})

app.use(router)
app.use(ElementPlus)
app.mount('#app')
