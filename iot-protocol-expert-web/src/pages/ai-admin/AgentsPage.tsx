import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography
} from 'antd'
import type { FormInstance } from 'antd'
import {
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SendOutlined
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type {
  AiAgentCompileResult,
  AiAgentComponent,
  AiAgentMcpBinding,
  AiAgentMcpBindingInput,
  AiAgentRelease,
  AiAgentSkillBinding,
  AiAgentSkillBindingInput,
  AiAgentUpdate,
  AiMcpComponent,
  AiMcpRelease,
  AiModelProfile,
  AiSkillComponent,
  AiSkillRelease
} from '../../api/types'
import { formatDateTime } from '../../utils/dateTime'
import {
  enabledTag,
  ErrorAlert,
  rememberPublication,
  StateTag
} from './shared'

const { Text, Paragraph } = Typography
const MAX_OUTPUT_TOKENS = 131072
const MAX_ITERATIONS = 12
const MAX_TOOL_CALLS = 32
const MAX_RUN_TIMEOUT_MS = 300000

interface McpBindingForm {
  mcpReleaseId?: string
  componentKey?: string
  version?: string
  required?: boolean
  executionOrder?: number
  toolNames?: string[]
}

interface SkillBindingForm {
  skillReleaseId?: string
  componentKey?: string
  version?: string
  order?: number
  loadMode?: 'AUTO' | 'ALWAYS'
}

interface AgentForm {
  key: string
  displayName: string
  description?: string
  modelProfileId?: string
  systemPrompt: string
  temperature?: number
  maxTokens?: number
  contextWindow?: number
  maxIterations: number
  maxToolCalls: number
  runTimeoutMs: number
  enabled: boolean
  mcpBindings?: McpBindingForm[]
  skillBindings?: SkillBindingForm[]
}

interface PublishForm {
  version?: string
  idempotencyKey: string
}

export default function AgentsPage() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [form] = Form.useForm<AgentForm>()
  const [publishForm] = Form.useForm<PublishForm>()
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [validation, setValidation] = useState<AiAgentCompileResult | null>(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const [publishAgent, setPublishAgent] = useState<AiAgentComponent | null>(null)
  const rollbackKeysRef = useRef<Record<string, string>>({})
  const loadedAgentRef = useRef<string | null>(null)
  const revisionRef = useRef<number | null>(null)

  const agents = useQuery({ queryKey: ['ai-agents-admin'], queryFn: api.aiAgents })
  const models = useQuery({ queryKey: ['ai-models'], queryFn: api.aiModels })
  const mcps = useQuery({ queryKey: ['ai-mcps'], queryFn: api.aiMcps })
  const skills = useQuery({ queryKey: ['ai-skills'], queryFn: api.aiSkills })
  const detail = useQuery({
    queryKey: ['ai-agent-detail', editingId],
    queryFn: () => api.aiAgent(editingId as string),
    enabled: editorOpen && Boolean(editingId)
  })

  const detailAgent = detail.data?.agent ?? null
  const releases = detail.data?.releases ?? []
  const publishedMcps = useMemo(
    () => (mcps.data ?? []).filter((item) => item.publishedVersion),
    [mcps.data]
  )
  const publishedSkills = useMemo(
    () => (skills.data ?? []).filter((item) => item.publishedVersion),
    [skills.data]
  )

  useEffect(() => {
    if (!editorOpen || !detail.data || !editingId || loadedAgentRef.current === editingId) return
    const agent = detail.data.agent
    const mcpRows = groupMcpBindings(detail.data.mcpBindings ?? [])
    const skillRows = (detail.data.skillBindings ?? []).map((binding) => ({
      skillReleaseId: binding.skillReleaseId,
      componentKey: binding.componentKey,
      version: binding.version,
      order: binding.order,
      loadMode: binding.loadMode
    }))
    form.setFieldsValue({
      key: agent.key,
      displayName: agent.displayName,
      description: agent.description ?? '',
      modelProfileId: agent.modelProfileId ?? undefined,
      systemPrompt: agent.systemPrompt,
      temperature: agent.modelOptions?.temperature ?? undefined,
      maxTokens: agent.modelOptions?.maxTokens ?? undefined,
      contextWindow: agent.modelOptions?.contextWindow ?? undefined,
      maxIterations: agent.budget?.maxIterations ?? 6,
      maxToolCalls: agent.budget?.maxToolCalls ?? 12,
      runTimeoutMs: agent.budget?.runTimeoutMs ?? 120000,
      enabled: agent.enabled,
      mcpBindings: mcpRows,
      skillBindings: skillRows
    })
    loadedAgentRef.current = editingId
    revisionRef.current = agent.revision
  }, [detail.data, editingId, editorOpen, form])

  const setDefaultForm = () => {
    form.resetFields()
    form.setFieldsValue({
      key: '',
      displayName: '',
      description: '',
      modelProfileId: models.data?.[0]?.id,
      systemPrompt: '',
      temperature: 0.1,
      maxTokens: 2048,
      contextWindow: undefined,
      maxIterations: 6,
      maxToolCalls: 12,
      runTimeoutMs: 120000,
      enabled: true,
      mcpBindings: [],
      skillBindings: []
    })
  }

  const openCreate = () => {
    setEditingId(null)
    loadedAgentRef.current = null
    revisionRef.current = null
    setDefaultForm()
    setEditorOpen(true)
  }

  const openEdit = (agent: AiAgentComponent) => {
    setEditingId(agent.id)
    loadedAgentRef.current = null
    revisionRef.current = null
    form.resetFields()
    setEditorOpen(true)
  }

  const persistDraft = async (values: AgentForm): Promise<AiAgentComponent> => {
    if (editingId && !detail.data) {
      throw new Error('Agent 详情尚未加载，请稍后重试')
    }
    const options = {
      temperature: values.temperature,
      maxTokens: values.maxTokens,
      contextWindow: values.contextWindow
    }
    const budget = {
      maxIterations: values.maxIterations,
      maxToolCalls: values.maxToolCalls,
      runTimeoutMs: values.runTimeoutMs
    }
    const mcpBindings = buildMcpBindings(values.mcpBindings)
    const skillBindings = buildSkillBindings(values.skillBindings)
    const mcpChanged =
      !detail.data ||
      !sameBindings(
        canonicalMcpBindings(detail.data.mcpBindings ?? []),
        canonicalMcpBindings(mcpBindings)
      )
    const skillChanged =
      !detail.data ||
      !sameBindings(
        canonicalSkillBindings(detail.data.skillBindings ?? []),
        canonicalSkillBindings(skillBindings)
      )
    let saved: AiAgentComponent
    if (editingId) {
      const body: AiAgentUpdate = {
        expectedRevision: revisionRef.current ?? detailAgent?.revision ?? 0,
        displayName: values.displayName,
        description: values.description,
        modelProfileId: values.modelProfileId,
        systemPrompt: values.systemPrompt,
        modelOptions: options,
        budget,
        enabled: values.enabled
      }
      if (mcpChanged) body.mcpBindings = mcpBindings
      if (skillChanged) body.skillBindings = skillBindings
      saved = await api.aiUpdateAgent(editingId, body)
      revisionRef.current = saved.revision
    } else {
      saved = await api.aiCreateAgent({
        key: values.key,
        displayName: values.displayName,
        description: values.description,
        modelProfileId: values.modelProfileId,
        systemPrompt: values.systemPrompt,
        modelOptions: options,
        budget,
        mcpBindings,
        skillBindings
      })
      setEditingId(saved.id)
      revisionRef.current = saved.revision
    }
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['ai-agents-admin'] }),
      queryClient.invalidateQueries({ queryKey: ['ai-agent-detail', saved.id] })
    ])
    return saved
  }

  const save = useMutation({
    mutationFn: async () => persistDraft(form.getFieldsValue(true)),
    onSuccess: () => message.success('Agent 草稿已保存'),
    onError: (error: Error) => message.error(error.message)
  })

  const validate = useMutation({
    mutationFn: async () => {
      const saved = await persistDraft(form.getFieldsValue(true))
      return api.aiValidateAgent(saved.id)
    },
    onSuccess: (result) => setValidation(result),
    onError: (error: Error) => message.error(error.message)
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.aiDeleteAgent(id),
    onSuccess: async () => {
      message.success('Agent 已删除')
      await queryClient.invalidateQueries({ queryKey: ['ai-agents-admin'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const publish = useMutation({
    mutationFn: (value: PublishForm) => {
      if (!publishAgent) throw new Error('Agent 尚未保存')
      return api.aiPublishAgent(publishAgent.id, {
        version: value.version?.trim() || undefined,
        expectedRevision: publishAgent.revision,
        idempotencyKey: value.idempotencyKey
      })
    },
    onSuccess: async (result, value) => {
      const publicationId = rememberPublication(result, 'AGENT', value.version)
      message.success('Agent 发布已提交')
      setPublishOpen(false)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['ai-agents-admin'] }),
        queryClient.invalidateQueries({ queryKey: ['ai-agent-detail', publishAgent?.id] })
      ])
      navigate(`/ai-admin/publications/${publicationId}`)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const rollback = useMutation({
    mutationFn: (version: string) => {
      if (!detailAgent) throw new Error('Agent 尚未加载')
      const idempotencyKey = rollbackKeysRef.current[version] ?? crypto.randomUUID()
      rollbackKeysRef.current[version] = idempotencyKey
      return api.aiRollbackAgent(detailAgent.id, {
        targetVersion: version,
        expectedRevision: detailAgent.revision,
        idempotencyKey
      })
    },
    onSuccess: async (result, version) => {
      delete rollbackKeysRef.current[version]
      const publicationId = rememberPublication(result, 'AGENT', result.version)
      message.success('回滚已提交，等待发布完成后默认版本才会切换')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['ai-agent-detail', editingId] }),
        queryClient.invalidateQueries({ queryKey: ['ai-agents-admin'] })
      ])
      navigate(`/ai-admin/publications/${publicationId}`)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const openPublish = async () => {
    if (save.isPending || validate.isPending) return
    try {
      const agent = await save.mutateAsync()
      setPublishAgent(agent)
      publishForm.setFieldsValue({
        version: nextPatchVersion(agent.publishedVersion),
        idempotencyKey: crypto.randomUUID()
      })
      setPublishOpen(true)
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="Agent 草稿"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => agents.refetch()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建 Agent
            </Button>
          </Space>
        }
      >
        <ErrorAlert error={agents.error} />
        <Table<AiAgentComponent>
          rowKey="id"
          loading={agents.isLoading}
          dataSource={agents.data ?? []}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          columns={[
            {
              title: 'Agent',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{row.displayName}</Text>
                  <Text type="secondary" className="mono">
                    {row.key}
                  </Text>
                </Space>
              )
            },
            {
              title: '模型',
              render: (_, row) => modelLabel(models.data, row.modelProfileId)
            },
            {
              title: '系统提示',
              dataIndex: 'systemPrompt',
              ellipsis: true,
              render: (value: string) => (
                <Tooltip title={value}>
                  <Text type="secondary">{value || '未配置'}</Text>
                </Tooltip>
              )
            },
            {
              title: '已发布',
              dataIndex: 'publishedVersion',
              width: 110,
              render: (value: string | null) =>
                value ? <Tag color="green">{value}</Tag> : <Tag>未发布</Tag>
            },
            {
              title: '状态',
              dataIndex: 'enabled',
              width: 80,
              render: (value: boolean) => enabledTag(value)
            },
            { title: 'revision', dataIndex: 'revision', width: 90 },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 170,
              render: (value: string) => formatDateTime(value)
            },
            {
              title: '操作',
              width: 150,
              render: (_, row) => (
                <Space size={0}>
                  <Button type="link" size="small" onClick={() => openEdit(row)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="确认删除该 Agent？已有发布版本时会被拒绝。"
                    onConfirm={() => remove.mutate(row.id)}
                  >
                    <Button type="link" size="small" danger loading={remove.isPending}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              )
            }
          ]}
        />
      </Card>

      <Drawer
        title={editingId ? '编辑 Agent' : '新建 Agent'}
        width="min(1040px, 96vw)"
        open={editorOpen}
        onClose={() => setEditorOpen(false)}
        destroyOnClose
        extra={
          <Space wrap>
            <Button onClick={() => setEditorOpen(false)}>取消</Button>
            <Button loading={save.isPending} onClick={() => save.mutate()}>
              保存草稿
            </Button>
            <Button
              icon={<SafetyCertificateOutlined />}
              loading={validate.isPending}
              onClick={() => validate.mutate()}
            >
              校验
            </Button>
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={publish.isPending || save.isPending}
              onClick={() => void openPublish()}
            >
              发布
            </Button>
          </Space>
        }
      >
        {detail.error ? <ErrorAlert error={detail.error} /> : null}
        {editingId && !detail.isLoading ? (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`当前草稿 revision ${detailAgent?.revision ?? '-'} · 默认发布版本 ${
              detailAgent?.publishedVersion || '未发布'
            }`}
          />
        ) : null}
        <Form<AgentForm>
          form={form}
          layout="vertical"
          disabled={Boolean(editingId) && detail.isLoading}
          initialValues={{
            temperature: 0.1,
            maxTokens: 2048,
            contextWindow: undefined,
            maxIterations: 6,
            maxToolCalls: 12,
            runTimeoutMs: 120000,
            enabled: true,
            mcpBindings: [],
            skillBindings: []
          }}
        >
          <Card size="small" title="基础信息" style={{ marginBottom: 16 }}>
            <div className="ai-form-grid">
              <Form.Item
                name="key"
                label="Agent key"
                rules={[
                  { required: true },
                  {
                    pattern: /^[a-z0-9][a-z0-9-]{1,39}$/,
                    message: '只允许 2-40 位小写字母、数字和短横线'
                  }
                ]}
              >
                <Input disabled={Boolean(editingId)} placeholder="protocol-expert" />
              </Form.Item>
              <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
                <Input maxLength={128} />
              </Form.Item>
              <Form.Item name="description" label="说明" className="ai-form-wide">
                <Input.TextArea rows={2} maxLength={2048} />
              </Form.Item>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </div>
          </Card>

          <Card size="small" title="模型与生成参数" style={{ marginBottom: 16 }}>
            <div className="ai-form-grid">
              <Form.Item
                name="modelProfileId"
                label="模型 profile"
                rules={[{ required: true, message: '请选择模型' }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  options={(models.data ?? []).map((model) => ({
                    value: model.id,
                    label: `${model.displayName} · ${model.key}`,
                    disabled: !model.enabled
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="temperature"
                label="Temperature"
                rules={[{ type: 'number', min: 0, max: 2 }]}
              >
                <InputNumber style={{ width: '100%' }} min={0} max={2} step={0.1} placeholder="0.1" />
              </Form.Item>
              <Form.Item
                name="maxTokens"
                label="最大输出 Token"
                rules={[{ type: 'number', min: 1, max: MAX_OUTPUT_TOKENS }]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={MAX_OUTPUT_TOKENS}
                  step={256}
                  placeholder="2048"
                />
              </Form.Item>
              <Form.Item
                name="contextWindow"
                label="上下文窗口"
                rules={[{ type: 'number', min: 1 }]}
              >
                <InputNumber style={{ width: '100%' }} min={1} step={1024} placeholder="可选" />
              </Form.Item>
            </div>
          </Card>

          <Card size="small" title="系统提示" style={{ marginBottom: 16 }}>
            <Form.Item
              name="systemPrompt"
              rules={[{ required: true, message: '系统提示不能为空' }]}
              style={{ marginBottom: 0 }}
            >
              <Input.TextArea
                className="ai-prompt-editor"
                spellCheck={false}
                placeholder="定义 Agent 的职责、事实边界、工具使用约束和输出格式。"
              />
            </Form.Item>
          </Card>

          <Card size="small" title="预算" style={{ marginBottom: 16 }}>
            <div className="ai-budget-grid">
              <Form.Item
                name="maxIterations"
                label="最大迭代次数"
                rules={[{ required: true, type: 'number', min: 1, max: MAX_ITERATIONS }]}
              >
                <InputNumber min={1} max={MAX_ITERATIONS} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="maxToolCalls"
                label="最大工具调用"
                rules={[{ required: true, type: 'number', min: 1, max: MAX_TOOL_CALLS }]}
              >
                <InputNumber min={1} max={MAX_TOOL_CALLS} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="runTimeoutMs"
                label="Run 超时（毫秒）"
                rules={[
                  { required: true, type: 'number', min: 5000, max: MAX_RUN_TIMEOUT_MS }
                ]}
              >
                <InputNumber min={5000} max={MAX_RUN_TIMEOUT_MS} step={5000} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </Card>

          <Card
            size="small"
            title="MCP 工具白名单"
            extra={
              <Text type="secondary">最多 8 个 MCP、32 个可见工具；未勾选的工具不会授予模型。</Text>
            }
            style={{ marginBottom: 16 }}
          >
            <Form.List name="mcpBindings">
              {(fields, { add, remove: removeField }) => (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  {fields.map((field) => (
                    <McpBindingRow
                      key={field.key}
                      fieldName={field.name}
                      form={form}
                      mcps={publishedMcps}
                      onRemove={() => removeField(field.name)}
                    />
                  ))}
                  <Button
                    type="dashed"
                    block
                    icon={<PlusOutlined />}
                    onClick={() =>
                      add({
                        componentKey: undefined,
                        version: undefined,
                        required: true,
                        executionOrder: fields.length,
                        toolNames: []
                      })
                    }
                  >
                    添加 MCP
                  </Button>
                </Space>
              )}
            </Form.List>
          </Card>

          <Card
            size="small"
            title="Skill 绑定"
            extra={<Text type="secondary">最多 16 个；ALWAYS 建议不超过 2 个。</Text>}
            style={{ marginBottom: 16 }}
          >
            <Form.List name="skillBindings">
              {(fields, { add, remove: removeField }) => (
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  {fields.map((field) => (
                    <SkillBindingRow
                      key={field.key}
                      fieldName={field.name}
                      form={form}
                      skills={publishedSkills}
                      onRemove={() => removeField(field.name)}
                    />
                  ))}
                  <Button
                    type="dashed"
                    block
                    icon={<PlusOutlined />}
                    onClick={() => add({ loadMode: 'AUTO', order: fields.length })}
                  >
                    添加 Skill
                  </Button>
                </Space>
              )}
            </Form.List>
          </Card>
        </Form>

        {editingId ? (
          <Card
            size="small"
            title="发布记录"
            extra={
              <Button size="small" icon={<ReloadOutlined />} onClick={() => detail.refetch()}>
                刷新
              </Button>
            }
          >
            <Table<AiAgentRelease>
              rowKey="id"
              size="small"
              loading={detail.isFetching}
              dataSource={releases}
              pagination={false}
              locale={{ emptyText: '暂无发布版本' }}
              columns={[
                { title: '版本', dataIndex: 'version', width: 110 },
                {
                  title: '状态',
                  dataIndex: 'state',
                  width: 110,
                  render: (value: string) => <StateTag state={value} />
                },
                { title: 'generation', dataIndex: 'generation', width: 100 },
                {
                  title: 'manifestDigest',
                  dataIndex: 'manifestDigest',
                  ellipsis: true,
                  render: (value: string) => (
                    <Text className="mono">{value ? `${value.slice(0, 16)}…` : '-'}</Text>
                  )
                },
                {
                  title: '创建时间',
                  dataIndex: 'createdAt',
                  width: 170,
                  render: (value: string) => formatDateTime(value)
                },
                {
                  title: '操作',
                  width: 140,
                  render: (_, row) =>
                    ['ACTIVE', 'RETIRED'].includes(row.state) &&
                    row.version !== detailAgent?.publishedVersion ? (
                      <Popconfirm
                        title={`将默认发布指针回滚到 ${row.version}？`}
                        description="回滚异步执行；发布成功后新会话才会使用该版本，旧会话仍固定原版本。"
                        onConfirm={() => rollback.mutate(row.version)}
                      >
                        <Button type="link" size="small" loading={rollback.isPending}>
                          回滚到此版本
                        </Button>
                      </Popconfirm>
                    ) : (
                      <Text type="secondary">
                        {row.version === detailAgent?.publishedVersion ? '当前默认' : '-'}
                      </Text>
                    )
                }
              ]}
            />
          </Card>
        ) : null}
      </Drawer>

      <Modal
        title="Agent 校验结果"
        open={Boolean(validation)}
        footer={<Button onClick={() => setValidation(null)}>关闭</Button>}
        onCancel={() => setValidation(null)}
        width={780}
      >
        {validation ? <ValidationResult result={validation} /> : null}
      </Modal>

      <Modal
        title="发布 Agent"
        open={publishOpen}
        confirmLoading={publish.isPending}
        onCancel={() => setPublishOpen(false)}
        onOk={() => publishForm.submit()}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          message="发布将冻结当前模型、提示词、工具白名单和 Skill 版本，已发布内容不可覆盖。"
          style={{ marginBottom: 16 }}
        />
        <Form form={publishForm} layout="vertical" onFinish={(value) => publish.mutate(value)}>
          <Form.Item
            name="version"
            label="版本"
            extra="留空由后端递增；格式必须为 major.minor.patch。"
          >
            <Input placeholder="1.0.0" />
          </Form.Item>
          <Form.Item name="idempotencyKey" label="幂等键" rules={[{ required: true }]}>
            <Input className="mono" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

function McpBindingRow({
  fieldName,
  form,
  mcps,
  onRemove
}: {
  fieldName: number
  form: FormInstance<AgentForm>
  mcps: AiMcpComponent[]
  onRemove: () => void
}) {
  const componentKey = Form.useWatch(['mcpBindings', fieldName, 'componentKey'], form) as
    string | undefined
  const version = Form.useWatch(['mcpBindings', fieldName, 'version'], form) as string | undefined
  const allRows =
    (Form.useWatch('mcpBindings', form) as McpBindingForm[] | undefined) ?? ([] as McpBindingForm[])
  const selectedMcp = mcps.find((item) => item.key === componentKey)
  const releases = useQuery<AiMcpRelease[]>({
    queryKey: ['ai-mcp-releases', selectedMcp?.id],
    queryFn: () => api.aiMcpReleases(selectedMcp!.id),
    enabled: Boolean(selectedMcp)
  })
  const releaseOptions = useMemo(() => {
    const options = (releases.data ?? []).map((release) => ({
      value: release.version,
      label: `v${release.version} · ${release.state}`,
      disabled: release.state !== 'ACTIVE' && release.version !== version
    }))
    if (version && !options.some((option) => option.value === version)) {
      options.unshift({ value: version, label: `v${version} · 历史绑定`, disabled: true })
    }
    return options
  }, [releases.data, version])
  const selectedRelease = useMemo(
    () => (releases.data ?? []).find((release) => release.version === version) ?? null,
    [releases.data, version]
  )
  const availableTools = selectedRelease?.tools ?? []

  return (
    <div className="ai-mcp-binding">
      <div className="ai-binding-row ai-binding-row-head">
        <Form.Item
          name={[fieldName, 'componentKey']}
          rules={[{ required: true, message: '请选择 MCP' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="MCP 服务"
            options={mcps.map((mcp) => ({
              value: mcp.key,
              label: `${mcp.displayName} · ${mcp.key}${mcp.enabled ? '' : ' · 已停用'}`,
              disabled:
                !mcp.enabled ||
                (mcp.key !== componentKey &&
                  allRows.some(
                    (item, index) => index !== fieldName && item?.componentKey === mcp.key
                  ))
            }))}
            onChange={(key: string) => {
              const mcp = mcps.find((item) => item.key === key)
              form.setFieldValue(['mcpBindings', fieldName, 'version'], mcp?.publishedVersion)
              form.setFieldValue(['mcpBindings', fieldName, 'mcpReleaseId'], undefined)
              form.setFieldValue(['mcpBindings', fieldName, 'toolNames'], [])
            }}
          />
        </Form.Item>
        <Form.Item
          name={[fieldName, 'version']}
          rules={[{ required: true, message: '请选择发布版本' }]}
        >
          <Select
            placeholder="精确版本"
            loading={releases.isLoading}
            options={releaseOptions}
            onChange={(value: string) => {
              const release = (releases.data ?? []).find((item) => item.version === value)
              form.setFieldValue(['mcpBindings', fieldName, 'mcpReleaseId'], release?.id)
              form.setFieldValue(['mcpBindings', fieldName, 'toolNames'], [])
            }}
          />
        </Form.Item>
        <Form.Item
          name={[fieldName, 'executionOrder']}
          initialValue={fieldName}
          rules={[{ required: true }]}
        >
          <InputNumber min={0} max={7} placeholder="顺序" style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name={[fieldName, 'required']} label="必需" valuePropName="checked" initialValue>
          <Switch size="small" />
        </Form.Item>
        <Button danger type="text" icon={<DeleteOutlined />} onClick={onRemove} />
      </div>
      <Form.Item
        name={[fieldName, 'toolNames']}
        label="允许模型调用的工具"
        rules={[
          {
            validator: (_, value: string[]) =>
              value?.length
                ? Promise.resolve()
                : Promise.reject(new Error('至少选择一个工具；未授权工具不会授予模型'))
          }
        ]}
        style={{ marginBottom: 4 }}
      >
        <Checkbox.Group
          className="ai-tool-checkboxes"
          options={availableTools.map((tool) => ({
            value: tool.name,
            label: `${tool.name}${tool.readOnly ? ' · 只读' : ''}`
          }))}
        />
      </Form.Item>
      {!selectedMcp ? (
        <Text type="secondary">先选择已发布 MCP，再勾选工具白名单。</Text>
      ) : releases.isLoading ? (
        <Text type="secondary">正在读取已发布工具目录…</Text>
      ) : releases.error ? (
        <Alert type="error" showIcon message={(releases.error as Error).message} />
      ) : !selectedRelease ? (
        <Alert
          type="warning"
          showIcon
          message="该 MCP 没有可用的已发布版本，请先在 MCP 页面完成测试和发布。"
        />
      ) : availableTools.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          message="该 MCP 没有可用的已发布工具目录，请先在 MCP 页面完成测试和发布。"
        />
      ) : (
        <Text type="secondary">
          已发布版本 {selectedRelease.version} · catalog{' '}
          {selectedRelease.catalogDigest ? `${selectedRelease.catalogDigest.slice(0, 12)}…` : '-'}
        </Text>
      )}
    </div>
  )
}

function SkillBindingRow({
  fieldName,
  form,
  skills,
  onRemove
}: {
  fieldName: number
  form: FormInstance<AgentForm>
  skills: AiSkillComponent[]
  onRemove: () => void
}) {
  const componentKey = Form.useWatch(['skillBindings', fieldName, 'componentKey'], form) as
    string | undefined
  const version = Form.useWatch(['skillBindings', fieldName, 'version'], form) as string | undefined
  const allRows =
    (Form.useWatch('skillBindings', form) as SkillBindingForm[] | undefined) ??
    ([] as SkillBindingForm[])
  const selectedSkill = skills.find((item) => item.key === componentKey)
  const releases = useQuery<AiSkillRelease[]>({
    queryKey: ['ai-skill-releases', selectedSkill?.id],
    queryFn: () => api.aiSkillReleases(selectedSkill!.id),
    enabled: Boolean(selectedSkill)
  })
  const releaseOptions = useMemo(() => {
    const options = (releases.data ?? []).map((release) => ({
      value: release.version,
      label: `v${release.version} · ${release.state}`,
      disabled: release.state !== 'ACTIVE' && release.version !== version
    }))
    if (version && !options.some((option) => option.value === version)) {
      options.unshift({ value: version, label: `v${version} · 历史绑定`, disabled: true })
    }
    return options
  }, [releases.data, version])

  return (
    <div className="ai-binding-row">
      <Form.Item
        name={[fieldName, 'componentKey']}
        rules={[{ required: true, message: '请选择 Skill' }]}
      >
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="Skill"
          options={skills.map((skill) => ({
            value: skill.key,
            label: `${skill.displayName} · ${skill.key}${skill.enabled ? '' : ' · 已停用'}`,
            disabled:
              !skill.enabled ||
              (skill.key !== componentKey &&
                allRows.some(
                  (item, index) => index !== fieldName && item?.componentKey === skill.key
                ))
          }))}
          onChange={(key: string) => {
            const skill = skills.find((item) => item.key === key)
            form.setFieldValue(['skillBindings', fieldName, 'version'], skill?.publishedVersion)
            form.setFieldValue(['skillBindings', fieldName, 'skillReleaseId'], undefined)
          }}
        />
      </Form.Item>
      <Form.Item
        name={[fieldName, 'version']}
        rules={[{ required: true, message: '请选择发布版本' }]}
      >
        <Select
          placeholder="版本"
          loading={releases.isLoading}
          options={releaseOptions}
          onChange={(value: string) => {
            const release = (releases.data ?? []).find((item) => item.version === value)
            form.setFieldValue(['skillBindings', fieldName, 'skillReleaseId'], release?.id)
          }}
        />
      </Form.Item>
      <Form.Item name={[fieldName, 'loadMode']} initialValue="AUTO" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'AUTO', label: 'AUTO' },
            { value: 'ALWAYS', label: 'ALWAYS' }
          ]}
        />
      </Form.Item>
      <Form.Item name={[fieldName, 'order']} initialValue={fieldName} rules={[{ required: true }]}>
        <InputNumber min={0} max={15} placeholder="顺序" style={{ width: '100%' }} />
      </Form.Item>
      <Button danger type="text" icon={<DeleteOutlined />} onClick={onRemove} />
    </div>
  )
}

function ValidationResult({ result }: { result: AiAgentCompileResult }) {
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type={result.valid ? 'success' : 'error'}
        showIcon
        message={result.valid ? '编译校验通过' : '编译校验未通过'}
        description={result.manifestDigest ? `manifest digest ${result.manifestDigest}` : undefined}
      />
      {result.errors.length > 0 ? (
        <List
          size="small"
          header={<Text strong>错误</Text>}
          dataSource={result.errors}
          renderItem={(item) => (
            <List.Item>
              <Space direction="vertical" size={0}>
                <Text strong>{item.code}</Text>
                <Paragraph style={{ margin: 0 }}>
                  {item.field ? `${item.field}: ` : ''}
                  {item.message}
                </Paragraph>
              </Space>
            </List.Item>
          )}
        />
      ) : null}
      {result.warnings.length > 0 ? (
        <List
          size="small"
          header={<Text strong>警告</Text>}
          dataSource={result.warnings}
          renderItem={(item) => (
            <List.Item>
              {item.field ? `${item.field}: ` : ''}
              {item.message}
            </List.Item>
          )}
        />
      ) : null}
      {result.manifestDigest ? (
        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="Manifest digest">
            <Text className="mono" copyable>
              {result.manifestDigest}
            </Text>
          </Descriptions.Item>
        </Descriptions>
      ) : null}
    </Space>
  )
}

function groupMcpBindings(bindings: AiAgentMcpBinding[]): McpBindingForm[] {
  const grouped = new Map<string, McpBindingForm>()
  bindings.forEach((binding) => {
    const key = `${binding.componentKey}@${binding.version}`
    const current = grouped.get(key)
    if (current) {
      current.toolNames = [...(current.toolNames ?? []), binding.toolName]
    } else {
      grouped.set(key, {
        mcpReleaseId: binding.mcpReleaseId,
        componentKey: binding.componentKey,
        version: binding.version,
        required: binding.required,
        executionOrder: binding.executionOrder,
        toolNames: [binding.toolName]
      })
    }
  })
  return [...grouped.values()].sort(
    (left, right) => (left.executionOrder ?? 0) - (right.executionOrder ?? 0)
  )
}

function buildMcpBindings(rows?: McpBindingForm[]): AiAgentMcpBindingInput[] {
  return (rows ?? []).flatMap((row) =>
    (row.toolNames ?? []).map((toolName) => ({
      mcpReleaseId: row.mcpReleaseId,
      componentKey: row.componentKey ?? '',
      version: row.version ?? '',
      toolName,
      required: row.required ?? true,
      executionOrder: row.executionOrder ?? 0
    }))
  )
}

function buildSkillBindings(rows?: SkillBindingForm[]): AiAgentSkillBindingInput[] {
  return (rows ?? []).map((row) => ({
    skillReleaseId: row.skillReleaseId,
    componentKey: row.componentKey ?? '',
    version: row.version ?? '',
    order: row.order ?? 0,
    loadMode: row.loadMode ?? 'AUTO'
  }))
}

function canonicalMcpBindings(
  bindings: Array<AiAgentMcpBinding | AiAgentMcpBindingInput>
): string[] {
  return bindings
    .map((binding) =>
      [
        binding.componentKey,
        binding.version,
        binding.toolName,
        binding.required ? '1' : '0',
        String(binding.executionOrder)
      ].join('\u0000')
    )
    .sort()
}

function canonicalSkillBindings(
  bindings: Array<AiAgentSkillBinding | AiAgentSkillBindingInput>
): string[] {
  return bindings
    .map((binding) =>
      [binding.componentKey, binding.version, String(binding.order), binding.loadMode].join(
        '\u0000'
      )
    )
    .sort()
}

function sameBindings(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function modelLabel(models: AiModelProfile[] | undefined, id?: string | null) {
  if (!id) return <Text type="secondary">未配置</Text>
  const model = models?.find((item) => item.id === id)
  return model ? `${model.displayName} · ${model.modelName}` : id
}

function nextPatchVersion(current?: string | null): string {
  if (!current || !/^\d+\.\d+\.\d+$/.test(current)) return '1.0.0'
  const [major, minor, patch] = current.split('.')
  return `${major}.${minor}.${Number(patch) + 1}`
}
