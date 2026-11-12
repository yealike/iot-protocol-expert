import { Suspense, useEffect, useState } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from './api/client'
import type { CurrentUser } from './api/types'
import AppLayout from './components/AppLayout'
import Login from './pages/Login'
import Overview from './pages/Overview'
import Devices from './pages/Devices'
import DeviceDetail from './pages/DeviceDetail'
import Products from './pages/Products'
import ProductDetail from './pages/ProductDetail'
import Alarms from './pages/Alarms'
import Orders from './pages/Orders'
import ProtocolDocuments from './pages/ProtocolDocuments'
import OrderDetail from './pages/OrderDetail'
import NotFound from './pages/NotFound'
import Simulator from './pages/Simulator'

/**
 * 会话守卫。
 *
 * 只做一件事：首次挂载问一次 /api/ops/auth/me，未登录时把任何路由都落到登录页。
 * 登录成功后整页刷新，让 React Query 的缓存从干净状态开始。
 */
export default function App() {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const location = useLocation()

  useEffect(() => {
    api
      .me()
      .then(setUser)
      .catch(() => setUser({ authenticated: false }))
  }, [])

  if (!user) {
    return (
      <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
        <Spin size="large" tip="正在校验会话…" />
      </div>
    )
  }

  if (!user.authenticated) {
    return <Login onSuccess={() => window.location.reload()} />
  }

  return (
    <AppLayout user={user}>
      <Suspense
        fallback={
          <div style={{ minHeight: 320, display: 'grid', placeItems: 'center' }}>
            <Spin tip="正在加载…" />
          </div>
        }
      >
        <Routes>
          <Route path="/" element={<Navigate to="/overview" replace />} />
          <Route path="/overview" element={<Overview />} />
          <Route path="/devices" element={<Devices />} />
          <Route path="/devices/:id" element={<DeviceDetail />} />
          <Route path="/products" element={<Products />} />
          <Route path="/products/:id" element={<ProductDetail />} />
          <Route path="/protocol-documents" element={<ProtocolDocuments />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/orders/:orderNo" element={<OrderDetail />} />
          <Route path="/simulator" element={<Simulator />} />
          <Route path="/alarms" element={<Alarms />} />
          <Route path="*" element={<NotFound path={location.pathname} />} />
        </Routes>
      </Suspense>
    </AppLayout>
  )
}
