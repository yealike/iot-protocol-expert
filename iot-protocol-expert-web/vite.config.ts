import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发期直接把 /api、/login、/logout、/actuator 代理到运维平台 19082，
 * 免去 CORS 与额外的 mock 层。生产构建输出到 dist，由独立静态服务器托管，
 * 同一台反向代理把 /api、/login、/logout、/actuator 转发到 19082，保持与后端同源。
 */
export default defineConfig({
  base: '/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:19082', changeOrigin: true },
      '/login': { target: 'http://127.0.0.1:19082', changeOrigin: true },
      '/logout': { target: 'http://127.0.0.1:19082', changeOrigin: true },
      '/actuator': { target: 'http://127.0.0.1:19082', changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
          charts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
          three: ['three']
        }
      }
    }
  }
})
