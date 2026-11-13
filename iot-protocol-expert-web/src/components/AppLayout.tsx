import { useMemo } from 'react'
import { Avatar, Button, Dropdown, Layout, Menu, Space, Tag } from 'antd'
import {
  AlertOutlined,
  AppstoreOutlined,
  ClusterOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  LogoutOutlined,
  RobotOutlined,
  FileTextOutlined,
  UserOutlined
} from '@ant-design/icons'
import { Link, useLocation } from 'react-router-dom'
import { api } from '../api/client'
import type { CurrentUser } from '../api/types'

const { Header, Sider, Content } = Layout

interface Props {
  user: CurrentUser
  children: React.ReactNode
}

const items = [
  { key: '/overview', icon: <DashboardOutlined />, label: <Link to="/overview">总览</Link> },
  { key: '/devices', icon: <ClusterOutlined />, label: <Link to="/devices">设备</Link> },
  { key: '/products', icon: <AppstoreOutlined />, label: <Link to="/products">产品与物模型</Link> },
  { key: '/orders', icon: <FileTextOutlined />, label: <Link to="/orders">订单管理</Link> },
  {
    key: '/protocol-documents',
    icon: <FileTextOutlined />,
    label: <Link to="/protocol-documents">协议文档</Link>
  },
  { key: '/simulator', icon: <ExperimentOutlined />, label: <Link to="/simulator">充电模拟器</Link> },
  { key: '/alarms', icon: <AlertOutlined />, label: <Link to="/alarms">告警</Link> },
  { key: '/assistant', icon: <RobotOutlined />, label: <Link to="/assistant">AI 助手</Link> }
  ,{ key: '/users', icon: <UserOutlined />, label: <Link to="/users">用户管理</Link> }
  ,{ key: '/ai-knowledge', icon: <FileTextOutlined />, label: <Link to="/ai-knowledge">AI 知识管理</Link> }
]

export default function AppLayout({ user, children }: Props) {
  const location = useLocation()

  const selected = useMemo(() => {
    const match = items
      .map((item) => item.key)
      .filter((key) => location.pathname === key || location.pathname.startsWith(`${key}/`))
      .sort((a, b) => b.length - a.length)
    return match.length > 0 ? [match[0]] : ['/overview']
  }, [location.pathname])

  const logout = async () => {
    await api.logout()
    window.location.reload()
  }

  return (
    <Layout className="app-shell">
      <Sider breakpoint="lg" collapsedWidth={0} width={214}>
        <div className="app-logo">
          <span className="mark">K</span>
          <span>
            慧舟 · 能源物联
            <small>KEEL ENERGY OPS</small>
          </span>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={selected}
          items={items.filter((item) => user.allStations === false && !['/assistant', '/ai-knowledge'].includes(item.key) ? false : item.key === '/users' ? user.permissions?.includes('iam:user:manage') : item.key === '/ai-knowledge' ? user.permissions?.includes('ai:admin') : item.key === '/assistant' ? user.permissions?.includes('ai:use') : true)}
          style={{ background: 'transparent' }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 12,
            paddingInline: 20,
            borderBottom: '1px solid #e4e9f1',
            position: 'sticky',
            top: 0,
            zIndex: 10,
            backdropFilter: 'blur(8px)'
          }}
        >
          <Space size={8}>
            {(user.roles ?? []).map((role) => (
              <Tag key={role} color="blue" bordered={false}>
                {role}
              </Tag>
            ))}
          </Space>
          <Dropdown
            menu={{
              items: [
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
              ]
            }}
          >
            <Button type="text">
              <Avatar size="small" icon={<UserOutlined />} style={{ marginRight: 8 }} />
              {user.username}
            </Button>
          </Dropdown>
        </Header>
        <Content style={{ padding: '22px 24px 40px' }}>{children}</Content>
      </Layout>
    </Layout>
  )
}
