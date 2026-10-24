import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// npm run dev        -> 本地后端 http://127.0.0.1:18099
// npm run dev:remote -> 远程后端（浏览器同源经代理转发，避免跨域与 Cookie 问题）
const REMOTE_BACKEND = 'https://docx-check.test.chatgqt.top'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    proxy: { '/api': mode === 'remote' ? REMOTE_BACKEND : 'http://127.0.0.1:18099' },
  },
}))
