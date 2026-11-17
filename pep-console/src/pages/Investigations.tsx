import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
  message
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { InvestigationEvidence, InvestigationTask } from '../api/types'

const { Paragraph, Text } = Typography
const { TextArea } = Input

const statusColor: Record<string, string> = {
  OPEN: 'default',
  INVESTIGATING: 'processing',
  WAITING_USER: 'orange',
  WAITING_APPROVAL: 'orange',
  WAITING_QUOTA: 'orange',
  VALIDATING: 'blue',
  RESOLVED: 'green',
  INCONCLUSIVE: 'purple',
  CANCELLED: 'red'
}

const nextStatus: Record<string, string> = {
  OPEN: 'INVESTIGATING',
  INVESTIGATING: 'VALIDATING',
  VALIDATING: 'RESOLVED'
}

export default function Investigations() {
  const queryClient = useQueryClient()
  const [projectId, setProjectId] = useState<string>()
  const [createOpen, setCreateOpen] = useState(false)
  const [selected, setSelected] = useState<InvestigationTask | null>(null)
  const [form] = Form.useForm<{ title: string; goal: string }>()

  const tenants = useQuery({ queryKey: ['tenants'], queryFn: api.tenants })
  const tenantId = tenants.data?.[0]?.id
  const projects = useQuery({
    queryKey: ['projects', tenantId],
    queryFn: () => api.projects(tenantId as string),
    enabled: Boolean(tenantId)
  })

  useEffect(() => {
    if (!projectId && projects.data?.[0]) {
      setProjectId(projects.data[0].id)
    }
  }, [projectId, projects.data])

  const tasks = useQuery({
    queryKey: ['investigations', projectId],
    queryFn: () => api.investigations(projectId as string),
    enabled: Boolean(projectId)
  })
  const evidence = useQuery({
    queryKey: ['investigation-evidence', selected?.id],
    queryFn: () => api.investigationEvidence(selected?.id as string),
    enabled: Boolean(selected?.id)
  })
  const events = useQuery({
    queryKey: ['investigation-events', selected?.id],
    queryFn: () => api.investigationEvents(selected?.id as string),
    enabled: Boolean(selected?.id)
  })

  const create = useMutation({
    mutationFn: (value: { title: string; goal: string }) =>
      api.createInvestigation(projectId as string, value.title, value.goal),
    onSuccess: (task) => {
      message.success('调查任务已创建')
      setCreateOpen(false)
      form.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['investigations', projectId] })
      setSelected(task)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const transition = useMutation({
    mutationFn: ({ task, status }: { task: InvestigationTask; status: string }) =>
      api.transitionInvestigation(task.id, task.version, status, '工作台状态推进'),
    onSuccess: (task) => {
      message.success('状态已更新')
      setSelected(task)
      void queryClient.invalidateQueries({ queryKey: ['investigations', projectId] })
      void queryClient.invalidateQueries({ queryKey: ['investigation-events', task.id] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const projectOptions = useMemo(
    () =>
      (projects.data ?? []).map((item) => ({
        label: `${item.name} (${item.code})`,
        value: item.id
      })),
    [projects.data]
  )

  return (
    <div className="page-stack">
      <Card
        title="调查任务"
        extra={
          <Space>
            <Select
              style={{ width: 240 }}
              placeholder="选择项目"
              value={projectId}
              options={projectOptions}
              onChange={setProjectId}
              loading={projects.isLoading}
            />
            <Button icon={<ReloadOutlined />} onClick={() => void tasks.refetch()}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={!projectId}
              onClick={() => setCreateOpen(true)}
            >
              新建
            </Button>
          </Space>
        }
      >
        {tasks.error ? (
          <Alert type="error" showIcon message={(tasks.error as Error).message} />
        ) : null}
        <Table<InvestigationTask>
          rowKey="id"
          size="middle"
          loading={tasks.isLoading}
          dataSource={tasks.data ?? []}
          onRow={(row) => ({ onClick: () => setSelected(row), style: { cursor: 'pointer' } })}
          locale={{ emptyText: <Empty description="还没有调查任务" /> }}
          columns={[
            { title: '标题', dataIndex: 'title', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 150,
              render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag>
            },
            { title: '版本', dataIndex: 'version', width: 80 },
            { title: '负责人', dataIndex: 'ownerId', width: 100 },
            { title: '更新时间', dataIndex: 'updatedAt', width: 190 }
          ]}
        />
      </Card>

      <Modal
        title="新建调查任务"
        open={createOpen}
        confirmLoading={create.isPending}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.validateFields().then((value) => create.mutate(value))}
        okText="创建"
      >
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="例如：充电桩启动失败定位" maxLength={256} />
          </Form.Item>
          <Form.Item name="goal" label="目标" rules={[{ required: true, message: '请输入目标' }]}>
            <TextArea rows={4} placeholder="描述要定位的现象、范围和期望结论" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        width={760}
        open={Boolean(selected)}
        title={selected?.title}
        onClose={() => setSelected(null)}
        extra={
          selected && nextStatus[selected.status] ? (
            <Button
              type="primary"
              loading={transition.isPending}
              onClick={() =>
                transition.mutate({ task: selected, status: nextStatus[selected.status] })
              }
            >
              推进到 {nextStatus[selected.status]}
            </Button>
          ) : null
        }
      >
        {selected ? (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Card size="small">
              <Space wrap>
                <Tag color={statusColor[selected.status]}>{selected.status}</Tag>
                <Text type="secondary">版本 {selected.version}</Text>
                <Text type="secondary">任务 {selected.id}</Text>
              </Space>
              <Paragraph style={{ marginTop: 12, marginBottom: 8 }}>{selected.goal}</Paragraph>
              <Text type="secondary">范围：{selected.scope}</Text>
            </Card>

            <Card size="small" title={`证据 (${evidence.data?.length ?? 0})`}>
              <Table<InvestigationEvidence>
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={evidence.data ?? []}
                locale={{ emptyText: '暂无证据' }}
                columns={[
                  { title: '来源', dataIndex: 'sourceType', width: 110 },
                  { title: '来源 ID', dataIndex: 'sourceId', ellipsis: true },
                  { title: '摘要', dataIndex: 'summary', ellipsis: true },
                  { title: '采集时间', dataIndex: 'collectedAt', width: 180 }
                ]}
              />
            </Card>

            <Card size="small" title="事件">
              {(events.data?.length ?? 0) === 0 ? (
                <Empty description="暂无事件" />
              ) : (
                <Timeline
                  items={(events.data ?? []).map((event) => ({
                    color: event.type.includes('STATUS') ? 'blue' : 'gray',
                    children: (
                      <div>
                        <Text strong>
                          #{event.seq} {event.type}
                        </Text>
                        <div>
                          <Text type="secondary">{event.createdAt}</Text>
                        </div>
                        <Text code style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                          {event.payload}
                        </Text>
                      </div>
                    )
                  }))}
                />
              )}
            </Card>
          </Space>
        ) : null}
      </Drawer>
    </div>
  )
}
