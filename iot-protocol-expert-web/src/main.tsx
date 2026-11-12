import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider, theme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 5_000
    }
  }
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#38d39f',
          colorInfo: '#38d39f',
          colorBgBase: '#0b1420',
          colorBgContainer: '#0f1c2b',
          colorBgElevated: '#132335',
          colorBorder: '#1e3a52',
          borderRadius: 10,
          fontFamily:
            '"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif'
        },
        components: {
          Layout: {
            siderBg: '#0a121d',
            headerBg: 'rgba(11,20,32,0.86)',
            bodyBg: '#080f18'
          },
          Menu: {
            darkItemBg: 'transparent',
            darkItemSelectedBg: 'rgba(56,211,159,0.14)',
            darkItemSelectedColor: '#5be8b8'
          },
          Card: { colorBgContainer: '#0f1c2b' },
          Table: { headerBg: '#0d1a28' }
        }
      }}
    >
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>
)
