import { defineConfig } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: './',
  plugins: [
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: 'dist',
  },
  server: {
    host: 'local.dev.swedenconnect.se',
    port: 5174,
    proxy: {
      '/api': {
        target: 'https://local.dev.swedenconnect.se:17005',
        changeOrigin: true,
        secure: false,
      },
      '/theme': {
        target: 'https://local.dev.swedenconnect.se:17005',
        changeOrigin: true,
        secure: false,
      },
      '/theme-init.js': {
        target: 'https://local.dev.swedenconnect.se:17005',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
