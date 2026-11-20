import React from 'react'
import ReactDOM from 'react-dom/client'
import './lib/random-uuid'
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
          colorPrimary: '#3563e9',
          colorInfo: '#3563e9',
          colorLink: '#3563e9',
          colorTextBase: '#182230',
          colorBgBase: '#f5f7fb',
          colorBgContainer: '#ffffff',
          colorBgElevated: '#ffffff',
          colorBorder: '#e2e8f0',
          borderRadius: 10,
          controlHeight: 38,
          fontFamily:
            'Inter, "Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif'
        },
        components: {
          Layout: {
            siderBg: '#111b2e',
            headerBg: '#ffffff',
            bodyBg: '#f5f7fb'
          },
          Menu: {
            darkItemBg: 'transparent',
            darkItemHoverBg: 'rgba(255, 255, 255, 0.06)',
            darkItemSelectedBg: '#3563e9',
            darkItemSelectedColor: '#ffffff',
            darkItemColor: '#a8b3c7'
          },
          Button: { fontWeight: 550 },
          Card: { colorBgContainer: '#ffffff' },
          Table: { headerBg: '#f7f9fc', headerColor: '#5d6b82' },
          Input: { activeShadow: '0 0 0 3px rgba(53, 99, 233, 0.10)' }
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
