import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址：开发时把 /api 代理到 Python 服务（以后换成 Spring Boot 只改这个变量）
const API_TARGET = process.env.VITE_API_TARGET || 'http://127.0.0.1:8765'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5180,
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/static': { target: API_TARGET, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200
  }
})
