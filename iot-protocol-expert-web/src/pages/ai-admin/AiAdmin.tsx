import { Alert, Tabs } from 'antd'
import {
  ApiOutlined,
  CloudServerOutlined,
  RobotOutlined,
  SettingOutlined,
  ToolOutlined
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import type { CurrentUser } from '../../api/types'

const tabs = [
  { key: 'models', label: '模型配置', icon: <SettingOutlined /> },
  { key: 'providers', label: '供应商登记', icon: <CloudServerOutlined /> },
  { key: 'mcps', label: 'MCP 服务', icon: <ApiOutlined /> },
  { key: 'skills', label: 'Skill 编辑', icon: <ToolOutlined /> },
  { key: 'agents', label: 'Agent 构建', icon: <RobotOutlined /> },
  { key: 'publications', label: '发布与注册', icon: <ApiOutlined /> }
]

export default function AiAdmin({ user }: { user: CurrentUser }) {
  const location = useLocation()
  const navigate = useNavigate()
  const segment = location.pathname.split('/')[2] || 'models'
  const active = tabs.some((item) => item.key === segment) ? segment : 'models'

  if (!(user.permissions ?? []).includes('ai:admin')) {
    return <Alert type="error" showIcon message="无权访问 AI 能力管理" />
  }

  return (
    <div className="ai-admin-shell">
      <div className="page-header">
        <div>
          <h1>Agent 在线构建与管理</h1>
          <p>在线维护模型、MCP、Skill 与 Agent，通过不可变版本完成发布和回滚。</p>
        </div>
      </div>
      <Tabs
        activeKey={active}
        items={tabs}
        onChange={(key) => navigate(`/ai-admin/${key}`)}
        destroyOnHidden
      />
      <Outlet />
    </div>
  )
}
