import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Button, Card, Empty, Input, Select, Space, Table } from 'antd'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { AlarmLevelTag, AlarmStateTag } from '../components/tags'
import type { AlarmLevel, AlarmState, AlarmView } from '../api/types'

export default function Alarms() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [state, setState] = useState<AlarmState | undefined>('OPEN')
  const [level, setLevel] = useState<AlarmLevel | undefined>()
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const alarms = useQuery({
    queryKey: ['alarms', state, level, keyword, page, size],
    queryFn: () => api.queryAlarms({ state, level, keyword: keyword || undefined, page, size }),
    refetchInterval: 15_000
  })

  const handle = useMutation({
    mutationFn: async ({ id, action }: { id: number; action: 'ack' | 'close' }) =>
      action === 'ack' ? api.ackAlarm(id) : api.closeAlarm(id),
    onSuccess: () => {
      message.success('已处理')
      queryClient.invalidateQueries({ queryKey: ['alarms'] })
      queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  return (
    <>
      <div className="page-header">
        <div>
          <h1>告警</h1>
          <p>规则命中 → 记录 → 确认 → 关闭；条件恢复后自动关闭</p>
        </div>
        <Space>
          <Input.Search
            allowClear
            placeholder="告警标题"
            style={{ width: 220 }}
            onSearch={(value) => {
              setPage(0)
              setKeyword(value)
            }}
          />
          <Select
            placeholder="状态"
            allowClear
            style={{ width: 130 }}
            value={state}
            options={[
              { label: '待处理', value: 'OPEN' },
              { label: '已确认', value: 'ACK' },
              { label: '已关闭', value: 'CLOSED' }
            ]}
            onChange={(value) => {
              setPage(0)
              setState(value)
            }}
          />
          <Select
            placeholder="级别"
            allowClear
            style={{ width: 130 }}
            value={level}
            options={[
              { label: 'INFO', value: 'INFO' },
              { label: 'WARN', value: 'WARN' },
              { label: 'MAJOR', value: 'MAJOR' },
              { label: 'CRITICAL', value: 'CRITICAL' }
            ]}
            onChange={(value) => {
              setPage(0)
              setLevel(value)
            }}
          />
        </Space>
      </div>

      <Card bordered={false} styles={{ body: { padding: 0 } }}>
        {alarms.isError ? (
          <Empty description={alarms.error instanceof Error ? alarms.error.message : '加载失败'} />
        ) : (
          <Table
            rowKey="id"
            size="middle"
            loading={alarms.isLoading}
            dataSource={alarms.data?.content ?? []}
            columns={[
              {
                title: '级别',
                dataIndex: 'level',
                width: 100,
                render: (value: AlarmLevel) => <AlarmLevelTag level={value} />
              },
              { title: '告警', dataIndex: 'title' },
              {
                title: '设备',
                dataIndex: 'deviceName',
                width: 220,
                render: (_: unknown, row: AlarmView) =>
                  row.deviceId ? <Link to={`/devices/${row.deviceId}`}>{row.deviceName}</Link> : '—'
              },
              { title: '通道', dataIndex: 'channelNo', width: 90 },
              {
                title: '状态',
                dataIndex: 'state',
                width: 100,
                render: (value: AlarmState) => <AlarmStateTag state={value} />
              },
              { title: '首次发生', dataIndex: 'firstAt', width: 170 },
              { title: '最近发生', dataIndex: 'lastAt', width: 170 },
              {
                title: '操作',
                width: 160,
                render: (_: unknown, row: AlarmView) => (
                  <Space size={4}>
                    <Button
                      size="small"
                      disabled={row.state !== 'OPEN'}
                      onClick={() => handle.mutate({ id: row.id, action: 'ack' })}
                    >
                      确认
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      ghost
                      disabled={row.state === 'CLOSED'}
                      onClick={() => handle.mutate({ id: row.id, action: 'close' })}
                    >
                      关闭
                    </Button>
                  </Space>
                )
              }
            ]}
            pagination={{
              current: (alarms.data?.page ?? 0) + 1,
              pageSize: alarms.data?.size ?? size,
              total: alarms.data?.total ?? 0,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条告警`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage - 1)
                setSize(nextSize)
              }
            }}
            scroll={{ x: 1100 }}
          />
        )}
      </Card>
    </>
  )
}
