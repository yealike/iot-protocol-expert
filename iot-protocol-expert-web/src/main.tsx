import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
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
        token: {
          colorPrimary: '#2858a5',
          colorInfo: '#2858a5',
          colorBgBase: '#f4f6fa',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBorder: '#dce3ec',
          borderRadius: 6,
          fontFamily:
            '"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif'
        },
        components: {
          Layout: {
            siderBg: '#14243b',
            headerBg: '#ffffff',
            bodyBg: '#f4f6fa'
          },
          Menu: {
            darkItemBg: 'transparent',
            darkItemSelectedBg: '#284d7f',
            darkItemSelectedColor: '#ffffff'
          },
          Card: { colorBgContainer: '#ffffff' },
          Table: { headerBg: '#f6f8fb' }
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
