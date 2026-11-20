import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as Icons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import './styles/main.css'

import App from './App.vue'
import router from './router'
import { initSkin } from './theme'

initSkin()

const app = createApp(App)

// 图标全局注册，模板里直接写 <el-icon><User /></el-icon>
Object.entries(Icons).forEach(([name, comp]) => app.component(name, comp))

app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
