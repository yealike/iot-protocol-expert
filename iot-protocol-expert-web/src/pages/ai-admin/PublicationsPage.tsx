import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Progress,
  Select,
  Space,
  Steps,
  Table,
  Tabs,
  Tag,
  Typography
} from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import type { AiRegistryComponent, AiRegistryVersionDetail, AiPublication } from '../../api/types'
import { formatDateTime } from '../../utils/dateTime'
import {
  ErrorAlert,
  HealthTag,
  StateTag,
  readPublicationHistory,
  type PublicationHistoryItem
} from './shared'

const { Text } = Typography
const PHASES = ['VALIDATING', 'PREPARING', 'REGISTERING', 'PUBLISHED']

export default function PublicationsPage() {
  const { message } = App.useApp()
  const { publicationId } = useParams()
  const navigate = useNavigate()
  const [manualId, setManualId] = useState('')
  const [innerTab, setInnerTab] = useState<'progress' | 'registry'>('progress')
  const announcedPublicationRef = useRef<string | null>(null)
  const queryClient = useQueryClient()
  const publication = useQuery({
    queryKey: ['ai-publication', publicationId],
    queryFn: () => api.aiPublication(publicationId as string),
    enabled: Boolean(publicationId),
    refetchInterval: (query) => {
      const state = query.state.data?.state
      return state && !['PUBLISHED', 'FAILED'].includes(state) ? 3000 : false
    }
  })
  const retry = useMutation({
    mutationFn: () => api.aiRetryPublication(publicationId as string),
    onSuccess: () => {
      message.success('已提交重试')
      void queryClient.invalidateQueries({ queryKey: ['ai-publication', publicationId] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  useEffect(() => {
    const data = publication.data
    if (!data || data.state !== 'PUBLISHED' || announcedPublicationRef.current === data.id) {
      return
    }
    announcedPublicationRef.current = data.id
    message.success(`发布完成：${data.resourceType} ${data.version}`)
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: ['ai-agent-catalog'] }),
      queryClient.invalidateQueries({ queryKey: ['ai-agents-admin'] }),
      queryClient.invalidateQueries({ queryKey: ['ai-agent-detail'] })
    ])
  }, [message, publication.data, queryClient])

  const openPublication = () => {
    const id = manualId.trim()
    if (!id) {
      message.warning('请输入 publicationId')
      return
    }
    navigate(`/ai-admin/publications/${encodeURIComponent(id)}`)
  }

  return (
    <Tabs
      activeKey={innerTab}
      onChange={(key) => setInnerTab(key as 'progress' | 'registry')}
      items={[
        {
          key: 'progress',
          label: '发布进度',
          children: (
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <Card title="查询发布任务" size="small">
                <Space.Compact style={{ width: '100%', maxWidth: 720 }}>
                  <Input
                    value={manualId}
                    onChange={(event) => setManualId(event.target.value)}
                    placeholder="输入 publicationId"
                    onPressEnter={openPublication}
                  />
                  <Button icon={<SearchOutlined />} onClick={openPublication}>
                    查看
                  </Button>
                </Space.Compact>
                <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                  发布提交后会自动跳转到本页；发布状态在后端完成注册前会持续轮询。
                </Text>
              </Card>

              {publicationId ? (
                <PublicationDetail
                  id={publicationId}
                  data={publication.data}
                  loading={publication.isLoading}
                  error={publication.error}
                  retryPending={retry.isPending}
                  onRetry={() => retry.mutate()}
                />
              ) : null}

              <RecentPublications onOpen={(id) => navigate(`/ai-admin/publications/${id}`)} />
            </Space>
          )
        },
        {
          key: 'registry',
          label: 'Registry 健康',
          children: <RegistryHealth />
        }
      ]}
    />
  )
}

function PublicationDetail({
  id,
  data,
  loading,
  error,
  retryPending,
  onRetry
}: {
  id: string
  data?: AiPublication
  loading: boolean
  error: unknown
  retryPending: boolean
  onRetry: () => void
}) {
  const phase = data?.state === 'FAILED' ? data.phase : data?.phase || data?.state || ''
  const phaseIndex = Math.max(0, PHASES.indexOf(phase))
  const done = data?.state === 'PUBLISHED'
  const failed = data?.state === 'FAILED'
  return (
    <Card
      title="发布任务"
      loading={loading}
      extra={
        data ? (
          <Space>
            <StateTag state={data.state} />
            {failed ? (
              <Button
                size="small"
                icon={<ReloadOutlined />}
                loading={retryPending}
                onClick={onRetry}
              >
                重试
              </Button>
            ) : null}
          </Space>
        ) : null
      }
    >
      <ErrorAlert error={error} />
      {data ? (
        <>
          <Steps
            size="small"
            current={done ? PHASES.length : phaseIndex}
            status={failed ? 'error' : done ? 'finish' : 'process'}
            items={PHASES.map((item) => ({ title: item }))}
            style={{ marginBottom: 24 }}
          />
          {failed || data.errorMessage || data.errorCode ? (
            <Alert
              type={failed ? 'error' : 'warning'}
              showIcon
              style={{ marginBottom: 16 }}
              message={failed ? '发布失败' : '发布遇到问题，等待自动重试'}
              description={
                <Space direction="vertical" size={4}>
                  {data.errorMessage ? <span>{data.errorMessage}</span> : null}
                  {data.errorCode === 'SCHEMA_DRIFT' ? (
                    <span>请测试对应 MCP 的工具目录，发布新版本后更新 Agent 的工具绑定。</span>
                  ) : null}
                  {data.errorCode ? (
                    <Text type="secondary">
                      {data.errorCode}
                      {data.traceId ? ` · Trace ${data.traceId}` : ''}
                    </Text>
                  ) : null}
                </Space>
              }
            />
          ) : null}
          <Descriptions bordered size="small" column={{ xs: 1, md: 3 }}>
            <Descriptions.Item label="Publication ID" span={2}>
              <Text copyable>{id}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="资源类型">{data.resourceType}</Descriptions.Item>
            <Descriptions.Item label="资源 ID" span={2}>
              <Text copyable>{data.resourceId}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="版本">{data.version}</Descriptions.Item>
            <Descriptions.Item label="阶段">{data.phase}</Descriptions.Item>
            <Descriptions.Item label="草稿 revision">{data.draftRevision}</Descriptions.Item>
            <Descriptions.Item label="尝试次数">{data.attempts}</Descriptions.Item>
            <Descriptions.Item label="deadline">
              {data.deadlineAt ? formatDateTime(data.deadlineAt) : '-'}
            </Descriptions.Item>
            {!failed && !done ? (
              <Descriptions.Item label="下一次尝试">
                {data.nextAttemptAt ? formatDateTime(data.nextAttemptAt) : '-'}
              </Descriptions.Item>
            ) : null}
            <Descriptions.Item label="创建时间">{formatDateTime(data.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="更新时间" span={2}>
              {formatDateTime(data.updatedAt)}
            </Descriptions.Item>
          </Descriptions>
        </>
      ) : null}
    </Card>
  )
}

function RecentPublications({ onOpen }: { onOpen: (id: string) => void }) {
  const history = readPublicationHistory()
  if (history.length === 0) return null
  return (
    <Card title="本次浏览器最近发布" size="small">
      <Table<PublicationHistoryItem>
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={history}
        columns={[
          { title: '资源', dataIndex: 'resourceType', width: 90 },
          { title: '版本', dataIndex: 'version', width: 100, render: (value) => value || '-' },
          { title: 'Publication ID', dataIndex: 'id', ellipsis: true },
          {
            title: '发起时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (value: string) => formatDateTime(value)
          },
          {
            title: '操作',
            width: 80,
            render: (_, row) => (
              <Button type="link" size="small" onClick={() => onOpen(row.id)}>
                查看
              </Button>
            )
          }
        ]}
      />
    </Card>
  )
}

function RegistryHealth() {
  const [form] = Form.useForm<{ type: string; key: string; version: string }>()
  const [lookup, setLookup] = useState<{ type: string; key: string; version: string } | null>(null)
  const components = useQuery({
    queryKey: ['ai-registry-components'],
    queryFn: api.aiRegistryComponents,
    refetchInterval: 10_000
  })
  const detail = useQuery({
    queryKey: ['ai-registry-detail', lookup],
    queryFn: () => api.aiRegistryVersionDetail(lookup!.type, lookup!.key, lookup!.version),
    enabled: Boolean(lookup)
  })

  const isHealthy = (row: AiRegistryComponent) =>
    row.healthy ?? (row.observedState === 'READY' || row.observedState === 'HEALTHY')

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="Registry 视图只展示 Nacos 注册投影与健康实例定位，不回显任何凭据。"
      />
      <Card
        title="三类组件注册投影"
        extra={
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={components.isFetching}
            onClick={() => components.refetch()}
          >
            刷新
          </Button>
        }
      >
        <ErrorAlert error={components.error} />
        <Table<AiRegistryComponent>
          rowKey={(row) => `${row.resourceType}-${row.releaseId}-${row.providerId}`}
          loading={components.isLoading}
          dataSource={components.data ?? []}
          pagination={{ pageSize: 20 }}
          columns={[
            {
              title: '类型',
              dataIndex: 'resourceType',
              width: 90,
              render: (value: string) => <Tag>{value}</Tag>
            },
            {
              title: '服务名',
              dataIndex: 'serviceName',
              ellipsis: true,
              render: (value: string) => <Text copyable>{value}</Text>
            },
            {
              title: 'Provider',
              dataIndex: 'providerId',
              width: 160,
              ellipsis: true
            },
            {
              title: '期望',
              dataIndex: 'desiredState',
              width: 100,
              render: (value: string) => <StateTag state={value} />
            },
            {
              title: '观测',
              dataIndex: 'observedState',
              width: 100,
              render: (value: string) => <StateTag state={value} />
            },
            {
              title: '健康',
              width: 80,
              render: (_, row) => <HealthTag healthy={isHealthy(row)} />
            },
            {
              title: '最后成功',
              dataIndex: 'lastSuccessAt',
              width: 170,
              render: (value: string | null) => (value ? formatDateTime(value) : '-')
            },
            {
              title: '最近错误',
              dataIndex: 'lastError',
              ellipsis: true,
              render: (value: string | null) => (value ? <Text type="danger">{value}</Text> : '-')
            },
            {
              title: '操作',
              width: 80,
              render: (_, row) =>
                row.componentKey && row.componentVersion ? (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => {
                      const value = {
                        type: row.resourceType,
                        key: row.componentKey as string,
                        version: row.componentVersion as string
                      }
                      form.setFieldsValue(value)
                      setLookup(value)
                    }}
                  >
                    诊断
                  </Button>
                ) : (
                  '-'
                )
            }
          ]}
        />
      </Card>

      <Card title="精确版本实例诊断" size="small">
        <Form
          form={form}
          layout="inline"
          initialValues={{ type: 'AGENT', version: '1.0.0' }}
          onFinish={(value) => setLookup(value)}
        >
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select
              style={{ width: 120 }}
              options={[
                { value: 'MCP', label: 'MCP' },
                { value: 'SKILL', label: 'Skill' },
                { value: 'AGENT', label: 'Agent' }
              ]}
            />
          </Form.Item>
          <Form.Item name="key" label="组件 key" rules={[{ required: true }]}>
            <Input placeholder="protocol-expert" />
          </Form.Item>
          <Form.Item name="version" label="版本" rules={[{ required: true }]}>
            <Input placeholder="1.0.0" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
              查询实例
            </Button>
          </Form.Item>
        </Form>
        <ErrorAlert error={detail.error} />
        {detail.data ? <RegistryDetail detail={detail.data} /> : null}
      </Card>
    </Space>
  )
}

function RegistryDetail({ detail }: { detail: AiRegistryVersionDetail }) {
  return (
    <div style={{ marginTop: 20 }}>
      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }}>
        <Descriptions.Item label="组件">
          {detail.componentType} / {detail.componentKey}
        </Descriptions.Item>
        <Descriptions.Item label="版本">{detail.componentVersion || '-'}</Descriptions.Item>
        <Descriptions.Item label="服务名">{detail.serviceName || '-'}</Descriptions.Item>
        <Descriptions.Item label="期望状态">
          {detail.desiredState ? <StateTag state={detail.desiredState} /> : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="观测状态">
          {detail.observedState ? <StateTag state={detail.observedState} /> : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="健康实例">{detail.instances.length}</Descriptions.Item>
      </Descriptions>
      {detail.error ? (
        <Alert type="warning" showIcon message={detail.error} style={{ marginTop: 12 }} />
      ) : null}
      <Progress
        percent={detail.instances.length > 0 ? 100 : 0}
        status={detail.instances.length > 0 ? 'success' : 'exception'}
        showInfo={false}
        style={{ marginTop: 12 }}
      />
      <Table
        rowKey={(row) => `${row.ip}:${row.port}`}
        size="small"
        pagination={false}
        dataSource={detail.instances}
        locale={{ emptyText: '没有健康实例' }}
        columns={[
          { title: 'IP', dataIndex: 'ip' },
          { title: 'Port', dataIndex: 'port', width: 100 },
          {
            title: 'metadata',
            dataIndex: 'metadata',
            render: (value: Record<string, string>) => (
              <Space size={[4, 4]} wrap>
                {Object.entries(value ?? {}).map(([key, item]) => (
                  <Tag key={key}>
                    {key}={item}
                  </Tag>
                ))}
              </Space>
            )
          }
        ]}
      />
    </div>
  )
}
