import {
  Button,
  Card,
  Collapse,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Switch,
  Typography
} from 'antd'
import type { FormInstance } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { AiAgentComponent, AiAgentTeam, AiDelegateBinding } from '../../api/types'
import { ErrorAlert } from './shared'

type BindingForm = AiDelegateBinding & { componentId?: string }
type TeamForm = Omit<AiAgentTeam, 'delegates'> & { delegates: BindingForm[] }

export function AgentTeamEditor({
  agent,
  agents,
  revision,
  onSaved
}: {
  agent: AiAgentComponent
  agents: AiAgentComponent[]
  revision: () => number
  onSaved: (revision: number) => void
}) {
  const queryClient = useQueryClient()
  const [form] = Form.useForm<TeamForm>()
  const teamKey = ['ai-agent-team', agent.id]
  const team = useQuery({ queryKey: teamKey, queryFn: () => api.aiAgentTeam(agent.id) })
  const catalog = useQuery({ queryKey: ['ai-agent-catalog'], queryFn: api.aiAgentCatalog })
  const targets = agents.filter(
    (candidate) =>
      candidate.id !== agent.id &&
      candidate.enabled &&
      catalog.data?.some((entry) => entry.agentKey === candidate.key)
  )
  const initialValues: TeamForm | undefined = team.data && {
    ...team.data,
    delegates: team.data.delegates.map((binding) => ({
      ...binding,
      componentId: agents.find((candidate) => candidate.key === binding.targetAgentKey)?.id
    })),
    delegationPolicy: team.data.delegationPolicy ?? {
      maxDepth: 2,
      maxCallsPerRun: 4,
      maxParallel: 1,
      maxTotalTokens: 100000,
      maxWallClockMs: agent.budget?.runTimeoutMs ?? 120000
    }
  }
  const save = useMutation({
    mutationFn: async (values: TeamForm) => {
      const expected = revision()
      const saved = await api.aiSaveAgentTeam(
        agent.id,
        {
          capabilityTags: values.capabilityTags ?? [],
          a2aCapabilities: values.a2aCapabilities ?? [],
          delegates: (values.delegates ?? []).map((binding, index) => ({
            alias: binding.alias,
            targetReleaseId: binding.targetReleaseId,
            description: binding.description,
            whenToUse: binding.whenToUse,
            allowedTools: binding.allowedTools ?? [],
            required: binding.required ?? false,
            maxCalls: binding.maxCalls,
            timeoutMs: binding.timeoutMs,
            order: binding.order ?? index
          })),
          delegationPolicy: values.delegates?.length
            ? { ...values.delegationPolicy!, maxParallel: 1 }
            : null
        },
        expected
      )
      return { saved, expected }
    },
    onSuccess: ({ saved, expected }) => {
      onSaved(expected + 1)
      queryClient.setQueryData(teamKey, saved)
      void queryClient.invalidateQueries({ queryKey: ['ai-agent-detail', agent.id] })
    }
  })

  return (
    <Card size="small" title="Team 委派" loading={team.isPending}>
      {team.isError ? <ErrorAlert error={team.error} /> : null}
      {save.isError ? <ErrorAlert error={save.error} /> : null}
      {initialValues ? (
        <Form
          form={form}
          layout="vertical"
          initialValues={initialValues}
          onFinish={(values) => save.mutate(values)}
        >
          <Form.Item name="capabilityTags" label="能力标签">
            <Select mode="tags" maxCount={16} tokenSeparators={[',', '，']} />
          </Form.Item>
          <Collapse
            size="small"
            items={[
              {
                key: 'capabilities',
                label: '能力卡片声明',
                forceRender: true,
                children: (
                  <Form.List name="a2aCapabilities">
                    {(fields, { add, remove }) => (
                      <Space direction="vertical" style={{ width: '100%' }}>
                        {fields.map(({ key, name }) => (
                          <Card
                            size="small"
                            key={key}
                            extra={
                              <Button
                                type="text"
                                danger
                                icon={<DeleteOutlined />}
                                onClick={() => remove(name)}
                              />
                            }
                          >
                            <div className="ai-form-grid">
                              <Form.Item
                                name={[name, 'id']}
                                label="能力 ID"
                                rules={[{ required: true, max: 128 }]}
                              >
                                <Input placeholder="protocol.ocpp16.interpret" />
                              </Form.Item>
                              <Form.Item
                                name={[name, 'name']}
                                label="能力名称"
                                rules={[{ required: true, max: 128 }]}
                              >
                                <Input />
                              </Form.Item>
                              <Form.Item
                                name={[name, 'description']}
                                label="说明"
                                rules={[{ required: true, max: 512 }]}
                              >
                                <Input.TextArea autoSize />
                              </Form.Item>
                              <Form.Item name={[name, 'examples']} label="示例">
                                <Select mode="tags" maxCount={16} />
                              </Form.Item>
                            </div>
                          </Card>
                        ))}
                        <Button
                          icon={<PlusOutlined />}
                          disabled={fields.length >= 16}
                          onClick={() => add()}
                        >
                          添加能力
                        </Button>
                      </Space>
                    )}
                  </Form.List>
                )
              }
            ]}
          />
          <Form.List name="delegates">
            {(fields, { add, remove }) => (
              <Space direction="vertical" style={{ width: '100%', marginBlock: 12 }}>
                {fields.map(({ name, key }) => (
                  <BindingRow
                    key={key}
                    name={name}
                    form={form}
                    targets={targets}
                    onRemove={() => remove(name)}
                  />
                ))}
                <Button
                  icon={<PlusOutlined />}
                  disabled={fields.length >= 8}
                  onClick={() =>
                    add({ maxCalls: 3, timeoutMs: 60000, order: fields.length, required: false })
                  }
                >
                  添加委派目标
                </Button>
              </Space>
            )}
          </Form.List>
          <div className="ai-form-grid">
            <Form.Item
              name={['delegationPolicy', 'maxDepth']}
              label="最大深度"
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={3} precision={0} />
            </Form.Item>
            <Form.Item
              name={['delegationPolicy', 'maxCallsPerRun']}
              label="单 Run 委派次数"
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={10} precision={0} />
            </Form.Item>
            <Form.Item
              name={['delegationPolicy', 'maxTotalTokens']}
              label="根 Run Token 上限"
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={100000} precision={0} />
            </Form.Item>
            <Form.Item
              name={['delegationPolicy', 'maxWallClockMs']}
              label="子任务最长时限（毫秒）"
              rules={[{ required: true }]}
            >
              <InputNumber min={1} max={agent.budget?.runTimeoutMs ?? 300000} precision={0} />
            </Form.Item>
          </div>
          <Space wrap>
            <Button htmlType="submit" loading={save.isPending}>
              保存委派配置
            </Button>
            <Typography.Text type="secondary">
              同一父任务串行委派。保存后重新发布生效。
            </Typography.Text>
          </Space>
        </Form>
      ) : null}
    </Card>
  )
}

