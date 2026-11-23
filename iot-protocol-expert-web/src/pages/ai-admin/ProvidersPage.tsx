import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography
} from 'antd'
import { api } from '../../api/client'
import type {
  AiGatewayModel,
  AiGatewayModelCreate,
  AiGatewayProvider,
  AiGatewayProviderCreate
} from '../../api/types'
import { ErrorAlert } from './shared'

const { Paragraph, Text } = Typography
const modelKeyPattern = /^[a-z0-9][a-z0-9-]{1,39}$/

export default function ProvidersPage() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [providerForm] = Form.useForm<AiGatewayProviderCreate>()
  const [modelForm] = Form.useForm<AiGatewayModelCreate>()
  const [providerEditorOpen, setProviderEditorOpen] = useState(false)
  const [modelEditorOpen, setModelEditorOpen] = useState(false)
  const providers = useQuery({
    queryKey: ['ai-gateway-providers'],
    queryFn: api.aiGatewayProviders
  })
  const models = useQuery({
    queryKey: ['ai-gateway-models'],
    queryFn: api.aiGatewayModels
  })

  const createProvider = useMutation({
    mutationFn: (values: AiGatewayProviderCreate) => api.aiCreateGatewayProvider(values),
    onSuccess: () => {
      message.success('模型供应商已登记，上游密钥不会回显')
      setProviderEditorOpen(false)
      providerForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['ai-gateway-providers'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const createModel = useMutation({
    mutationFn: (values: AiGatewayModelCreate) => api.aiCreateGatewayModel(values),
    onSuccess: () => {
      message.success('逻辑模型已登记')
      setModelEditorOpen(false)
      modelForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['ai-gateway-models'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const providerName = (id: string) =>
    providers.data?.find((provider) => provider.id === id)?.name ?? id

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="供应商上游与网关逻辑模型"
        description="先在模型网关登记供应商和上游模型，再到“模型配置”创建同 key 的 Control 逻辑模型。供应商 API Key 只写不读，列表仅显示是否已配置。"
      />

      <Card
        title="模型供应商"
        extra={
          <Button type="primary" onClick={() => setProviderEditorOpen(true)}>
            登记供应商
          </Button>
        }
      >
        <Paragraph type="secondary">
          Base URL、上游 API Key 与加密存储均由模型网关管理，Control 不保存或回显这些密钥。
        </Paragraph>
        <ErrorAlert error={providers.error} />
        <Table<AiGatewayProvider>
          rowKey="id"
          loading={providers.isLoading}
          dataSource={providers.data ?? []}
          pagination={false}
          columns={[
            {
              title: '编码 / 名称',
              width: 240,
              render: (_, provider) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{provider.name}</Text>
                  <Text type="secondary" className="mono">
                    {provider.code}
                  </Text>
                </Space>
              )
            },
            {
              title: 'Base URL',
              dataIndex: 'baseUrl',
              ellipsis: true
            },
            {
              title: '上游密钥',
              width: 130,
              render: (_, provider) =>
                provider.credentialConfigured ? (
                  <Tag color="green">已配置</Tag>
                ) : (
                  <Tag>未配置</Tag>
                )
            },
            {
              title: '状态',
              dataIndex: 'state',
              width: 110,
              render: (state: string) => <Tag color={state === 'ACTIVE' ? 'green' : 'default'}>{state}</Tag>
            }
          ]}
        />
      </Card>

      <Card
        title="网关逻辑模型"
        extra={
          <Button
            type="primary"
            disabled={!providers.data?.length}
            onClick={() => setModelEditorOpen(true)}
          >
            登记逻辑模型
          </Button>
        }
      >
        <Paragraph type="secondary">
          Agent 和 Control 只使用逻辑模型 key。上游实际模型名仅由模型网关路由使用。
        </Paragraph>
        <ErrorAlert error={models.error} />
        <Table<AiGatewayModel>
          rowKey="id"
          loading={models.isLoading}
          dataSource={models.data ?? []}
          pagination={false}
          columns={[
            {
              title: '逻辑 key / 名称',
              width: 240,
              render: (_, model) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{model.displayName}</Text>
                  <Text type="secondary" className="mono">
                    {model.key}
                  </Text>
                </Space>
              )
            },
            {
              title: '供应商',
              width: 180,
              render: (_, model) => providerName(model.providerId)
            },
            {
              title: '上游模型',
              dataIndex: 'upstreamModel',
              width: 200,
              ellipsis: true
            },
            {
              title: '上下文 / 最大输出',
              width: 170,
              render: (_, model) =>
                `${model.contextWindow?.toLocaleString('zh-CN') ?? '—'} / ${
                  model.maxOutputTokens?.toLocaleString('zh-CN') ?? '—'
                }`
            },
            {
              title: '计量方式',
              dataIndex: 'countingMode',
              width: 190
            },
            {
              title: '状态',
              dataIndex: 'state',
              width: 110,
              render: (state: string) => <Tag color={state === 'ACTIVE' ? 'green' : 'default'}>{state}</Tag>
            }
          ]}
        />
      </Card>

      <Modal
        title="登记模型供应商"
        open={providerEditorOpen}
        confirmLoading={createProvider.isPending}
        onCancel={() => {
          setProviderEditorOpen(false)
          providerForm.resetFields()
        }}
        onOk={() => providerForm.submit()}
        destroyOnClose
      >
        <Form
          form={providerForm}
          layout="vertical"
          onFinish={(values) => createProvider.mutate(values)}
        >
          <Form.Item
            name="code"
            label="供应商编码"
            rules={[
              { required: true },
              { pattern: modelKeyPattern, message: '请输入 2-40 位小写字母、数字或短横线' }
            ]}
          >
            <Input placeholder="deepseek" />
          </Form.Item>
          <Form.Item name="name" label="供应商名称" rules={[{ required: true }]}>
            <Input placeholder="DeepSeek" maxLength={128} />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="Base URL"
            rules={[
              { required: true },
              { pattern: /^https?:\/\//, message: '必须是 http(s) 地址' }
            ]}
          >
            <Input placeholder="https://api.deepseek.com" />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label="上游 API Key"
            extra="提交后加密保存，不再回显。"
            rules={[{ required: true, min: 1, max: 8192 }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="登记网关逻辑模型"
        open={modelEditorOpen}
        confirmLoading={createModel.isPending}
        onCancel={() => {
          setModelEditorOpen(false)
          modelForm.resetFields()
        }}
        onOk={() => modelForm.submit()}
        destroyOnClose
      >
        <Form form={modelForm} layout="vertical" onFinish={(values) => createModel.mutate(values)}>
          <Form.Item
            name="providerCode"
            label="模型供应商"
            rules={[{ required: true }]}
          >
            <Select
              options={providers.data?.map((provider) => ({
                value: provider.code,
                label: `${provider.name} / ${provider.code}`
              }))}
            />
          </Form.Item>
          <Form.Item
            name="key"
            label="逻辑模型 key"
            rules={[
              { required: true },
              { pattern: modelKeyPattern, message: '请输入 2-40 位小写字母、数字或短横线' }
            ]}
          >
            <Input placeholder="deepseek-default" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
            <Input placeholder="DeepSeek 默认模型" maxLength={128} />
          </Form.Item>
          <Form.Item name="upstreamModel" label="上游模型名" rules={[{ required: true }]}>
            <Input placeholder="deepseek-chat" maxLength={128} />
          </Form.Item>
          <Form.Item name="contextWindow" label="上下文窗口">
            <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="65536" />
          </Form.Item>
          <Form.Item name="maxOutputTokens" label="最大输出 Token" initialValue={2048}>
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="countingMode" label="计量方式" initialValue="CONSERVATIVE_ESTIMATE">
            <Select options={[{ value: 'CONSERVATIVE_ESTIMATE', label: '保守估算' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
