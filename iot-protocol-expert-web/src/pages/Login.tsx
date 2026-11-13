import { useState } from 'react'
import { Alert, Button, Form, Input, Typography } from 'antd'
import { api } from '../api/client'

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

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="app-logo" style={{ padding: '0 0 18px' }}>
          <span className="mark">I</span>
          <span>
            充电桩协议平台
            <small>PROTOCOL OPERATIONS</small>
          </span>
        </div>
        <Typography.Title level={4} style={{ marginTop: 0 }}>
          登录运维平台
        </Typography.Title>
        <Typography.Paragraph className="muted" style={{ fontSize: 13 }}>
          演示账号见仓库 README：<b>admin</b>（管理员）、<b>ops</b>（运维）、<b>viewer</b>（只读）。
        </Typography.Paragraph>
        {error ? (
          <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />
        ) : null}
        <Form layout="vertical" onFinish={submit} initialValues={{ username: 'admin' }}>
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: true, message: '请输入账号' }]}
          >
            <Input size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={loading}>
            进入平台
          </Button>
        </Form>
      </div>
    </div>
  )
}
