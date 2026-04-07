import { defineConfig } from 'vite'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
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
    port: 5173,
    proxy: {
      '/api': {
        target: 'https://localhost:16990',
        changeOrigin: true,
        secure: false,
      },
      '/oauth2': {
        target: 'https://localhost:16990',
        changeOrigin: true,
        secure: false,
      },
      '/login': {
        target: 'https://localhost:16990',
        changeOrigin: true,
        secure: false,
      },
      '/logout': {
        target: 'https://localhost:16990',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
