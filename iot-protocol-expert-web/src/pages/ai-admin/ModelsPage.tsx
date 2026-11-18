import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Typography
} from 'antd'
import { api } from '../../api/client'
import type { AiCredential, AiModelProfile, AiModelTestResult } from '../../api/types'
import { formatDateTime } from '../../utils/dateTime'
import { enabledTag, ErrorAlert, jsonText, parseJsonObject } from './shared'

const { Paragraph, Text } = Typography

interface ModelFormValues {
  key: string
  displayName: string
  description?: string
  enabled: boolean
  optionsJson: string
}

interface CredentialFormValues {
  key: string
  displayName: string
  secret: string
}

interface RotateFormValues {
  secret: string
}

export default function ModelsPage() {
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [modelForm] = Form.useForm<ModelFormValues>()
  const [credentialForm] = Form.useForm<CredentialFormValues>()
  const [rotateForm] = Form.useForm<RotateFormValues>()
  const [editingModel, setEditingModel] = useState<AiModelProfile | null>(null)
  const [modelEditorOpen, setModelEditorOpen] = useState(false)
  const [credentialEditorOpen, setCredentialEditorOpen] = useState(false)
  const [rotatingCredential, setRotatingCredential] = useState<AiCredential | null>(null)
  const [testResult, setTestResult] = useState<AiModelTestResult | null>(null)

  const models = useQuery({ queryKey: ['ai-models'], queryFn: api.aiModels })
  const credentials = useQuery({ queryKey: ['ai-credentials'], queryFn: api.aiCredentials })

  const saveModel = useMutation({
    mutationFn: async ({
      values,
      model
    }: {
      values: ModelFormValues
      model: AiModelProfile | null
    }) => {
      const options = parseJsonObject(values.optionsJson)
      if (model) {
        return api.aiUpdateModel(model.id, {
          expectedRevision: model.revision,
          displayName: values.displayName,
          description: values.description,
          options,
          enabled: values.enabled
        })
      }
      return api.aiCreateModel({
        key: values.key,
        displayName: values.displayName,
        description: values.description,
        options
      })
    },
    onSuccess: () => {
      message.success('模型配置已保存')
      setModelEditorOpen(false)
      setEditingModel(null)
      void queryClient.invalidateQueries({ queryKey: ['ai-models'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const testModel = useMutation({
    mutationFn: (model: AiModelProfile) => api.aiTestModel(model.id),
    onSuccess: (result) => {
      setTestResult(result)
      void queryClient.invalidateQueries({ queryKey: ['ai-models'] })
    },
    onError: (error: Error) => {
      setTestResult({ success: false, message: error.message })
      message.error(error.message)
    }
  })

  const createCredential = useMutation({
    mutationFn: (values: CredentialFormValues) => api.aiCreateCredential(values),
    onSuccess: () => {
      message.success('凭据引用已创建，Secret 不会再次回显')
      setCredentialEditorOpen(false)
      credentialForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['ai-credentials'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const rotateCredential = useMutation({
    mutationFn: ({ credential, secret }: { credential: AiCredential; secret: string }) =>
      api.aiRotateCredential(credential.id, secret),
    onSuccess: () => {
      message.success('凭据已轮换，新 Run 将使用新版本')
      setRotatingCredential(null)
      rotateForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['ai-credentials'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const openModelEditor = (model: AiModelProfile | null) => {
    setEditingModel(model)
    modelForm.resetFields()
    modelForm.setFieldsValue(
      model
        ? {
            key: model.key,
            displayName: model.displayName,
            description: model.description ?? '',
            enabled: model.enabled,
            optionsJson: jsonText(model.options)
          }
        : {
            key: '',
            displayName: '',
            description: '',
            enabled: true,
            optionsJson: jsonText({ temperature: 0.1, maxTokens: 2048 })
          }
    )
    setModelEditorOpen(true)
  }

  const confirmTest = (model: AiModelProfile) => {
    modal.confirm({
      title: '测试模型连通性',
      content:
        '该操作会按逻辑模型 key 经模型网关向上游供应商发送最小请求，并产生少量 token 消耗。工具调用、Skill 与 RAG 不会参与测试。',
      okText: '开始测试',
      cancelText: '取消',
      onOk: () => testModel.mutate(model)
    })
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="逻辑模型配置与供应商上游分离"
        description="此页维护模型网关的逻辑模型 key。上游 Base URL、实际模型名与 API Key 在模型网关的供应商登记中管理，不会在此页提交或回显。保存配置不会自动发起外部请求。"
      />

      <Card
        title="模型配置"
        extra={
          <Space>
            <Button onClick={() => navigate('/ai-admin/providers')}>上游供应商登记</Button>
            <Button type="primary" onClick={() => openModelEditor(null)}>
              新建逻辑模型
            </Button>
          </Space>
        }
      >
        <ErrorAlert error={models.error} />
        <Table
          rowKey="id"
          loading={models.isLoading}
          dataSource={models.data ?? []}
          pagination={{ pageSize: 15 }}
          scroll={{ x: 1040 }}
          columns={[
            {
              title: 'Key / 名称',
              width: 190,
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
              title: '说明',
              dataIndex: 'description',
              width: 260,
              ellipsis: true
            },
            {
              title: '生成参数',
              width: 220,
              render: (_, model) => (
                <Text className="mono" ellipsis>
                  {jsonText(model.options)}
                </Text>
              )
            },
            {
              title: '状态',
              width: 90,
              render: (_, model) => enabledTag(model.enabled)
            },
            { title: 'Revision', dataIndex: 'revision', width: 90 },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 170,
              render: (value: string) => formatDateTime(value)
            },
            {
              title: '操作',
              fixed: 'right',
              width: 150,
              render: (_, model) => (
                <Space size={2}>
                  <Button type="link" size="small" onClick={() => openModelEditor(model)}>
                    编辑
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    loading={testModel.isPending && testModel.variables?.id === model.id}
                    onClick={() => confirmTest(model)}
                  >
                    测试
                  </Button>
                </Space>
              )
            }
          ]}
        />
      </Card>

      <Card
        title="共享凭据引用"
        extra={<Button onClick={() => setCredentialEditorOpen(true)}>新建凭据</Button>}
      >
        <Paragraph type="secondary">
          Secret 采用只写模式：创建或轮换后仅显示掩码、keyId 与 secretRevision。MCP
          等支持引用解析的平台资源可用凭据；模型供应商的上游密钥以模型网关供应商登记为准。
        </Paragraph>
        <ErrorAlert error={credentials.error} />
        <Table
          rowKey="id"
          loading={credentials.isLoading}
          dataSource={credentials.data ?? []}
          pagination={false}
          scroll={{ x: 820 }}
          columns={[
            {
              title: 'Key / 名称',
              width: 210,
              render: (_, credential) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{credential.displayName}</Text>
                  <Text type="secondary" className="mono">
                    {credential.key}
                  </Text>
                </Space>
              )
            },
            {
              title: 'Secret',
              width: 100,
              render: (_, credential) => (
                <Text className="mono">{credential.masked ?? '******'}</Text>
              )
            },
            {
              title: 'keyId',
              dataIndex: 'keyId',
              width: 160,
              ellipsis: true,
              render: (value?: string) => value ?? '—'
            },
            {
              title: 'Secret Revision',
              width: 135,
              render: (_, credential) =>
                credential.secretRevision ?? credential.currentSecretRevision
            },
            {
              title: '状态',
              width: 90,
              render: (_, credential) => enabledTag(credential.enabled)
            },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 170,
              render: (value: string) => formatDateTime(value)
            },
            {
              title: '操作',
              width: 110,
              render: (_, credential) => (
                <Button
                  type="link"
                  size="small"
                  disabled={!credential.enabled}
                  onClick={() => {
                    setRotatingCredential(credential)
                    rotateForm.resetFields()
                  }}
                >
                  轮换 Secret
                </Button>
              )
            }
          ]}
        />
      </Card>

      <Modal
        title={editingModel ? '编辑模型' : '新建模型'}
        open={modelEditorOpen}
        width={720}
        confirmLoading={saveModel.isPending}
        onCancel={() => {
          setModelEditorOpen(false)
          setEditingModel(null)
        }}
        onOk={() => modelForm.submit()}
        destroyOnClose
      >
        <Form
          form={modelForm}
          layout="vertical"
          onFinish={(values) => saveModel.mutate({ values, model: editingModel })}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="这里只配置 Control 的逻辑模型 key。请先在模型网关登记同名上游模型，再让 Agent 或 API 引用此 key。"
          />
          <div className="product-form-grid">
            <Form.Item
              name="key"
              label="逻辑模型 key"
              extra="调用和网关路由均使用此 key。"
              rules={[
                { required: true },
                {
                  pattern: /^[a-z0-9][a-z0-9-]{1,39}$/,
                  message: '请输入 2-40 位小写字母、数字或短横线'
                }
              ]}
            >
              <Input disabled={!!editingModel} placeholder="deepseek-default" />
            </Form.Item>
            <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
              <Input placeholder="DeepSeek 默认模型" maxLength={128} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch disabled={!editingModel} />
            </Form.Item>
          </div>
          <Form.Item name="description" label="说明">
            <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={2000} />
          </Form.Item>
          <Form.Item
            name="optionsJson"
            label="生成参数（JSON 对象）"
            extra="例如 temperature、maxTokens、contextWindow；由 Runtime 按模型 profile 读取。"
            rules={[
              { required: true },
              {
                validator: async (_, value: string) => {
                  try {
                    parseJsonObject(value)
                  } catch {
                    throw new Error('必须是合法 JSON 对象')
                  }
                }
              }
            ]}
          >
            <Input.TextArea
              className="mono"
              autoSize={{ minRows: 6, maxRows: 14 }}
              spellCheck={false}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建凭据引用"
        open={credentialEditorOpen}
        confirmLoading={createCredential.isPending}
        onCancel={() => setCredentialEditorOpen(false)}
        onOk={() => credentialForm.submit()}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="Secret 仅提交一次，服务端加密保存且不会回显。"
        />
        <Form
          form={credentialForm}
          layout="vertical"
          onFinish={(values) => createCredential.mutate(values)}
        >
          <Form.Item
            name="key"
            label="凭据 key"
            rules={[
              { required: true },
              {
                pattern: /^[a-z0-9][a-z0-9-]{1,39}$/,
                message: '请输入 2-40 位小写字母、数字或短横线'
              }
            ]}
          >
            <Input placeholder="platform-api-key" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
            <Input placeholder="平台资源 API Key" maxLength={128} />
          </Form.Item>
          <Form.Item name="secret" label="Secret" rules={[{ required: true, min: 8, max: 4096 }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`轮换 Secret${rotatingCredential ? ` · ${rotatingCredential.displayName}` : ''}`}
        open={!!rotatingCredential}
        confirmLoading={rotateCredential.isPending}
        onCancel={() => {
          setRotatingCredential(null)
          rotateForm.resetFields()
        }}
        onOk={() => rotateForm.submit()}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="轮换后旧 secretRevision 仍供已固定的旧发布引用解析；新 Run 使用最新 revision。"
        />
        <Form
          form={rotateForm}
          layout="vertical"
          onFinish={(values) =>
            rotatingCredential &&
            rotateCredential.mutate({ credential: rotatingCredential, secret: values.secret })
          }
        >
          <Form.Item
            name="secret"
            label="新 Secret"
            rules={[{ required: true, min: 8, max: 4096 }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="模型连通测试结果"
        open={!!testResult}
        footer={<Button onClick={() => setTestResult(null)}>关闭</Button>}
        onCancel={() => setTestResult(null)}
      >
        {testResult ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type={testResult.success ? 'success' : 'error'}
              showIcon
              message={testResult.success ? '模型连通正常' : (testResult.message ?? '模型测试失败')}
            />
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="模型">{testResult.model ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="耗时">
                {testResult.latencyMs == null ? '—' : `${testResult.latencyMs} ms`}
              </Descriptions.Item>
              <Descriptions.Item label="错误码">{testResult.errorCode ?? '—'}</Descriptions.Item>
            </Descriptions>
            {testResult.usage ? (
              <div>
                <Text strong>Token 用量</Text>
                <pre
                  style={{
                    maxHeight: 280,
                    overflow: 'auto',
                    padding: 12,
                    borderRadius: 6,
                    background: '#f0f3f8',
                    fontSize: 12
                  }}
                >
                  {jsonText(testResult.usage)}
                </pre>
              </div>
            ) : null}
            {testResult.note ? <Text type="secondary">{testResult.note}</Text> : null}
          </Space>
        ) : null}
      </Modal>
    </Space>
  )
}
