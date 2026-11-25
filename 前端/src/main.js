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


/**
 * 部署之后，浏览器里开着的旧页面还在引用老的文件名，
 * 点到那些页面就会 404（Failed to fetch dynamically imported module）。
 * 这里兜一下：发现文件没了就自动刷一次，刷完拿到新的 index.html 就好了。
 */
let chunkReloading = false
function reloadForNewBuild() {
  if (chunkReloading) return
  const last = Number(sessionStorage.getItem('dsh_chunk_reload_at') || 0)
  // 10 秒内只刷一次，避免真的坏了的时候死循环
  if (Date.now() - last < 10000) return
  chunkReloading = true
  sessionStorage.setItem('dsh_chunk_reload_at', String(Date.now()))
  window.location.reload()
}

const isChunkError = (msg) =>
  /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk|dynamically imported module/i
    .test(String(msg || ''))

window.addEventListener('vite:preloadError', (e) => {
  if (e && typeof e.preventDefault === 'function') e.preventDefault()
  reloadForNewBuild()
})

router.onError((err) => {
  if (isChunkError(err && err.message)) reloadForNewBuild()
})

const app = createApp(App)

// 图标全局注册，模板里直接写 <el-icon><User /></el-icon>
Object.entries(Icons).forEach(([name, comp]) => app.component(name, comp))

app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
