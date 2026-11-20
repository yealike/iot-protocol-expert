import { useState } from 'react'
import { Alert, Button, Form, Input } from 'antd'
import {
  ApiOutlined,
  ArrowRightOutlined,
  ClusterOutlined,
  LockOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  UserOutlined
} from '@ant-design/icons'
import { api } from '../api/client'

const features = [
  {
    icon: <ClusterOutlined />,
    title: '多协议设备接入',
    description: '统一管理平台、设备与充电交易事实'
  },
  {
    icon: <ApiOutlined />,
    title: '全链路运营观测',
    description: '从协议报文到设备状态，快速定位关键线索'
  },
  {
    icon: <RobotOutlined />,
    title: 'AI 辅助诊断',
    description: '结合协议知识与设备事实，让排障更有依据'
  }
]

export default function Login({ onSuccess }: { onSuccess: () => void }) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (values: { username: string; password: string }) => {
    setLoading(true)
    setError(null)
    try {
      await api.login(values.username, values.password)
      onSuccess()
    } catch (e) {
      setError(e instanceof Error ? e.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  const brand = (
    <div className="app-logo">
      <span className="mark">
        <ApiOutlined />
      </span>
      <span className="app-logo-text">
        充电桩协议平台
        <small>IOT PROTOCOL EXPERT</small>
      </span>
    </div>
  )

  return (
    <div className="login-wrap">
      <section className="login-showcase">
        {brand}
        <div className="login-showcase-content">
          <div className="login-kicker">
            <span /> 智能充电运营工作台
          </div>
          <h1>
            让协议接入更简单，
            <br />
            让运营诊断更智能。
          </h1>
          <p>连接设备、协议与业务数据，为充电运营团队提供清晰、可靠的工作入口。</p>
          <div className="login-feature-list">
            {features.map((feature) => (
              <div className="login-feature" key={feature.title}>
                <span className="login-feature-icon">{feature.icon}</span>
                <div>
                  <strong>{feature.title}</strong>
                  <span>{feature.description}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
        <div className="login-showcase-footer">PROTOCOL OPERATIONS · INTELLIGENT DIAGNOSIS</div>
      </section>

      <main className="login-main">
        <div className="login-mobile-brand">{brand}</div>
        <div className="login-card">
          <div className="login-heading">
            <span>WELCOME BACK</span>
            <h2>欢迎回来</h2>
            <p>使用平台账号登录，继续您的运维工作。</p>
          </div>
          {error ? (
            <Alert type="error" message={error} showIcon style={{ marginBottom: 20 }} />
          ) : null}
          <Form
            layout="vertical"
            onFinish={submit}
            initialValues={{ username: 'admin' }}
            requiredMark={false}
          >
            <Form.Item
              name="username"
              label="账号"
              rules={[{ required: true, message: '请输入账号' }]}
            >
              <Input
                size="large"
                prefix={<UserOutlined />}
                placeholder="请输入账号"
                autoComplete="username"
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                size="large"
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Button
              className="login-submit"
              type="primary"
              htmlType="submit"
              size="large"
              block
              loading={loading}
              icon={<ArrowRightOutlined />}
              iconPosition="end"
            >
              进入平台
            </Button>
          </Form>
          <div className="login-security">
            <SafetyCertificateOutlined />
            账号会话由平台统一安全管理
          </div>
        </div>
        <footer className="login-footer">IPE Protocol Operations · 企业级充电运营与协议诊断</footer>
      </main>
    </div>
  )
}