function BindingRow({
  name,
  form,
  targets,
  onRemove
}: {
  name: number
  form: FormInstance<TeamForm>
  targets: AiAgentComponent[]
  onRemove: () => void
}) {
  const componentId = Form.useWatch(['delegates', name, 'componentId'], form)
  const releaseId = Form.useWatch(['delegates', name, 'targetReleaseId'], form)
  const allowedTools = Form.useWatch(['delegates', name, 'allowedTools'], form)
  const target = useQuery({
    queryKey: ['ai-agent-detail', componentId],
    queryFn: () => api.aiAgent(componentId!),
    enabled: Boolean(componentId)
  })
  const releases = target.data?.releases?.filter((item) => item.state === 'ACTIVE') ?? []
  const release = releases.find((item) => item.id === releaseId)
  const tools =
    release?.manifest?.mcp?.flatMap((entry) =>
      entry.tools.map((tool) => ({
        value: tool.alias,
        label: `${entry.key} · ${tool.name}`
      }))
    ) ?? []
  return (
    <Card
      size="small"
      title="委派绑定"
      extra={
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          aria-label="移除委派绑定"
          onClick={onRemove}
        />
      }
    >
      {target.isError ? <ErrorAlert error={target.error} /> : null}
      <div className="ai-form-grid">
        <Form.Item
          name={[name, 'alias']}
          label="别名"
          rules={[{ required: true, pattern: /^[a-z][a-z0-9_]{1,39}$/ }]}
        >
          <Input placeholder="protocol_expert" />
        </Form.Item>
        <Form.Item name={[name, 'componentId']} label="目标 Agent" rules={[{ required: true }]}>
          <Select
            showSearch
            optionFilterProp="label"
            options={targets.map((item) => ({ value: item.id, label: item.displayName }))}
            onChange={() => {
              form.setFieldValue(['delegates', name, 'targetReleaseId'], undefined)
              form.setFieldValue(['delegates', name, 'allowedTools'], [])
            }}
          />
        </Form.Item>
        <Form.Item
          name={[name, 'targetReleaseId']}
          label="目标 Release"
          rules={[{ required: true }]}
          extra={release?.manifestDigest ? `摘要 ${release.manifestDigest.slice(0, 8)}` : undefined}
        >
          <Select
            loading={target.isFetching}
            options={releases.map((item) => ({ value: item.id, label: item.version }))}
            onChange={() => form.setFieldValue(['delegates', name, 'allowedTools'], [])}
          />
        </Form.Item>
        <Form.Item name={[name, 'required']} label="必需依赖" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item
          name={[name, 'description']}
          label="委派说明"
          rules={[{ required: true, max: 512 }]}
        >
          <Input.TextArea autoSize />
        </Form.Item>
        <Form.Item name={[name, 'whenToUse']} label="适用场景" rules={[{ max: 1024 }]}>
          <Input.TextArea autoSize />
        </Form.Item>
        <Form.Item
          name={[name, 'allowedTools']}
          label="允许的 MCP 工具"
          extra={
            !allowedTools?.length ? (
              <Typography.Text type="warning">
                留空会开放目标的全部已审核只读 MCP 工具。
              </Typography.Text>
            ) : undefined
          }
        >
          <Select mode="multiple" options={tools} />
        </Form.Item>
        <Form.Item name={[name, 'maxCalls']} label="调用次数" rules={[{ required: true }]}>
          <InputNumber min={1} max={10} precision={0} />
        </Form.Item>
        <Form.Item name={[name, 'timeoutMs']} label="执行时限（毫秒）" rules={[{ required: true }]}>
          <InputNumber min={5000} max={300000} precision={0} />
        </Form.Item>
        <Form.Item name={[name, 'order']} label="显示顺序">
          <InputNumber min={0} max={10000} precision={0} />
        </Form.Item>
      </div>
    </Card>
  )
}
