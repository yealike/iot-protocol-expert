import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  Avatar,
  Button,
  Drawer,
  Dropdown,
  Grid,
  Layout,
  Menu,
  Space,
  Tag,
  type MenuProps
} from 'antd'
import {
  ApiOutlined,
  AppstoreOutlined,
  BuildOutlined,
  ClusterOutlined,
  DashboardOutlined,
  DownOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  LogoutOutlined,
  MenuOutlined,
  RobotOutlined,
  ThunderboltOutlined,
  UserOutlined,
  WalletOutlined
} from '@ant-design/icons'
import { Link, useLocation } from 'react-router-dom'
import { api } from '../api/client'
import type { CurrentUser } from '../api/types'

const { Header, Sider, Content } = Layout

interface Props {
  user: CurrentUser
  children: React.ReactNode
}

type NavigationSection = 'operations' | 'intelligence' | 'administration'

interface NavigationItem {
  key: string
  icon: ReactNode
  label: string
  section: NavigationSection
}

const sections: { key: NavigationSection; label: string }[] = [
  { key: 'operations', label: '运营工作台' },
  { key: 'intelligence', label: '智能运维' },
  { key: 'administration', label: '平台管理' }
]

const items: NavigationItem[] = [
  { key: '/overview', icon: <DashboardOutlined />, label: '运营总览', section: 'operations' },
  { key: '/devices', icon: <ClusterOutlined />, label: '设备管理', section: 'operations' },
  {
    key: '/products',
    icon: <AppstoreOutlined />,
    label: '产品与物模型',
    section: 'operations'
  },
  { key: '/orders', icon: <FileTextOutlined />, label: '订单管理', section: 'operations' },
  {
    key: '/protocol-documents',
    icon: <FileTextOutlined />,
    label: '协议文档',
    section: 'operations'
  },
  {
    key: '/simulator',
    icon: <ExperimentOutlined />,
    label: '充电模拟器',
    section: 'operations'
  },
  { key: '/assistant', icon: <RobotOutlined />, label: 'AI 助手', section: 'intelligence' },
  {
    key: '/investigations',
    icon: <ThunderboltOutlined />,
    label: '调查任务',
    section: 'intelligence'
  },
  { key: '/usage', icon: <WalletOutlined />, label: '模型额度', section: 'intelligence' },
  {
    key: '/ai-admin',
    icon: <BuildOutlined />,
    label: 'AI 能力管理',
    section: 'administration'
  },
  {
    key: '/ai-knowledge',
    icon: <FileTextOutlined />,
    label: 'AI 知识管理',
    section: 'administration'
  },
  { key: '/users', icon: <UserOutlined />, label: '用户管理', section: 'administration' }
]

const restrictedUserItems = new Set([
  '/assistant',
  '/ai-knowledge',
  '/ai-admin',
  '/investigations',
  '/usage'
])

export default function AppLayout({ user, children }: Props) {
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const isMobile = screens.lg === false
  const [navigationOpen, setNavigationOpen] = useState(false)

  const selected = useMemo(() => {
    const match = items
      .map((item) => item.key)
      .filter((key) => location.pathname === key || location.pathname.startsWith(`${key}/`))
      .sort((a, b) => b.length - a.length)
    return match.length > 0 ? [match[0]] : ['/overview']
  }, [location.pathname])

  const currentItem = items.find((item) => item.key === selected[0]) ?? items[0]
  const currentSection = sections.find((section) => section.key === currentItem.section)

  const visibleItems = useMemo(() => items.filter((item) => canAccess(item, user)), [user])
  const menuItems = useMemo(
    () =>
      sections.reduce<NonNullable<MenuProps['items']>>((groups, section) => {
        const children = visibleItems
          .filter((item) => item.section === section.key)
          .map((item) => ({
            key: item.key,
            icon: item.icon,
            label: <Link to={item.key}>{item.label}</Link>
          }))
        if (children.length) {
          groups.push({ key: section.key, label: section.label, type: 'group', children })
        }
        return groups
      }, []),
    [visibleItems]
  )

  useEffect(() => setNavigationOpen(false), [location.pathname])

  const logout = async () => {
    await api.logout()
    window.location.reload()
  }

  const brand = (
    <Link to="/" className="app-logo" aria-label="返回运营总览">
      <span className="mark">
        <ApiOutlined />
      </span>
      <span className="app-logo-text">
        充电桩协议平台
        <small>IOT PROTOCOL EXPERT</small>
      </span>
    </Link>
  )

  const navigation = (
    <Menu
      className="app-navigation"
      theme="dark"
      mode="inline"
      selectedKeys={selected}
      items={menuItems}
      onClick={() => setNavigationOpen(false)}
    />
  )

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" width={232} theme="dark">
        {brand}
        {navigation}
        <div className="app-sidebar-footer">
          <span className="app-sidebar-footer-icon">
            <ApiOutlined />
          </span>
          <span>
            协议驱动 · 智能运维
            <small>IPE Operations Console</small>
          </span>
        </div>
      </Sider>
      <Drawer
        className="app-nav-drawer"
        placement="left"
        width={280}
        open={isMobile && navigationOpen}
        closable={false}
        onClose={() => setNavigationOpen(false)}
        styles={{ body: { padding: 0, background: '#111b2e' } }}
      >
        {brand}
        {navigation}
      </Drawer>
      <Layout>
        <Header className="app-header">
          <div className="app-header-left">
            {isMobile ? (
              <Button
                className="app-menu-trigger"
                type="text"
                icon={<MenuOutlined />}
                aria-label="打开导航菜单"
                onClick={() => setNavigationOpen(true)}
              />
            ) : null}
            <div className="app-page-context">
              <span>{currentSection?.label}</span>
              <strong>{currentItem.label}</strong>
            </div>
          </div>
          <div className="app-header-actions">
            <Space size={6} className="app-role-list">
              {(user.roles ?? []).map((role) => (
                <Tag key={role} bordered={false}>
                  {role}
                </Tag>
              ))}
            </Space>
            <Dropdown
              placement="bottomRight"
              menu={{
                items: [
                  { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
                ]
              }}
            >
              <Button className="app-user-trigger" type="text">
                <Avatar size={32} icon={<UserOutlined />} />
                <span className="app-user-name">{user.username}</span>
                <DownOutlined className="app-user-caret" />
              </Button>
            </Dropdown>
          </div>
        </Header>
        <Content
          className={`app-content${location.pathname === '/assistant' ? ' app-content-assistant' : ''}`}
        >
          {children}
        </Content>
      </Layout>
    </Layout>
  )
}

function canAccess(item: NavigationItem, user: CurrentUser): boolean {
  if (user.allStations === false && !restrictedUserItems.has(item.key)) return false
  switch (item.key) {
    case '/users':
      return Boolean(user.permissions?.includes('iam:user:manage'))
    case '/ai-knowledge':
    case '/ai-admin':
      return Boolean(user.permissions?.includes('ai:admin'))
    case '/assistant':
      return Boolean(user.permissions?.includes('ai:use'))
    case '/investigations':
      return Boolean(user.permissions?.includes('investigation:read'))
    case '/usage':
      return Boolean(
        user.permissions?.includes('ai:use') ||
        user.permissions?.includes('usage:read') ||
        user.permissions?.includes('quota:grant')
      )
    default:
      return true
  }
}
