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
import Orders from './pages/Orders'
import Rates from './pages/Rates'
import ProtocolDocuments from './pages/ProtocolDocuments'
import OrderDetail from './pages/OrderDetail'
import NotFound from './pages/NotFound'
import Simulator from './pages/Simulator'
import Assistant from './pages/Assistant'
import AiKnowledge from './pages/AiKnowledge'
import Users from './pages/Users'
import Usage from './pages/Usage'
import QuotaAdmin from './pages/QuotaAdmin'
import AiAdmin from './pages/ai-admin/AiAdmin'
import ModelsPage from './pages/ai-admin/ModelsPage'
import ProvidersPage from './pages/ai-admin/ProvidersPage'
import McpsPage from './pages/ai-admin/McpsPage'
import SkillsPage from './pages/ai-admin/SkillsPage'
import AgentsPage from './pages/ai-admin/AgentsPage'
import PublicationsPage from './pages/ai-admin/PublicationsPage'

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
        <div style={{ textAlign: 'center' }}>
          <Spin size="large" />
          <div style={{ marginTop: 12, color: '#718096' }}>正在校验会话…</div>
        </div>
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
            <div style={{ textAlign: 'center' }}>
              <Spin />
              <div style={{ marginTop: 12, color: '#718096' }}>正在加载…</div>
            </div>
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
          <Route path="/rates" element={<Rates />} />
          <Route path="/simulator/*" element={<Simulator />} />
          <Route path="/assistant" element={<Assistant />} />
          <Route path="/usage" element={<Usage view="quota" />} />
          <Route path="/usage/history" element={<Usage view="history" />} />
          <Route path="/quota-admin" element={<QuotaAdmin user={user} />} />
          <Route path="/users" element={<Users />} />
          <Route path="/ai-knowledge" element={<AiKnowledge user={user} />} />
          <Route path="/ai-admin" element={<AiAdmin user={user} />}>
            <Route index element={<Navigate to="models" replace />} />
            <Route path="models" element={<ModelsPage />} />
            <Route path="providers" element={<ProvidersPage />} />
            <Route path="mcps" element={<McpsPage />} />
            <Route path="skills" element={<SkillsPage />} />
            <Route path="agents" element={<AgentsPage />} />
            <Route path="publications" element={<PublicationsPage />} />
            <Route path="publications/:publicationId" element={<PublicationsPage />} />
          </Route>
          <Route path="*" element={<NotFound path={location.pathname} />} />
        </Routes>
      </Suspense>
    </AppLayout>
  )
}
