import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag
} from 'antd'
import { api } from '../api/client'
import type { ManagedUser, UserChange } from '../api/types'

const roles = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'OPS', label: '运营人员' },
  { value: 'VIEWER', label: '查看人员' }
]

export default function Users() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [form] = Form.useForm<UserChange>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ManagedUser | null>(null)
  const rolesQuery = useQuery({ queryKey: ['roles'], queryFn: api.roles })
  const [editingRole, setEditingRole] = useState<string | null>(null)
  const [rolePermissions, setRolePermissions] = useState<string[]>([])
  const roleSave = useMutation({
    mutationFn: () => api.updateRole(editingRole!, rolePermissions),
    onSuccess: () => {
      message.success('角色权限已保存，相关用户需重新登录')
      setEditingRole(null)
      void queryClient.invalidateQueries({ queryKey: ['roles'] })
    },
    onError: (error: Error) => message.error(error.message)
  })
  const users = useQuery({ queryKey: ['users'], queryFn: api.users })
  const stations = useQuery({ queryKey: ['stations'], queryFn: api.stations })
  const allStations = Form.useWatch('allStations', form)
  const save = useMutation({
    mutationFn: (value: UserChange) =>
      editing ? api.updateUser(editing.id, value) : api.createUser(value),
    onSuccess: () => {
      message.success('用户已保存')
      setOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (error: Error) => message.error(error.message)
  })
  const edit = (user: ManagedUser | null) => {
    setEditing(user)
    form.resetFields()
    form.setFieldsValue(
      user
        ? { ...user, password: '' }
        : {
            username: '',
            password: '',
            enabled: true,
            roles: ['VIEWER'],
            allStations: true,
            stationIds: []
          }
    )
    setOpen(true)
  }
  return (
    <Card
      title="用户管理"
      extra={
        <Button type="primary" onClick={() => edit(null)}>
          新增用户
        </Button>
      }
    >
      <Alert
        showIcon
        type="info"
        style={{ marginBottom: 16 }}
        message="受限站点账号仅可通过 AI 访问获授权站点；运营页面暂不支持站点级隔离。修改账号后其现有登录会话会失效。"
      />
      {users.error && <Alert type="error" message={(users.error as Error).message} />}
      <Table
        rowKey="id"
        loading={users.isLoading}
        dataSource={users.data ?? []}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          {
            title: '状态',
            render: (_, user) => (
              <Tag color={user.enabled ? 'green' : 'default'}>{user.enabled ? '启用' : '停用'}</Tag>
            )
          },
          {
            title: '角色',
            render: (_, user) =>
              user.roles.map((role) => (
                <Tag key={role}>{roles.find((item) => item.value === role)?.label ?? role}</Tag>
              ))
          },
          {
            title: '站点范围',
            render: (_, user) =>
              user.allStations
                ? '全部站点'
                : user.stationIds.length
                  ? user.stationIds.join(', ')
                  : '无站点'
          },
          {
            title: '操作',
            render: (_, user) => (
              <Button type="link" onClick={() => edit(user)}>
                编辑
              </Button>
            )
          }
        ]}
      />
      <Card title="角色权限" size="small" style={{ marginTop: 24 }}>
        {rolesQuery.error && <Alert type="error" message={(rolesQuery.error as Error).message} />}
        <Table
          rowKey="code"
          pagination={false}
          dataSource={rolesQuery.data ?? []}
          columns={[
            { title: '角色', dataIndex: 'name' },
            {
              title: '权限',
              render: (_, role) =>
                role.permissions.map((permission) => <Tag key={permission}>{permission}</Tag>)
            },
            {
              title: '操作',
              render: (_, role) => (
                <Button
                  type="link"
                  onClick={() => {
                    setEditingRole(role.code)
                    setRolePermissions(role.permissions)
                  }}
                >
                  配置
                </Button>
              )
            }
          ]}
        />
      </Card>
      <Modal
        title="配置角色权限"
        open={!!editingRole}
        confirmLoading={roleSave.isPending}
        onCancel={() => setEditingRole(null)}
        onOk={() => roleSave.mutate()}
      >
        <p>修改后，拥有该角色的用户会话将失效。</p>
        <Select
          mode="multiple"
          style={{ width: '100%' }}
          value={rolePermissions}
          onChange={setRolePermissions}
          options={[
            { value: 'iam:user:manage', label: '用户与角色管理' },
            { value: 'ops:read', label: '运营读取' },
            { value: 'ops:operate', label: '运营操作' },
            { value: 'ops:manage', label: '运营管理' },
            { value: 'ai:use', label: 'AI 使用' },
            { value: 'ai:admin', label: 'AI 管理' }
          ]}
        />
      </Modal>
      <Modal
        title={editing ? '编辑用户' : '新增用户'}
        open={open}
        confirmLoading={save.isPending}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(value) =>
            save.mutate({ ...value, stationIds: value.allStations ? [] : (value.stationIds ?? []) })
          }
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true },
              {
                pattern: /^[a-zA-Z0-9_.-]{3,64}$/,
                message: '请输入 3 至 64 位字母、数字、点、下划线或短横线'
              }
            ]}
          >
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="password"
            label={editing ? '重置密码（留空则不修改）' : '密码'}
            rules={[{ required: !editing }, { min: 8, max: 72 }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="roles" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select mode="multiple" options={roles} />
          </Form.Item>
          <Space>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="allStations" label="全部站点" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          {!allStations && (
            <Form.Item name="stationIds" label="可访问站点">
              <Select
                mode="multiple"
                placeholder="不选择表示无站点权限"
                options={(stations.data ?? []).map((station) => ({
                  value: station.id,
                  label: station.name
                }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Card>
  )
}
