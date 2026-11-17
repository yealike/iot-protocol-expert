import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography
} from 'antd'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type {
  AiComponentOrigin,
  AiMcpComponent,
  AiMcpTestResult,
  AiMcpToolDescriptor,
  AiMcpToolExecutionCategory,
  AiMcpToolPolicy
} from '../../api/types'
import { formatDateTime } from '../../utils/dateTime'
import { enabledTag, ErrorAlert, jsonText, rememberPublication } from './shared'

const { Text } = Typography

interface McpFormValues {
  key: string
  displayName: string
  description?: string
  origin: AiComponentOrigin
  enabled: boolean
  timeoutMs?: number
  credentialId?: string
  endpoint?: string
  serverName?: string
  namespace?: string
  version?: string
}

interface PublishFormValues {
  version?: string
  reportId: string
  idempotencyKey: string
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined
}

export default function McpsPage() {
  const { message, modal } = App.useApp()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [mcpForm] = Form.useForm<McpFormValues>()
  const [publishForm] = Form.useForm<PublishFormValues>()
  const [editingMcp, setEditingMcp] = useState<AiMcpComponent | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [testResult, setTestResult] = useState<AiMcpTestResult | null>(null)
  const [toolMcp, setToolMcp] = useState<AiMcpComponent | null>(null)
  const [publishingMcp, setPublishingMcp] = useState<AiMcpComponent | null>(null)
  const [preparingPublishId, setPreparingPublishId] = useState<string | null>(null)

  const mcps = useQuery({ queryKey: ['ai-mcps'], queryFn: api.aiMcps })
  const credentials = useQuery({ queryKey: ['ai-credentials'], queryFn: api.aiCredentials })
  const toolCatalog = useQuery({
    queryKey: ['ai-mcp-tools', toolMcp?.id],
    queryFn: () => api.aiMcpTools(toolMcp!.id),
    enabled: !!toolMcp
  })
  const toolPolicies = useQuery({
    queryKey: ['ai-mcp-tool-policies', toolMcp?.id],
    queryFn: () => api.aiMcpToolPolicies(toolMcp!.id),
    enabled: !!toolMcp
  })

  const saveMcp = useMutation({
    mutationFn: async ({ values, mcp }: { values: McpFormValues; mcp: AiMcpComponent | null }) => {
      const nativeRegistryRef =
        values.origin === 'NATIVE_REGISTRY'
          ? {
              ...(mcp?.nativeRegistryRef ?? {}),
              serverName: values.serverName?.trim(),
              namespace: values.namespace?.trim(),
              version: values.version?.trim()
            }
          : {}
      const draft: Record<string, unknown> = {
        ...(mcp?.draft ?? {}),
        transport: 'STREAMABLE_HTTP',
        timeoutMs: values.timeoutMs ?? 20000
      }
      if (values.credentialId) {
        draft.credentialId = values.credentialId
      } else {
        delete draft.credentialId
      }
      draft.endpoint = values.endpoint?.trim()
      if (mcp) {
        return api.aiUpdateMcp(mcp.id, {
          expectedRevision: mcp.revision,
          displayName: values.displayName,
          description: values.description,
          nativeRegistryRef,
          draft,
          enabled: values.enabled
        })
      }
      return api.aiCreateMcp({
        key: values.key,
        displayName: values.displayName,
        description: values.description,
        origin: values.origin,
        nativeRegistryRef,
        draft
      })
    },
    onSuccess: () => {
      message.success('MCP 草稿已保存；发布前请重新测试')
      setEditorOpen(false)
      setEditingMcp(null)
      void queryClient.invalidateQueries({ queryKey: ['ai-mcps'] })
      void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tools'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const deleteMcp = useMutation({
    mutationFn: (mcp: AiMcpComponent) => api.aiDeleteMcp(mcp.id),
    onSuccess: () => {
      message.success('MCP 已删除')
      void queryClient.invalidateQueries({ queryKey: ['ai-mcps'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const testMcp = useMutation({
    mutationFn: (mcp: AiMcpComponent) => api.aiTestMcp(mcp.id),
    onSuccess: (result, mcp) => {
      setTestResult(result)
      void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tools', mcp.id] })
    },
    onError: (error: Error) => {
      setTestResult({ success: false, message: error.message })
      message.error(error.message)
    }
  })

  const publishMcp = useMutation({
    mutationFn: ({ mcp, values }: { mcp: AiMcpComponent; values: PublishFormValues }) =>
      api.aiPublishMcp(mcp.id, {
        version: values.version?.trim() || undefined,
        reportId: values.reportId.trim(),
        expectedRevision: mcp.revision,
        idempotencyKey: values.idempotencyKey.trim()
      }),
    onSuccess: (receipt, { mcp, values }) => {
      const publicationId = rememberPublication(receipt, 'MCP', values.version)
      message.success('发布已提交，正在准备并注册 MCP 精确版本')
      setPublishingMcp(null)
      void queryClient.invalidateQueries({ queryKey: ['ai-mcps'] })
      void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tools', mcp.id] })
      navigate(`/ai-admin/publications/${publicationId}`)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const reviewTool = useMutation({
    mutationFn: ({
      mcp,
      toolName,
      executionCategory
    }: {
      mcp: AiMcpComponent
      toolName: string
      executionCategory: AiMcpToolExecutionCategory
    }) => api.aiReviewMcpTool(mcp.id, { toolName, executionCategory }),
    onSuccess: (_, { mcp }) => {
      message.success('工具执行类别已更新')
      void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tool-policies', mcp.id] })
      void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tools', mcp.id] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const openEditor = (mcp: AiMcpComponent | null) => {
    setEditingMcp(mcp)
    mcpForm.resetFields()
    const nativeRef = mcp?.nativeRegistryRef ?? {}
    const draft = mcp?.draft ?? {}
    const mcpOrigin: AiComponentOrigin =
      mcp?.origin === 'NATIVE_REGISTRY' ? 'NATIVE_REGISTRY' : 'REMOTE'
    mcpForm.setFieldsValue(
      mcp
        ? {
            key: mcp.key,
            displayName: mcp.displayName,
            description: mcp.description ?? '',
            origin: mcpOrigin,
            enabled: mcp.enabled,
            timeoutMs: typeof draft.timeoutMs === 'number' ? draft.timeoutMs : 20000,
            credentialId: stringValue(draft.credentialId),
            endpoint: stringValue(draft.endpoint),
            serverName: stringValue(nativeRef.serverName),
            namespace: stringValue(nativeRef.namespace) ?? 'public',
            version: stringValue(nativeRef.version) ?? '1.0.0'
          }
        : {
            key: '',
            displayName: '',
            description: '',
            origin: 'REMOTE',
            enabled: true,
            timeoutMs: 20000,
            credentialId: undefined,
            endpoint: '',
            serverName: '',
            namespace: 'public',
            version: '1.0.0'
          }
    )
    setEditorOpen(true)
  }

  const confirmTest = (mcp: AiMcpComponent) => {
    modal.confirm({
      title: '测试 MCP 连接与工具目录',
      content:
        '将临时初始化 MCP 并执行 tools/list，不执行业务工具。测试报告会用于随后发布时冻结工具目录。',
      okText: '开始测试',
      cancelText: '取消',
      onOk: () => testMcp.mutate(mcp)
    })
  }

  const openPublish = async (mcp: AiMcpComponent) => {
    setPreparingPublishId(mcp.id)
    try {
      const catalog = await api.aiMcpTools(mcp.id)
      if (catalog.source !== 'TEST' || !catalog.reportId || catalog.success === false) {
        message.warning('没有可用的成功测试报告，请先测试 MCP')
        return
      }
      setPublishingMcp(mcp)
      publishForm.resetFields()
      publishForm.setFieldsValue({
        version: undefined,
        reportId: catalog.reportId,
        idempotencyKey: crypto.randomUUID()
      })
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setPreparingPublishId(null)
    }
  }

  const credentialOptions = (credentials.data ?? []).map((credential) => ({
    value: credential.id,
    label: `${credential.displayName}（${credential.key}${credential.enabled ? '' : ' · 停用'}）`,
    disabled: !credential.enabled
  }))

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="MCP 发布是不可变冻结"
        description="测试只读取工具目录；发布时会把目录、入口策略和内容摘要冻结为新版本。已有发布版本不能覆盖，配置变更后必须重新测试。"
      />

      <Card
        title="MCP 服务"
        extra={
          <Button type="primary" onClick={() => openEditor(null)}>
            新建 MCP
          </Button>
        }
      >
        <ErrorAlert error={mcps.error} />
        <Table
          rowKey="id"
          loading={mcps.isLoading}
          dataSource={mcps.data ?? []}
          pagination={{ pageSize: 15 }}
          scroll={{ x: 1160 }}
          columns={[
            {
              title: 'Key / 名称',
              width: 210,
              render: (_, mcp) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{mcp.displayName}</Text>
                  <Text type="secondary" className="mono">
                    {mcp.key}
                  </Text>
                </Space>
              )
            },
            {
              title: '来源',
              width: 130,
              render: (_, mcp) => (
                <Tag color={mcp.origin === 'NATIVE_REGISTRY' ? 'blue' : 'purple'}>
                  {mcp.origin === 'NATIVE_REGISTRY' ? '历史目录导入' : '远程 HTTP'}
                </Tag>
              )
            },
            {
              title: '发布版本',
              width: 120,
              render: (_, mcp) =>
                mcp.publishedVersion ? (
                  <Tag color="green">v{mcp.publishedVersion}</Tag>
                ) : (
                  <Text type="secondary">未发布</Text>
                )
            },
            {
              title: '状态',
              width: 90,
              render: (_, mcp) => enabledTag(mcp.enabled)
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
              width: 330,
              render: (_, mcp) => (
                <Space size={0} wrap>
                  <Button type="link" size="small" onClick={() => openEditor(mcp)}>
                    编辑
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    loading={testMcp.isPending && testMcp.variables?.id === mcp.id}
                    onClick={() => confirmTest(mcp)}
                  >
                    测试
                  </Button>
                  <Button type="link" size="small" onClick={() => setToolMcp(mcp)}>
                    工具目录
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    loading={preparingPublishId === mcp.id}
                    onClick={() => void openPublish(mcp)}
                  >
                    发布
                  </Button>
                  <Popconfirm
                    title="确认删除该 MCP？"
                    description="已有发布版本或被 Agent 引用时服务端会拒绝。"
                    onConfirm={() => deleteMcp.mutate(mcp)}
                  >
                    <Button type="link" danger size="small">
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              )
            }
          ]}
        />
      </Card>

      <Modal
        title={editingMcp ? '编辑 MCP' : '新建 MCP'}
        open={editorOpen}
        width={760}
        confirmLoading={saveMcp.isPending}
        onCancel={() => {
          setEditorOpen(false)
          setEditingMcp(null)
        }}
        onOk={() => mcpForm.submit()}
        destroyOnClose
      >
        <Form
          form={mcpForm}
          layout="vertical"
          onFinish={(values) => saveMcp.mutate({ values, mcp: editingMcp })}
        >
          <div className="product-form-grid">
            <Form.Item
              name="key"
              label="MCP key"
              rules={[
                { required: true },
                {
                  pattern: /^[a-z0-9][a-z0-9-]{1,39}$/,
                  message: '请输入 2-40 位小写字母、数字或短横线'
                }
              ]}
            >
              <Input disabled={!!editingMcp} placeholder="ops" />
            </Form.Item>
            <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
              <Input placeholder="运维 REST-to-MCP" maxLength={128} />
            </Form.Item>
            <Form.Item name="origin" label="来源" rules={[{ required: true }]}>
              <Select
                disabled={!!editingMcp}
                options={[
                  ...(editingMcp?.origin === 'NATIVE_REGISTRY'
                    ? [{ value: 'NATIVE_REGISTRY', label: '历史目录导入（请补充 MCP 入口）' }]
                    : []),
                  { value: 'REMOTE', label: '远程 Streamable HTTP' }
                ]}
              />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch disabled={!editingMcp} />
            </Form.Item>
          </div>

          {editingMcp?.origin === 'NATIVE_REGISTRY' && (
            <div className="product-form-grid">
              <Form.Item
                name="serverName"
                label="原生目录服务名"
                rules={[{ required: true, message: '请输入 serverName' }]}
              >
                <Input placeholder="ipe-ops-rest" />
              </Form.Item>
              <Form.Item name="namespace" label="原生目录 namespace">
                <Input placeholder="public" />
              </Form.Item>
              <Form.Item name="version" label="原生目录版本">
                <Input placeholder="1.0.0" />
              </Form.Item>
            </div>
          )}
          <Form.Item
            name="endpoint"
            label="MCP endpoint"
            rules={[
              { required: true },
              { pattern: /^https?:\/\//, message: '必须是 http(s) 地址' }
            ]}
          >
            <Input placeholder="https://mcp.example.com/mcp" />
          </Form.Item>

          <div className="product-form-grid">
            <Form.Item
              name="timeoutMs"
              label="连接 / 初始化超时（毫秒）"
              rules={[{ required: true }, { type: 'number', min: 1000, max: 20000 }]}
            >
              <InputNumber style={{ width: '100%' }} min={1000} max={20000} step={1000} />
            </Form.Item>
            <Form.Item name="credentialId" label="鉴权凭据引用">
              <Select allowClear placeholder="公开 MCP 可留空" options={credentialOptions} />
            </Form.Item>
          </div>
          <Form.Item name="description" label="说明">
            <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={2000} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`发布 MCP${publishingMcp ? ` · ${publishingMcp.displayName}` : ''}`}
        open={!!publishingMcp}
        confirmLoading={publishMcp.isPending}
        onCancel={() => setPublishingMcp(null)}
        onOk={() => publishForm.submit()}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="发布会在平台冻结工具目录、入口和精确版本；版本一旦存在不可覆盖。"
        />
        <Form
          form={publishForm}
          layout="vertical"
          onFinish={(values) => publishingMcp && publishMcp.mutate({ mcp: publishingMcp, values })}
        >
          <Form.Item
            name="version"
            label="版本（留空自动递增 patch）"
            rules={[
              {
                pattern: /^\d+\.\d+\.\d+$/,
                message: '版本必须是 major.minor.patch'
              }
            ]}
          >
            <Input placeholder="例如 1.0.0" />
          </Form.Item>
          <Form.Item
            name="reportId"
            label="测试报告 ID"
            rules={[{ required: true, message: '请先测试并选择报告' }]}
          >
            <Input className="mono" />
          </Form.Item>
          <Form.Item name="idempotencyKey" label="幂等键" rules={[{ required: true }]}>
            <Input className="mono" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="MCP 测试报告"
        open={!!testResult}
        width={900}
        footer={<Button onClick={() => setTestResult(null)}>关闭</Button>}
        onCancel={() => setTestResult(null)}
      >
        {testResult ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type={testResult.success ? 'success' : 'error'}
              showIcon
              message={
                testResult.success
                  ? 'MCP 初始化与工具发现成功'
                  : (testResult.message ?? 'MCP 测试失败')
              }
            />
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="Endpoint" span={2}>
                <Text className="mono">{testResult.endpoint ?? '—'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="Server">
                {testResult.serverName ?? '—'}
                {testResult.serverVersion ? ` · ${testResult.serverVersion}` : ''}
              </Descriptions.Item>
              <Descriptions.Item label="协议版本">
                {testResult.protocolVersion ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="延迟">
                {testResult.latencyMs == null ? '—' : `${testResult.latencyMs} ms`}
              </Descriptions.Item>
              <Descriptions.Item label="报告 ID">
                <Text className="mono">{testResult.reportId ?? '—'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="测试时间" span={2}>
                {formatDateTime(testResult.testedAt)}
              </Descriptions.Item>
            </Descriptions>
            {(testResult.warnings ?? []).map((warning) => (
              <Alert key={warning} type="warning" showIcon message={warning} />
            ))}
            <ToolTable tools={testResult.tools} />
          </Space>
        ) : null}
      </Modal>

      <Drawer
        title={`工具目录${toolMcp ? ` · ${toolMcp.displayName}` : ''}`}
        width={920}
        open={!!toolMcp}
        onClose={() => setToolMcp(null)}
        extra={
          <Button
            loading={toolCatalog.isFetching}
            onClick={() =>
              toolMcp &&
              void queryClient.invalidateQueries({ queryKey: ['ai-mcp-tools', toolMcp.id] })
            }
          >
            刷新
          </Button>
        }
      >
        <ErrorAlert error={toolCatalog.error} />
        {toolCatalog.isLoading ? (
          <Card loading />
        ) : toolCatalog.data ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="来源">
                {toolCatalog.data.source === 'TEST'
                  ? '最近测试'
                  : toolCatalog.data.source === 'PUBLISHED'
                    ? '已发布目录'
                    : '无目录'}
              </Descriptions.Item>
              <Descriptions.Item label="测试时间">
                {formatDateTime(toolCatalog.data.testedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="Report ID">
                <Text className="mono">{toolCatalog.data.reportId ?? '—'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="已发布版本">
                {toolCatalog.data.publishedVersion
                  ? `v${toolCatalog.data.publishedVersion}`
                  : '未发布'}
              </Descriptions.Item>
              <Descriptions.Item label="Catalog Digest" span={2}>
                <Text className="mono" copyable={!!toolCatalog.data.catalogDigest}>
                  {toolCatalog.data.catalogDigest ?? '—'}
                </Text>
              </Descriptions.Item>
            </Descriptions>
            {(toolCatalog.data.warnings ?? []).map((warning) => (
              <Alert key={warning} type="warning" showIcon message={warning} />
            ))}
            <Alert
              type="warning"
              showIcon
              message="只读边界由平台审核决定"
              description="Provider 的 readOnlyHint 只作参考。只有平台审核为 READ_ONLY 的工具才能被 Agent 绑定和调用；WRITE 与 UNKNOWN 一律拒绝。"
            />
            <Card size="small" title="最近测试工具">
              <ToolTable
                tools={toolCatalog.data.tools}
                policyMap={
                  new Map((toolPolicies.data ?? []).map((policy) => [policy.toolName, policy]))
                }
                reviewingTool={reviewTool.isPending ? reviewTool.variables?.toolName : undefined}
                onReview={(toolName, executionCategory) =>
                  toolMcp && reviewTool.mutate({ mcp: toolMcp, toolName, executionCategory })
                }
              />
            </Card>
            <Card size="small" title="当前发布工具目录">
              <ToolTable tools={toolCatalog.data.publishedTools} />
            </Card>
          </Space>
        ) : null}
      </Drawer>
    </Space>
  )
}

function ToolTable({
  tools,
  policyMap,
  reviewingTool,
  onReview
}: {
  tools?: AiMcpToolDescriptor[]
  policyMap?: Map<string, AiMcpToolPolicy>
  reviewingTool?: string
  onReview?: (toolName: string, category: AiMcpToolExecutionCategory) => void
}) {
  if (!tools?.length) {
    return <Alert type="info" showIcon message="暂无工具目录" />
  }
  return (
    <Table<AiMcpToolDescriptor>
      rowKey="name"
      size="small"
      dataSource={tools}
      pagination={false}
      expandable={{
        expandedRowRender: (tool) => (
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
            {jsonText(tool.inputSchema)}
          </pre>
        )
      }}
      columns={[
        {
          title: '工具',
          dataIndex: 'name',
          width: 220,
          render: (name: string, tool) => (
            <Space direction="vertical" size={0}>
              <Text strong className="mono">
                {name}
              </Text>
              <Tag color={tool.readOnly ? 'green' : 'orange'}>
                {tool.readOnly ? '只读' : '可能写入'}
              </Tag>
            </Space>
          )
        },
        ...(onReview
          ? [
              {
                title: '平台执行类别',
                dataIndex: 'name',
                width: 150,
                render: (name: string) => {
                  const category = policyMap?.get(name)?.executionCategory ?? 'UNKNOWN'
                  return (
                    <Select<AiMcpToolExecutionCategory>
                      size="small"
                      value={category}
                      loading={reviewingTool === name}
                      style={{ width: 120 }}
                      onChange={(value) => onReview(name, value)}
                      options={[
                        { value: 'READ_ONLY', label: '只读（批准）' },
                        { value: 'WRITE', label: '写入' },
                        { value: 'UNKNOWN', label: '未确认' }
                      ]}
                    />
                  )
                }
              }
            ]
          : []),
        {
          title: '说明',
          dataIndex: 'description',
          render: (description?: string | null) => description || '—'
        },
        {
          title: 'Schema Digest',
          dataIndex: 'schemaDigest',
          width: 190,
          ellipsis: true,
          render: (digest?: string) => <Text className="mono">{digest ?? '—'}</Text>
        }
      ]}
    />
  )
}
