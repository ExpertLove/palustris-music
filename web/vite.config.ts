import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// VITE_BASE=/next/ — сборка для проверочного адреса, по умолчанию корень.
const base = process.env.VITE_BASE || '/'
const upstream = process.env.API_UPSTREAM || 'https://music.palustris.ru'

export default defineConfig({
  base,
  plugins: [react()],
  build: {
    target: 'es2022',
    cssMinify: 'lightningcss',
    chunkSizeWarningLimit: 600,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: upstream, changeOrigin: true, secure: true },
    },
  },
})
