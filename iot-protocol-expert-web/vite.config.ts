import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发期将 /api/ai 与 /api/control 转发到 Agent Control 19083，其余 API 和登录转发到 OPS 19082，
 * 免去 CORS 与额外的 mock 层。生产构建输出到 dist，由独立静态服务器托管，
 * 生产反向代理沿用相同的路径分流，保持会话 Cookie 同源。
 */
export default defineConfig({
  base: '/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/ai': { target: 'http://127.0.0.1:19083', changeOrigin: true },
      '/api/control': { target: 'http://127.0.0.1:19083', changeOrigin: true },
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
          charts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    }
  }
})
